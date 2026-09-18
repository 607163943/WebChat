package com.webchat.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.webchat.common.BizException;
import com.webchat.common.ResultCode;
import com.webchat.config.CurrentUserProvider;
import com.webchat.entity.Conversation;
import com.webchat.entity.Message;
import com.webchat.mapper.ConversationMapper;
import com.webchat.mapper.MessageMapper;
import com.webchat.service.ConversationService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式对话的编排：合并「落库」与「生成」两条线。
 *
 * <p>整体分三段，段与段的边界决定了错误处理方式：
 * <ol>
 *   <li>同步前置（{@link #prepare}）——还在响应提交之前，失败走普通 JSON 错误响应</li>
 *   <li>流式生成（{@link #stream}）——失败只能走 SSE 的 error 事件</li>
 *   <li>标题生成——排在 done 之后，失败必须静默吞掉</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_SYSTEM = "system";

    private final StreamingChatModel streamingChatModel;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ConversationService conversationService;
    private final CurrentUserProvider currentUserProvider;
    private final TitleGenerator titleGenerator;

    /**
     * 发送消息的同步前置：校验参数、落用户消息、组装模型输入。
     *
     * <p>用户消息在这里就落库，好处是生成失败只丢回复、不丢提问，同时给「重新生成」留下可寻的尾部用户消息。
     *
     * <p>必须由控制器调用以走到事务代理上；若被本类内部自调，{@code @Transactional} 会静默失效。
     */
    @Transactional
    public ChatContext prepare(Long conversationId, String rawContent) {
        long userId = currentUserProvider.userId();
        Conversation conversation = conversationService.requireOwned(conversationId);

        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "消息内容不能为空");
        }
        if (content.length() > Prompt.MAX_USER_MESSAGE_LENGTH) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "消息太长了，最多 " + Prompt.MAX_USER_MESSAGE_LENGTH + " 个字符");
        }

        // 先取历史再插入，这样历史里不含本条，正好用来判断「是不是首条用户消息」
        List<Message> history = selectMessages(conversationId);

        Message userMessage = new Message();
        userMessage.setConversationId(conversationId);
        userMessage.setRole(ROLE_USER);
        userMessage.setContent(content);
        messageMapper.insert(userMessage);
        conversationMapper.touch(conversationId, userId);

        return buildContext(conversation, userId, history, content);
    }

    /**
     * 重新生成的同步前置：删掉最后一条助手回复，改用其前面那条用户消息重跑。
     *
     * <p>由服务端来判断「重生成哪一条」，而不是让前端把内容再发一遍——这样用户消息不会被重复插入，
     * 连点两次也是安全的（第二次只是把它刚生成的那条回复再删掉重来）。
     */
    @Transactional
    public ChatContext prepareRegenerate(Long conversationId) {
        long userId = currentUserProvider.userId();
        Conversation conversation = conversationService.requireOwned(conversationId);

        List<Message> messages = new ArrayList<>(selectMessages(conversationId));

        int lastIndex = messages.size() - 1;
        if (lastIndex >= 0 && ROLE_ASSISTANT.equals(messages.get(lastIndex).getRole())) {
            messageMapper.deleteById(messages.get(lastIndex).getId());
            messages.remove(lastIndex);
        }
        if (messages.isEmpty() || !ROLE_USER.equals(messages.get(messages.size() - 1).getRole())) {
            throw new BizException(ResultCode.BAD_REQUEST, "没有可重新生成的提问");
        }

        Message lastUserMessage = messages.get(messages.size() - 1);
        List<Message> history = messages.subList(0, messages.size() - 1);
        return buildContext(conversation, userId, history, lastUserMessage.getContent());
    }

    /**
     * 把一次生成过程表示成事件流。
     *
     * <p>注意本方法<b>不开事务</b>：整段生成期间持有数据库连接会很快耗尽连接池。
     */
    public Flux<ChatEvent> stream(ChatContext context) {
        Flux<ChatEvent> reply = replyFlux(context);
        if (!context.titleNeeded()) {
            return reply;
        }
        return reply.concatWith(titleFlux(context));
    }

    private Flux<ChatEvent> replyFlux(ChatContext context) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        return Flux.<ChatEvent>create(sink -> {
                    sink.onCancel(() -> cancelled.set(true));
                    requestModel(context, sink, cancelled);
                }, FluxSink.OverflowStrategy.BUFFER)
                // 让模型调用与随后的落库都离开 Tomcat 请求线程
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void requestModel(ChatContext context, FluxSink<ChatEvent> sink, AtomicBoolean cancelled) {
        StringBuilder accumulated = new StringBuilder();
        try {
            streamingChatModel.chat(
                    ChatRequest.builder().messages(context.modelMessages()).build(),
                    new StreamingChatResponseHandler() {

                        @Override
                        public void onPartialResponse(String token) {
                            if (cancelled.get()) {
                                return;
                            }
                            accumulated.append(token);
                            sink.next(new ChatEvent.Delta(token));
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            if (cancelled.get()) {
                                // 客户端已断开：按约定本次回复不落库
                                sink.complete();
                                return;
                            }
                            persistReply(context, sink, accumulated.toString());
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (cancelled.get()) {
                                sink.complete();
                                return;
                            }
                            log.error("流式生成失败，conversationId={}", context.conversationId(), error);
                            sink.next(new ChatEvent.Failed(briefReason(error)));
                            sink.complete();
                        }
                    });
        } catch (Exception e) {
            log.error("调用模型失败，conversationId={}", context.conversationId(), e);
            sink.next(new ChatEvent.Failed(briefReason(e)));
            sink.complete();
        }
    }

    private void persistReply(ChatContext context, FluxSink<ChatEvent> sink, String text) {
        if (text.isEmpty()) {
            sink.next(new ChatEvent.Failed("模型没有返回任何内容"));
            sink.complete();
            return;
        }
        try {
            // 整条落库，绝不保存半截内容
            Message assistantMessage = new Message();
            assistantMessage.setConversationId(context.conversationId());
            assistantMessage.setRole(ROLE_ASSISTANT);
            assistantMessage.setContent(text);
            messageMapper.insert(assistantMessage);
            conversationMapper.touch(context.conversationId(), context.userId());
            sink.next(new ChatEvent.Done(assistantMessage.getId()));
        } catch (Exception e) {
            log.error("保存助手回复失败，conversationId={}", context.conversationId(), e);
            sink.next(new ChatEvent.Failed("回复保存失败"));
        }
        // 无论成败都是 complete 而不是 error：响应提交之后再调 sink.error 会重新进入
        // Spring 的异常解析器，可能把 JSON 错误体追加进已经开始输出的 SSE 流
        sink.complete();
    }

    /**
     * 标题生成事件，排在 done 之后。
     *
     * <p>{@code concatWith} 只在 reply 完成之后才订阅本 Flux，而 reply 要推完 Done 才会 complete，
     * 所以「若干 delta → done → title」的顺序由结构保证，不需要额外同步。
     */
    private Flux<ChatEvent> titleFlux(ChatContext context) {
        return Mono.fromCallable(() -> titleGenerator.generate(context.titleSource()).orElse(null))
                // 标题走的是阻塞式 ChatModel.chat(...)，必须换到弹性线程池
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(title -> {
                    conversationMapper.updateTitle(context.conversationId(), context.userId(), title);
                    return Flux.<ChatEvent>just(new ChatEvent.Title(title));
                })
                // 标题失败必须吞掉：此时助手消息已经落库，若让异常冒泡就会发出 error 事件，
                // 把一次成功且已持久化的回复变成「失败」
                .onErrorResume(e -> {
                    log.warn("生成会话标题失败，conversationId={}", context.conversationId(), e);
                    return Flux.empty();
                });
    }

    private static ChatContext buildContext(Conversation conversation, long userId,
                                            List<Message> history, String userContent) {
        List<ChatMessage> modelMessages = new ArrayList<>();
        modelMessages.add(SystemMessage.from(Prompt.SYSTEM_PROMPT));
        modelMessages.addAll(toChatMessages(trimToRecent(history)));
        modelMessages.add(UserMessage.from(userContent));

        // 标题只在「标题仍是默认值」且「这条是该会话第一条用户消息」时生成。
        // 前半句让「重新生成」不会给已有标题的会话改名，后半句又让它能补上首次生成失败而没写成的标题。
        boolean titleNeeded = ConversationService.DEFAULT_TITLE.equals(conversation.getTitle())
                && history.stream().noneMatch(message -> ROLE_USER.equals(message.getRole()));

        return new ChatContext(conversation.getId(), userId, List.copyOf(modelMessages), titleNeeded, userContent);
    }

    private List<Message> selectMessages(Long conversationId) {
        return messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getId));
    }

    private static List<Message> trimToRecent(List<Message> history) {
        int size = history.size();
        if (size <= Prompt.MAX_HISTORY_MESSAGES) {
            return history;
        }
        return history.subList(size - Prompt.MAX_HISTORY_MESSAGES, size);
    }

    private static List<ChatMessage> toChatMessages(List<Message> messages) {
        return messages.stream().map(ChatStreamService::toChatMessage).toList();
    }

    private static ChatMessage toChatMessage(Message message) {
        return switch (message.getRole()) {
            case ROLE_USER -> UserMessage.from(message.getContent());
            case ROLE_ASSISTANT -> AiMessage.from(message.getContent());
            case ROLE_SYSTEM -> SystemMessage.from(message.getContent());
            default -> throw new IllegalStateException("未知的消息角色：" + message.getRole());
        };
    }

    private static String briefReason(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "生成失败：" + error.getClass().getSimpleName();
        }
        return "生成失败：" + (message.length() > 200 ? message.substring(0, 200) : message);
    }
}
