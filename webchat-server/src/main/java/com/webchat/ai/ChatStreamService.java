package com.webchat.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.webchat.ai.rag.DocumentIndexService;
import com.webchat.ai.rag.RagProperties;
import com.webchat.common.BizException;
import com.webchat.common.ResultCode;
import com.webchat.config.AttachmentProperties;
import com.webchat.config.CurrentUserProvider;
import com.webchat.entity.Attachment;
import com.webchat.entity.Conversation;
import com.webchat.entity.Message;
import com.webchat.mapper.ConversationMapper;
import com.webchat.mapper.MessageMapper;
import com.webchat.service.AttachmentService;
import com.webchat.service.ConversationService;
import com.webchat.storage.AttachmentStorageException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 流式对话的编排：合并「落库」与「生成」两条线。
 *
 * <p>整体分三段，段与段的边界决定了错误处理方式：
 * <ol>
 *   <li>同步前置（{@link #prepare}）——还在响应提交之前，失败走普通 JSON 错误响应</li>
 *   <li>文档检索 + 流式生成（{@link #stream}）——失败只能走 SSE 的 error 事件</li>
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

    /** 白名单保证 mime_type 必然以它、{@code video/} 或 {@code text/} 开头，据此分派 */
    private static final String IMAGE_PREFIX = "image/";
    private static final String TEXT_PREFIX = "text/";

    private final StreamingChatModel streamingChatModel;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ConversationService conversationService;
    private final AttachmentService attachmentService;
    private final AttachmentProperties attachmentProperties;
    private final CurrentUserProvider currentUserProvider;
    private final TitleGenerator titleGenerator;
    private final DocumentIndexService documentIndexService;
    private final RagProperties ragProperties;

    /**
     * 发送消息的同步前置：校验参数、落用户消息、绑定附件、组装模型输入。
     *
     * <p>用户消息在这里就落库，好处是生成失败只丢回复、不丢提问，同时给「重新生成」留下可寻的尾部用户消息。
     * 附件绑定也在同一个事务里——任何一条附件不可用都会把用户消息一起回滚，
     * 免得留下一条既无文字也无附件的空消息。
     *
     * <p>必须由控制器调用以走到事务代理上；若被本类内部自调，{@code @Transactional} 会静默失效。
     */
    @Transactional
    public ChatContext prepare(Long conversationId, String rawContent, List<Long> attachmentIds) {
        long userId = currentUserProvider.userId();
        Conversation conversation = conversationService.requireOwned(conversationId);

        String content = rawContent == null ? "" : rawContent.strip();
        List<Long> ids = attachmentIds == null ? List.of() : attachmentIds.stream().distinct().toList();
        // 只有附件、没有文字是允许的——传张图直接问「这是什么」很正常
        if (content.isEmpty() && ids.isEmpty()) {
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

        // 校验归属、未绑定、未过期，并回填 conversation_id
        attachmentService.bindToMessage(ids, userMessage.getId(), conversationId);

        return buildContext(conversation, userId, history, userMessage,
                attachmentService.findByMessageId(userMessage.getId()));
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
        // 附件轮的提问正文可能是空串，必须把它当初的附件一并取回来——否则重新生成等于发了个空提问，
        // 答案与首次必然不同，而用户以为在重跑同一问
        List<Attachment> attachments = attachmentService.findByMessageId(lastUserMessage.getId());
        return buildContext(conversation, userId, history, lastUserMessage, attachments);
    }

    /**
     * 把一次生成过程表示成事件流。
     *
     * <p>先做文档检索再出流：检索要调一次 embedding，结果要作为本轮提问的第一段文本注入，
     * 所以它必须排在组装请求之前。检索跑在弹性线程池上（不占 Tomcat 请求线程），
     * 并且<b>失败一律降级成「没检索到」</b>——资料取不到不该把一次对话变成失败。
     *
     * <p>注意本方法<b>不开事务</b>：整段生成期间持有数据库连接会很快耗尽连接池。
     */
    public Flux<ChatEvent> stream(ChatContext context) {
        Flux<ChatEvent> reply = Mono.fromCallable(() -> documentIndexService.knowledgeFor(
                        context.retrievalQuery(), context.retrievableAttachmentIds()))
                .subscribeOn(Schedulers.boundedElastic())
                // 检索在回复的关键路径上，而 SSE 协议里没有心跳事件：embedding 接口抖几秒，
                // 用户那边就是「一个事件都收不到」的假死。到点就当作没检索到
                .timeout(ragProperties.retrievalTimeout())
                .onErrorResume(e -> {
                    log.warn("文档检索失败，本轮不带资料继续：conversationId={}", context.conversationId(), e);
                    return Mono.just("");
                })
                .flatMapMany(knowledge -> replyFlux(context, knowledge));
        if (!context.titleNeeded()) {
            return reply;
        }
        return reply.concatWith(titleFlux(context));
    }

    private Flux<ChatEvent> replyFlux(ChatContext context, String knowledge) {
        List<ChatMessage> messages = assembleMessages(context, knowledge);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        return Flux.<ChatEvent>create(sink -> {
                    sink.onCancel(() -> cancelled.set(true));
                    requestModel(context, messages, sink, cancelled);
                }, FluxSink.OverflowStrategy.BUFFER)
                // 让模型调用与随后的落库都离开 Tomcat 请求线程
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 把系统提示词、历史、检索到的资料与本轮提问拼成最终发给模型的消息列表 */
    private List<ChatMessage> assembleMessages(ChatContext context, String knowledge) {
        List<ChatMessage> messages = new ArrayList<>(context.modelMessages());
        messages.add(toUserMessage(context.currentMessage(), context.currentAttachments(), knowledge));
        return List.copyOf(messages);
    }

    private void requestModel(ChatContext context, List<ChatMessage> messages,
                              FluxSink<ChatEvent> sink, AtomicBoolean cancelled) {
        StringBuilder accumulated = new StringBuilder();
        try {
            streamingChatModel.chat(
                    ChatRequest.builder().messages(messages).build(),
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

    private ChatContext buildContext(Conversation conversation, long userId, List<Message> history,
                                     Message userMessage, List<Attachment> userAttachments) {
        List<Message> recentHistory = trimToRecent(history);
        Map<Long, List<Attachment>> historyMedia = pickHistoryMedia(recentHistory, userAttachments);

        List<ChatMessage> modelMessages = new ArrayList<>();
        modelMessages.add(SystemMessage.from(Prompt.SYSTEM_PROMPT));
        for (Message message : recentHistory) {
            modelMessages.add(toChatMessage(message, historyMedia.getOrDefault(message.getId(), List.of())));
        }

        // 标题只在「标题仍是默认值」且「这条是该会话第一条用户消息」时生成。
        // 前半句让「重新生成」不会给已有标题的会话改名，后半句又让它能补上首次生成失败而没写成的标题。
        boolean titleNeeded = ConversationService.DEFAULT_TITLE.equals(conversation.getTitle())
                && history.stream().noneMatch(message -> ROLE_USER.equals(message.getRole()));

        return new ChatContext(
                conversation.getId(),
                userId,
                List.copyOf(modelMessages),
                userMessage,
                List.copyOf(userAttachments),
                retrievalQueryOf(userMessage),
                // 检索范围＝本会话内的全部文本附件，含本轮这条（刚绑定，已经带上 conversation_id）
                attachmentService.listTextAttachmentIds(conversation.getId()),
                titleNeeded,
                titleSourceOf(userMessage, userAttachments));
    }

    /**
     * 挑出历史里还要发给模型的附件。
     *
     * <p><b>媒体</b>（图片／视频）受预算约束：整轮请求的数量不超过单条消息的上限、字节数不超过配置的
     * 上限，本轮提问的附件优先占额度，剩下的从最近的历史消息往前补——最新那批正是用户刚发上去、
     * 语义上也最相关的，更早的只发文本。没有这道闸，20 条历史 × 5 个文件会把请求撑爆。
     *
     * <p><b>文本附件不受预算约束、也不占额度</b>：它的内容不进请求体（走检索），带上它只是为了在历史
     * 那条消息里渲染出一行「用户上传了文件：x.txt」。这一行不能省——那条消息的正文可能是空串
     * （只传文件没打字），少了它就成了一条内容为空的用户消息。
     */
    private Map<Long, List<Attachment>> pickHistoryMedia(List<Message> recentHistory,
                                                         List<Attachment> currentAttachments) {
        if (recentHistory.isEmpty()) {
            return Map.of();
        }
        List<Attachment> currentMedia = currentAttachments.stream().filter(a -> !isDocument(a)).toList();
        long remainingCount = attachmentProperties.maxFilesPerMessage() - currentMedia.size();
        long remainingBytes = attachmentProperties.maxRequestMediaSize().toBytes() - totalBytes(currentMedia);

        List<Long> historyIds = recentHistory.stream().map(Message::getId).toList();
        Map<Long, List<Attachment>> byMessage = attachmentService.findByMessageIds(historyIds).stream()
                .collect(Collectors.groupingBy(Attachment::getMessageId,
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, List<Attachment>> picked = new LinkedHashMap<>();
        long count = 0;
        long bytes = 0;
        // 从最新的一条往前取
        for (int index = recentHistory.size() - 1; index >= 0; index--) {
            Message message = recentHistory.get(index);
            if (!ROLE_USER.equals(message.getRole())) {
                continue;
            }
            for (Attachment attachment : byMessage.getOrDefault(message.getId(), List.of())) {
                if (isDocument(attachment)) {
                    picked.computeIfAbsent(message.getId(), key -> new ArrayList<>()).add(attachment);
                    continue;
                }
                if (count >= remainingCount || bytes + sizeOf(attachment) > remainingBytes) {
                    // 媒体额度用完就不再带更早的媒体，但循环要继续——后面的文本附件还得收进来说明文件
                    continue;
                }
                picked.computeIfAbsent(message.getId(), key -> new ArrayList<>()).add(attachment);
                count++;
                bytes += sizeOf(attachment);
            }
        }
        return picked;
    }

    private ChatMessage toChatMessage(Message message, List<Attachment> attachments) {
        return switch (message.getRole()) {
            // 历史轮次不注入资料：检索结果只对本轮提问有意义，混进历史反而会重复占篇幅
            case ROLE_USER -> toUserMessage(message, attachments, "");
            case ROLE_ASSISTANT -> AiMessage.from(message.getContent());
            case ROLE_SYSTEM -> SystemMessage.from(message.getContent());
            default -> throw new IllegalStateException("未知的消息角色：" + message.getRole());
        };
    }

    /**
     * 组装用户消息。
     *
     * <p>内容的顺序是：检索到的资料 → 用户正文 → 文本附件说明 → 媒体。资料放在最前是因为它服务于
     * 紧随其后的那个问题；它<b>不能单独作为一条 system 消息</b>——DashScope 的适配在清洗消息时，
     * 遇到「system 之后不是 user」会把那条消息静默丢掉，不报错也不抛异常，RAG 会彻底失效却查不出
     * 原因（见 {@link Prompt#KNOWLEDGE_PROMPT_TEMPLATE}）。
     *
     * @param knowledge 检索到的片段正文，没有资料时为空串
     */
    private UserMessage toUserMessage(Message message, List<Attachment> attachments, String knowledge) {
        String text = message.getContent();
        boolean hasKnowledge = knowledge != null && !knowledge.isBlank();
        // 既没有附件也没有资料时走最朴素的路径，请求体与加 RAG 之前完全一致
        if (attachments.isEmpty() && !hasKnowledge) {
            return UserMessage.from(text == null ? "" : text);
        }

        List<Content> contents = new ArrayList<>();
        if (hasKnowledge) {
            contents.add(TextContent.from(knowledge));
        }
        if (text != null && !text.isBlank()) {
            contents.add(TextContent.from(text));
        }
        // 文本附件本身不作为多模态内容发出去（内容走检索），但必须留下一行说明它是哪个文件，
        // 否则「只带附件、没有文字」的那一轮会得到一份空的 contents，触发下面那道守卫——
        // 用户收到的是「附件内容已不可用，请重新上传」，而文件其实好好的
        List<Attachment> documents = attachments.stream().filter(ChatStreamService::isDocument).toList();
        if (!documents.isEmpty()) {
            contents.add(TextContent.from(documentNote(documents)));
        }
        attachments.stream()
                .filter(attachment -> !isDocument(attachment))
                .map(this::toMediaContent)
                .flatMap(Optional::stream)
                .forEach(contents::add);

        if (contents.isEmpty()) {
            // 有附件却一个都没读出来（对象已被清理之类）。UserMessage 由构造器强制 contents 非空，
            // 与其让 LangChain4j 抛 IllegalArgumentException 变成 500，不如在这里说清楚
            throw new BizException(ResultCode.BAD_REQUEST, "附件内容已不可用，请重新上传");
        }
        return UserMessage.builder().contents(contents).build();
    }

    /**
     * 组装媒体内容。带附件时走多模态：文字与媒体各自是一个 content，媒体一律用 base64。
     *
     * <p>用 base64 而不是 URL，是因为开发环境的后端跑在内网（192.168.150.101），
     * 模型侧根本拉不到那个地址；LangChain4j 的 DashScope 适配会把 base64 拼成
     * {@code data:<mime>;base64,<数据>} 再发出去。
     *
     * <p>这里不需要显式打开什么「多模态开关」——DashScope 那个模型适配是按模型名判断的，
     * {@code qwen3.8-max} 的版本号已经让它默认走多模态分支。
     *
     * <p>调用方保证传进来的都是媒体：白名单里的文本类型在 {@link #isDocument} 那里就被拦下了，
     * 不拦的话它会被当成视频塞进 {@code VideoContent}。
     */
    private Optional<Content> toMediaContent(Attachment attachment) {
        try {
            String base64 = Base64.getEncoder().encodeToString(attachmentService.readContent(attachment));
            String mimeType = attachment.getMimeType();
            // 走到这里的只剩图片与视频两类，顶层类型就是这一处要的分派依据。
            // 工厂方法两个参数的顺序都是 (base64Data, mimeType)，且 mimeType 不能为空——
            // DashScope 适配据此拼成 data:<mime>;base64,<数据>
            return Optional.of(mimeType.startsWith(IMAGE_PREFIX)
                    ? ImageContent.from(base64, mimeType)
                    : VideoContent.from(base64, mimeType));
        } catch (AttachmentStorageException e) {
            // 历史附件读不出来就跳过这一段，不值得让整轮对话失败
            log.warn("附件内容读取失败，本轮跳过：id={}, objectKey={}",
                    attachment.getId(), attachment.getObjectKey(), e);
            return Optional.empty();
        }
    }

    /** 该附件是否走检索（文本），而不是作为媒体塞进请求体 */
    private static boolean isDocument(Attachment attachment) {
        String mimeType = attachment.getMimeType();
        return mimeType != null && mimeType.startsWith(TEXT_PREFIX);
    }

    private static String documentNote(List<Attachment> documents) {
        return documents.stream()
                .map(document -> "（用户上传了文件：" + document.getOriginalName() + "）")
                .collect(Collectors.joining());
    }

    /** 用于检索的提问文本；只带附件没打字时为空串，此时检索会被跳过 */
    private static String retrievalQueryOf(Message userMessage) {
        String content = userMessage.getContent();
        return content == null ? "" : content.strip();
    }

    /** 只有附件、没有文字时，拿文件名给标题生成器凑一个输入，总比给它一个空串强 */
    private static String titleSourceOf(Message userMessage, List<Attachment> attachments) {
        String content = userMessage.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }
        return attachments.isEmpty()
                ? ""
                : "（用户发来一个附件：" + attachments.get(0).getOriginalName() + "）";
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
        // 附件预算在这个裁剪结果上算，否则会为随后被裁掉的消息白读一遍磁盘
        return history.subList(size - Prompt.MAX_HISTORY_MESSAGES, size);
    }

    private static long totalBytes(List<Attachment> attachments) {
        return attachments.stream().mapToLong(ChatStreamService::sizeOf).sum();
    }

    private static long sizeOf(Attachment attachment) {
        return attachment.getFileSize() == null ? 0L : attachment.getFileSize();
    }

    private static String briefReason(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "生成失败：" + error.getClass().getSimpleName();
        }
        return "生成失败：" + (message.length() > 200 ? message.substring(0, 200) : message);
    }
}
