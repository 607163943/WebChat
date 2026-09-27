package com.webchat.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.webchat.ai.rag.DocumentIndexService;
import com.webchat.ai.rag.RagProperties;
import com.webchat.common.BizException;
import com.webchat.common.ResultCode;
import com.webchat.config.CurrentUserProvider;
import com.webchat.entity.Attachment;
import com.webchat.entity.Conversation;
import com.webchat.entity.Message;
import com.webchat.mapper.ConversationMapper;
import com.webchat.mapper.MessageMapper;
import com.webchat.service.AttachmentService;
import com.webchat.service.ConversationService;
import dev.langchain4j.data.message.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式对话的编排：合并「落库」与「生成」两条线。
 *
 * <p>整体分三段，段与段的边界决定了错误处理方式：
 * <ol>
 *   <li>同步前置（{@link #prepare}）——校验、落用户消息、灌记忆、组装本轮提问；
 *       此时的失败还在响应提交之前，可以走普通 JSON 错误响应</li>
 *   <li>流式生成（{@link #stream}）——调 {@link ChatAssistant}，检索与记忆由框架接管；
 *       开始写出之后的失败只能走 SSE 的 error 事件</li>
 *   <li>标题生成——排在 done 之后，失败必须静默吞掉</li>
 * </ol>
 *
 * <p>模型侧的三件事（系统提示词、记忆、检索）都不在本类里：本类只负责「库里怎么记」与
 * 「这一轮怎么收场」，装配见 {@link AssistantConfig}，检索见
 * {@link com.webchat.ai.rag.DocumentIndexService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ChatAssistant chatAssistant;
    private final ConversationMemoryStore memoryStore;
    private final ChatMessageAssembler messageAssembler;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ConversationService conversationService;
    private final AttachmentService attachmentService;
    private final CurrentUserProvider currentUserProvider;
    private final TitleGenerator titleGenerator;
    private final DocumentIndexService documentIndexService;
    private final RagProperties ragProperties;

    /**
     * 发送消息的同步前置：校验参数、落用户消息、绑定附件、灌记忆、组装本轮提问。
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
                attachmentService.findByMessageId(userMessage.getId()), null);
    }

    /**
     * 重新生成的同步前置：找到最后一条助手回复，改用其前面那条用户消息重跑。
     *
     * <p>由服务端来判断「重生成哪一条」，而不是让前端把内容再发一遍——这样用户消息不会被重复插入，
     * 连点两次也是安全的（第二次只是把它刚生成的那条回复再覆盖一遍）。
     *
     * <p>那条旧回复<b>不删</b>，本轮结束后原地覆盖它（见 {@link #saveReply}）：删了再插会换一个 id，
     * 而这一轮同样可能以半截内容收场，覆盖是唯一不留下垃圾行的做法。
     */
    @Transactional
    public ChatContext prepareRegenerate(Long conversationId) {
        long userId = currentUserProvider.userId();
        Conversation conversation = conversationService.requireOwned(conversationId);

        List<Message> messages = new ArrayList<>(selectMessages(conversationId));

        Long existingReplyId = null;
        int lastIndex = messages.size() - 1;
        if (lastIndex >= 0 && ROLE_ASSISTANT.equals(messages.get(lastIndex).getRole())) {
            existingReplyId = messages.get(lastIndex).getId();
            // 只是从上下文里摘掉：它是本轮要覆盖的对象，不该作为历史发给模型。
            // 尾条不是助手回复时（首轮提问后重生成）保持 null，本轮新写一条
            messages.remove(lastIndex);
        }
        if (messages.isEmpty() || !ROLE_USER.equals(messages.get(messages.size() - 1).getRole())) {
            throw new BizException(ResultCode.BAD_REQUEST, "没有可重新生成的提问");
        }

        Message lastUserMessage = messages.get(messages.size() - 1);
        // 附件轮的提问正文可能是空串，必须把它当初的附件一并取回来——否则重新生成等于发了个空提问，
        // 答案与首次必然不同，而用户以为在重跑同一问
        List<Attachment> attachments = attachmentService.findByMessageId(lastUserMessage.getId());
        // 历史只到这条提问之前：这条提问自己要被当成「本轮提问」再发一次，
        // 留在记忆里就成了同一句话的两次出现
        return buildContext(conversation, userId, messages.subList(0, messages.size() - 1),
                lastUserMessage, attachments, existingReplyId);
    }

    /**
     * 把一次生成过程表示成事件流。
     *
     * <p>先做文档检索再出流：检索要调一次 embedding，结果要作为本轮提问的第一段文本，
     * 所以它必须排在调用模型之前。检索跑在弹性线程池上（不占 Tomcat 请求线程），
     * 并且<b>失败一律降级成「没检索到」</b>——资料取不到不该把一次对话变成失败。
     *
     * <p>注意本方法<b>不开事务</b>：整段生成期间持有数据库连接会很快耗尽连接池。
     *
     * <p>事件顺序是「若干 delta → done →（仅新会话）title」，失败则以 error 结束。
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

    /**
     * 一次生成：把模型吐出的增量变成 delta 事件，并在收尾时落库。
     *
     * <p>三种收场都要落库，靠 {@link ReplyState} 这个「谁写」的仲裁者区分：正常结束由完成回调写、
     * 报错由失败分支写、客户端断开由取消回调写。几个算子各自的职责见方法内注释。
     */
    private Flux<ChatEvent> replyFlux(ChatContext context, String knowledge) {
        List<Content> question = ChatMessageAssembler.withKnowledge(context.currentContents(), knowledge);
        ReplyState state = new ReplyState();
        // defer 是必要的：真正的调用发生在订阅时，也就是下面 subscribeOn 切过去的弹性线程上，
        // 而不是控制器的 Tomcat 线程上
        return Flux.defer(() -> chatAssistant.chat(context.conversationId(), question))
                // 只累计、不下发：断开之后模型可能还会吐几个增量，累计到快照里没有意义，
                // 而「发不发得出去」由下游是否已取消决定
                .doOnNext(state::append)
                .<ChatEvent>map(ChatEvent.Delta::new)
                // 正常结束：取走正文并落库，发 done（或「模型没返回任何内容」）
                .concatWith(Flux.defer(() -> completionEvents(context, state)))
                // 生成中途报错：把出错前已生成的部分落库为 failed，再以 error 事件收场。
                // 吞掉异常而不是继续向外抛：响应提交之后再抛会重新进入 Spring 的异常解析器，
                // 可能把 JSON 错误体追加进已经开始输出的 SSE 流
                .onErrorResume(error -> failureEvents(context, state, error))
                // 客户端断开：用户点「停止生成」、切会话、关页面，服务端看到的都是这一条路。
                // 把断开那一刻已经生成的部分落库——用户看过这段内容，刷新后它不该凭空消失
                .doOnCancel(() -> saveInterrupted(context, state))
                // 让模型调用、检索与随后的落库都离开 Tomcat 请求线程
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 正常结束：落 completed，发 done；一个字都没生成时不落库，只发一条 error */
    private Flux<ChatEvent> completionEvents(ChatContext context, ReplyState state) {
        String text = state.claim();
        if (text == null) {
            // 写入权已被取消路径取走：这一轮的内容早就不归这里管了
            return Flux.empty();
        }
        if (text.isEmpty()) {
            return Flux.just(new ChatEvent.Failed("模型没有返回任何内容"));
        }
        Long messageId = saveReply(context, text, Message.STATUS_COMPLETED);
        return Flux.just(messageId == null
                ? new ChatEvent.Failed("回复保存失败")
                : new ChatEvent.Done(messageId));
    }

    /**
     * 生成中途报错：把出错前已生成的部分落库为 {@code failed}，再以 error 事件收场。
     *
     * <p>一个字都没生成时什么都不写：空消息既没有展示价值，作为历史发给模型也会出问题。
     */
    private Flux<ChatEvent> failureEvents(ChatContext context, ReplyState state, Throwable error) {
        log.error("流式生成失败，conversationId={}", context.conversationId(), error);
        String text = state.claim();
        if (text == null) {
            // 客户端已经断开，取消路径先把这一轮写走了
            return Flux.empty();
        }
        if (!text.isEmpty()) {
            saveReply(context, text, Message.STATUS_FAILED);
        }
        return Flux.just(new ChatEvent.Failed(briefReason(error)));
    }

    /** 客户端断开：把断开那一刻的快照落库为 {@code interrupted}，一个字都没有时什么都不写 */
    private void saveInterrupted(ChatContext context, ReplyState state) {
        String snapshot = state.cancelAndSnapshot();
        if (snapshot != null && !snapshot.isEmpty()) {
            saveReply(context, snapshot, Message.STATUS_INTERRUPTED);
        }
    }

    /**
     * 落库一轮回复：重新生成时原地覆盖那条旧回复，否则新写一条。
     *
     * <p>判空留在调用方——「模型一个字没吐」与「写库失败」要给用户不同的文案，
     * 收进来的话这个区别就没了。
     *
     * @param status 这一轮是怎么收场的，取值见 {@link Message} 的 {@code STATUS_*} 常量
     * @return 落库后的消息 id；写库失败返回 null，由调用方决定怎么收场
     */
    private Long saveReply(ChatContext context, String text, String status) {
        try {
            Long messageId;
            if (context.existingReplyId() == null) {
                Message reply = new Message();
                reply.setConversationId(context.conversationId());
                reply.setRole(ROLE_ASSISTANT);
                reply.setContent(text);
                reply.setStatus(status);
                messageMapper.insert(reply);
                messageId = reply.getId();
            } else {
                messageId = context.existingReplyId();
                int updated = messageMapper.updateReply(messageId, context.conversationId(), text, status);
                if (updated == 0) {
                    // 那一行不在了（或已被别处删掉）：内容并没存下去，别让调用方以为写成功了
                    log.warn("覆盖助手回复没有命中任何行：messageId={}, conversationId={}",
                            messageId, context.conversationId());
                    return null;
                }
            }
            conversationMapper.touch(context.conversationId(), context.userId());
            return messageId;
        } catch (Exception e) {
            log.error("保存助手回复失败，conversationId={}", context.conversationId(), e);
            return null;
        }
    }

    /**
     * 一轮生成的共享状态：累计正文、是否已被取消、这次写入归谁。
     *
     * <p>取消信号与生成回调可能来自不同线程（模型推送线程、容器察觉断开的线程），
     * 所以全部改动都收在这一个监视器里。
     *
     * <p>「谁写」由返回值决定：{@link #cancelAndSnapshot} 与 {@link #claim} 互斥，
     * 只有一个能拿到非 null，所以半截内容与完整内容不会被写两次，也不需要额外的标记。
     */
    private static final class ReplyState {

        private final StringBuilder accumulated = new StringBuilder();
        private boolean cancelled;
        private boolean claimed;

        /** 追加一段增量；已被取消就丢掉——断开之后的增量不属于用户看到过的那段内容 */
        synchronized void append(String token) {
            if (!cancelled) {
                accumulated.append(token);
            }
        }

        /** 客户端断开：置位并取走「断开那一刻」的快照；返回 null 表示这次写入已经不归取消路径 */
        synchronized String cancelAndSnapshot() {
            cancelled = true;
            return claimLocked();
        }

        /** 生成结束（正常或报错）：取走快照并占住写入权；已被取消时返回 null */
        synchronized String claim() {
            return cancelled ? null : claimLocked();
        }

        private String claimLocked() {
            if (claimed) {
                return null;
            }
            claimed = true;
            return accumulated.toString();
        }
    }

    /**
     * 标题生成事件，排在 done 之后。
     *
     * <p>{@code concatWith} 只在 reply 完成之后才订阅本 Flux，而 reply 要推完 Done 才会 complete，
     * 所以「若干 delta → done → title」的顺序由结构保证，不需要额外同步。
     */
    private Flux<ChatEvent> titleFlux(ChatContext context) {
        return Flux.defer(() -> {
                    String title = titleGenerator.generate(context.titleSource()).orElse(null);
                    if (title == null) {
                        return Flux.<ChatEvent>empty();
                    }
                    conversationMapper.updateTitle(context.conversationId(), context.userId(), title);
                    return Flux.<ChatEvent>just(new ChatEvent.Title(title));
                })
                // 标题走的是阻塞式 ChatModel.chat(...)，必须换到弹性线程池
                .subscribeOn(Schedulers.boundedElastic())
                // 标题失败必须吞掉：此时助手消息已经落库，若让异常冒泡就会发出 error 事件，
                // 把一次成功且已持久化的回复变成「失败」
                .onErrorResume(e -> {
                    log.warn("生成会话标题失败，conversationId={}", context.conversationId(), e);
                    return Flux.empty();
                });
    }

    /**
     * 组装本轮上下文，并把系统提示词与历史灌进该会话的记忆槽位。
     *
     * <p><b>记忆每轮重建</b>，而不是首轮灌一次、之后增量维护：库是唯一真源，这样中断／失败落库的半截回复、
     * 重启、切会话、重新生成都不会让「模型看到的」与「库里记着的」分叉；历史附件的媒体预算也才谈得上
     * 每轮重算（见 {@link ChatMessageAssembler}）。灌进去的那一批里，最后一条必然是助手回复或用户消息，
     * 本轮提问随后由框架追加在它之后。
     *
     * @param history 本轮之前的历史；不含本轮提问，重生成时也不含那条要被覆盖的旧回复
     */
    private ChatContext buildContext(Conversation conversation, long userId, List<Message> history,
                                     Message userMessage, List<Attachment> userAttachments,
                                     Long existingReplyId) {
        memoryStore.seed(conversation.getId(),
                messageAssembler.renderMemory(history, userAttachments));

        // 标题只在「标题仍是默认值」且「这条是该会话第一条用户消息」时生成。
        // 前半句让「重新生成」不会给已有标题的会话改名，后半句又让它能补上首次生成失败而没写成的标题
        boolean titleNeeded = ConversationService.DEFAULT_TITLE.equals(conversation.getTitle())
                && history.stream().noneMatch(message -> ROLE_USER.equals(message.getRole()));

        List<Content> question = messageAssembler.renderQuestion(userMessage, userAttachments);
        return new ChatContext(
                conversation.getId(),
                userId,
                question,
                retrievalQueryOf(userMessage),
                // 检索范围＝本会话内的全部文本附件，含本轮这条（刚绑定，已经带上 conversation_id）
                attachmentService.listTextAttachmentIds(conversation.getId()),
                titleNeeded,
                titleSourceOf(userMessage, userAttachments),
                existingReplyId);
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

    private static String briefReason(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "生成失败：" + error.getClass().getSimpleName();
        }
        return "生成失败：" + (message.length() > 200 ? message.substring(0, 200) : message);
    }
}
