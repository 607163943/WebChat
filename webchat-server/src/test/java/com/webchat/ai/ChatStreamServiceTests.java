package com.webchat.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.webchat.ai.rag.DocumentIndexService;
import com.webchat.ai.rag.RagProperties;
import com.webchat.common.BizException;
import com.webchat.config.AttachmentProperties;
import com.webchat.config.CurrentUserProvider;
import com.webchat.entity.Attachment;
import com.webchat.entity.Conversation;
import com.webchat.entity.Message;
import com.webchat.mapper.ConversationMapper;
import com.webchat.mapper.MessageMapper;
import com.webchat.service.AttachmentService;
import com.webchat.service.ConversationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式对话编排的单元测试。
 *
 * <p>用假的 {@link StreamingChatModel} 驱动整个 Flux，覆盖几处最容易出错、又最难在手工联调中发现的约定：
 * 事件顺序、失败时不落库、标题失败不能污染已成功的回复，以及附件如何变成多模态消息。
 * 全程不联网、不消耗 API 额度、不碰数据库。
 */
@ExtendWith(MockitoExtension.class)
class ChatStreamServiceTests {

    private static final long USER_ID = 7L;
    private static final long CONVERSATION_ID = 1L;
    /** 落库时回填给助手消息的 id，与用户消息那个分开，便于断言 done 事件带的是哪一个 */
    private static final long ASSISTANT_MESSAGE_ID = 50L;

    @Mock
    private StreamingChatModel streamingChatModel;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ConversationService conversationService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private TitleGenerator titleGenerator;
    @Mock
    private DocumentIndexService documentIndexService;

    /** 直接用 record 构造，无需启动 Spring 上下文 */
    private final AttachmentProperties attachmentProperties = new AttachmentProperties(
            "./data/attachments", DataSize.ofMegabytes(10), DataSize.ofMegabytes(1), 5,
            DataSize.ofMegabytes(20), Duration.ofHours(24), Duration.ofMinutes(30), 5);

    private final RagProperties ragProperties = new RagProperties(
            5, 0.7, 1200, 200, 10000, Duration.ofSeconds(3));

    /**
     * 事件编排这些用例的输入：系统提示词 + 历史。**本轮提问不在这里**，
     * 它在检索出结果之后才由 {@code assembleMessages} 拼上去。
     */
    private static final List<ChatMessage> MODEL_MESSAGES =
            List.of(SystemMessage.from("系统提示词"), UserMessage.from("你好"));

    private ChatStreamService service;

    @BeforeEach
    void setUp() {
        service = new ChatStreamService(streamingChatModel, conversationMapper, messageMapper,
                conversationService, attachmentService, attachmentProperties,
                currentUserProvider, titleGenerator, documentIndexService, ragProperties);
        // 检索默认返回「没有资料」：多数用例测的是事件编排，与 RAG 无关。
        // 必须显式 stub —— mock 默认返回 null，而 Mono.fromCallable 拿到 null 会变成空流，
        // 表现是一次 delta 都收不到，排查起来会莫名其妙。
        // lenient 是因为并非每个用例都会走到检索；也不能放进 collect()，那样会在
        // 「用例把 knowledgeFor 改成抛异常」之后再调一次 when(...)，而 when 会先执行方法本身
        lenient().when(documentIndexService.knowledgeFor(any(), any())).thenReturn("");
    }

    /** 让模型依次吐出给定片段后正常结束 */
    private void modelEmits(String... tokens) {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            for (String token : tokens) {
                handler.onPartialResponse(token);
            }
            // 实现累积的是 onPartialResponse 的片段，不使用 ChatResponse，传 null 即可
            handler.onCompleteResponse(null);
            return null;
        }).when(streamingChatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    private void modelFailsAfter(String token, Throwable error) {
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse(token);
            handler.onError(error);
            return null;
        }).when(streamingChatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    }

    /** 落库时回填自增主键，模拟 MyBatis-Plus 的行为 */
    private void assistantInsertReturns(long id) {
        when(messageMapper.insert(any(Message.class))).thenAnswer(invocation -> {
            ((Message) invocation.getArgument(0)).setId(id);
            return 1;
        });
    }

    private List<ChatEvent> collect(ChatContext context) {
        return service.stream(context).collectList().block();
    }

    /** 真正发给模型的消息列表 */
    private List<ChatMessage> sentMessages() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streamingChatModel).chat(captor.capture(), any(StreamingChatResponseHandler.class));
        return captor.getValue().messages();
    }

    /** 发出去的最后一条就是本轮提问 */
    private static UserMessage lastUserMessageOf(List<ChatMessage> messages) {
        return (UserMessage) messages.get(messages.size() - 1);
    }

    /** 造一个「事件编排」用例用的 context：没有附件、不检索 */
    private static ChatContext contextOf(boolean titleNeeded, String titleSource) {
        return new ChatContext(1L, 7L, MODEL_MESSAGES, userMessage("你好"), List.of(),
                "你好", List.of(), titleNeeded, titleSource);
    }

    private static Message userMessage(String content) {
        Message message = new Message();
        message.setId(8L);
        message.setConversationId(1L);
        message.setRole("user");
        message.setContent(content);
        return message;
    }

    // ---------------------------------------------------------------- 事件编排

    @Test
    @DisplayName("新会话：若干 delta → done → title，顺序由结构保证")
    void emitsDeltaThenDoneThenTitle() {
        modelEmits("你", "好");
        assistantInsertReturns(42L);
        when(titleGenerator.generate("你好")).thenReturn(Optional.of("问候"));

        List<ChatEvent> events = collect(contextOf(true, "你好"));

        assertThat(events).containsExactly(
                new ChatEvent.Delta("你"),
                new ChatEvent.Delta("好"),
                new ChatEvent.Done(42L),
                new ChatEvent.Title("问候"));
        verify(conversationMapper).updateTitle(1L, 7L, "问候");
    }

    @Test
    @DisplayName("非首条消息不发 title 事件，也不去调模型生成标题")
    void skipsTitleWhenNotNeeded() {
        modelEmits("好");
        assistantInsertReturns(43L);

        List<ChatEvent> events = collect(contextOf(false, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(43L));
        verify(titleGenerator, never()).generate(anyString());
    }

    @Test
    @DisplayName("标题为空时不发 title 事件")
    void skipsTitleEventWhenTitleIsEmpty() {
        modelEmits("好");
        assistantInsertReturns(44L);
        when(titleGenerator.generate(anyString())).thenReturn(Optional.empty());

        List<ChatEvent> events = collect(contextOf(true, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(44L));
    }

    @Test
    @DisplayName("标题生成抛异常时，已落库的回复仍然是 done 而不是 error")
    void titleFailureDoesNotTurnSuccessIntoError() {
        modelEmits("好");
        assistantInsertReturns(45L);
        when(titleGenerator.generate(anyString())).thenThrow(new RuntimeException("标题服务不可用"));

        List<ChatEvent> events = collect(contextOf(true, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(45L));
        assertThat(events).noneMatch(ChatEvent.Failed.class::isInstance);
    }

    @Test
    @DisplayName("写入标题失败同样不影响 done")
    void updateTitleFailureDoesNotBreakStream() {
        modelEmits("好");
        assistantInsertReturns(46L);
        when(titleGenerator.generate(anyString())).thenReturn(Optional.of("问候"));
        doThrow(new RuntimeException("库挂了")).when(conversationMapper).updateTitle(anyLong(), anyLong(), anyString());

        List<ChatEvent> events = collect(contextOf(true, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(46L));
    }

    @Test
    @DisplayName("模型中途报错：发 error 事件，且助手回复不落库")
    void emitsErrorAndSkipsPersistence() {
        modelFailsAfter("半截", new RuntimeException("模型挂了"));

        List<ChatEvent> events = collect(contextOf(false, "你好"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new ChatEvent.Delta("半截"));
        assertThat(events.get(1)).isInstanceOf(ChatEvent.Failed.class);
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("模型一个字都没返回：发 error 事件，不落库空消息")
    void emitsErrorWhenModelReturnsNothing() {
        modelEmits();

        List<ChatEvent> events = collect(contextOf(false, "你好"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ChatEvent.Failed.class);
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("落库后的助手消息会刷新会话活跃时间")
    void refreshesConversationActivityTime() {
        modelEmits("好");
        assistantInsertReturns(47L);

        collect(contextOf(false, "你好"));

        verify(conversationMapper).touch(1L, 7L);
    }

    // ---------------------------------------------------------------- 附件与多模态

    /**
     * 让 prepare 能顺利走到「落用户消息」那一步，并让新插入的消息拿到 id。
     *
     * <p>轮次里既有用户消息也有助手回复，按角色分开回填，免得事件里那个 done 带出用户消息的 id。
     */
    private void prepareSucceeds(long userMessageId, List<Message> history) {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(conversationService.requireOwned(CONVERSATION_ID)).thenReturn(conversation("新对话"));
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(history));
        when(messageMapper.insert(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId("assistant".equals(message.getRole()) ? ASSISTANT_MESSAGE_ID : userMessageId);
            return 1;
        });
    }

    private static Conversation conversation(String title) {
        Conversation conversation = new Conversation();
        conversation.setId(CONVERSATION_ID);
        conversation.setTitle(title);
        return conversation;
    }

    private static Attachment attachment(long id, String mimeType, long size) {
        return attachment(id, mimeType, size, null);
    }

    /** 历史里的附件必须带 messageId —— 按消息分组时它是 key，为空会直接 NPE */
    private static Attachment attachment(long id, String mimeType, long size, Long messageId) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setMimeType(mimeType);
        attachment.setFileSize(size);
        attachment.setMessageId(messageId);
        attachment.setObjectKey("2026/09/23/" + id);
        attachment.setOriginalName("附件-" + id);
        return attachment;
    }

    @Test
    @DisplayName("带附件的提问：绑定到刚落的用户消息，并按 base64 拼成多模态消息")
    void sendsAttachmentsAsMultimodalContent() {
        prepareSucceeds(9L, List.of());
        Attachment image = attachment(100L, "image/png", 3);
        when(attachmentService.findByMessageId(9L)).thenReturn(List.of(image));
        when(attachmentService.readContent(image)).thenReturn(new byte[]{1, 2, 3});
        modelEmits("好");

        ChatContext context = service.prepare(CONVERSATION_ID, "这是什么", List.of(100L));
        assertThat(collect(context)).contains(new ChatEvent.Done(ASSISTANT_MESSAGE_ID));

        verify(attachmentService).bindToMessage(List.of(100L), 9L, CONVERSATION_ID);
        UserMessage sent = lastUserMessageOf(sentMessages());
        assertThat(sent.contents()).hasSize(2);
        assertThat(sent.contents().get(0)).isEqualTo(TextContent.from("这是什么"));
        assertThat(sent.contents().get(1)).isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) sent.contents().get(1)).image().base64Data()).isEqualTo("AQID");
    }

    @Test
    @DisplayName("只发附件不打字是允许的，标题生成拿文件名兜底")
    void allowsAttachmentOnlyMessage() {
        prepareSucceeds(9L, List.of());
        Attachment video = attachment(101L, "video/mp4", 3);
        when(attachmentService.findByMessageId(9L)).thenReturn(List.of(video));
        when(attachmentService.readContent(video)).thenReturn(new byte[]{1});
        modelEmits("好");

        ChatContext context = service.prepare(CONVERSATION_ID, "   ", List.of(101L));
        collect(context);

        UserMessage sent = lastUserMessageOf(sentMessages());
        // 文字为空时不塞空 TextContent，只留媒体本身。
        // 非图片的媒体走 VideoContent 分支（音频已被移出白名单，见 AttachmentTypePolicy）
        assertThat(sent.contents()).hasSize(1);
        assertThat(sent.contents().get(0)).isInstanceOf(VideoContent.class);
        assertThat(context.titleSource()).contains("附件-101");
    }

    @Test
    @DisplayName("只发文本附件不打字：留下说明文件的那行，不能变成一条空的用户消息")
    void rendersTextAttachmentAsNote() {
        prepareSucceeds(9L, List.of());
        Attachment document = attachment(104L, "text/plain", 3);
        when(attachmentService.findByMessageId(9L)).thenReturn(List.of(document));
        modelEmits("好");

        ChatContext context = service.prepare(CONVERSATION_ID, "   ", List.of(104L));
        collect(context);

        // 文本附件的内容走检索、不作为多模态内容发出去，但必须留下一行说明它是哪个文件：
        // 少了它 contents 就是空的，那道守卫会抛 400「附件内容已不可用」——而文件其实好好的
        UserMessage sent = lastUserMessageOf(sentMessages());
        assertThat(sent.contents()).hasSize(1);
        assertThat(sent.contents().get(0)).isInstanceOf(TextContent.class);
        assertThat(((TextContent) sent.contents().get(0)).text()).contains("附件-104");
    }

    @Test
    @DisplayName("文本附件不会被当成视频塞进请求体")
    void neverTreatsTextAsVideo() {
        prepareSucceeds(9L, List.of());
        Attachment document = attachment(105L, "text/plain", 3);
        when(attachmentService.findByMessageId(9L)).thenReturn(List.of(document));
        modelEmits("好");

        collect(service.prepare(CONVERSATION_ID, "总结一下", List.of(105L)));

        // 「非图片即视频」的旧分派会把 txt 包成 VideoContent 发出去，这里钉住它不再发生
        assertThat(lastUserMessageOf(sentMessages()).contents())
                .noneMatch(VideoContent.class::isInstance);
    }

    @Test
    @DisplayName("既没有文字也没有附件：400，且不落任何消息")
    void rejectsCompletelyEmptyMessage() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(conversationService.requireOwned(CONVERSATION_ID)).thenReturn(conversation("新对话"));

        assertThatThrownBy(() -> service.prepare(CONVERSATION_ID, "  ", List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("附件绑定失败时整个 prepare 抛出，用户消息不会留下")
    void propagatesBindFailure() {
        prepareSucceeds(9L, List.of());
        doThrow(new BizException(com.webchat.common.ResultCode.BAD_REQUEST, "有附件不可用"))
                .when(attachmentService).bindToMessage(any(), eq(9L), eq(CONVERSATION_ID));

        assertThatThrownBy(() -> service.prepare(CONVERSATION_ID, "看图", List.of(100L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可用");
    }

    @Test
    @DisplayName("附件读不出来但文字还在：跳过该附件而不是让整轮失败")
    void skipsUnreadableAttachmentWhenTextPresent() {
        prepareSucceeds(9L, List.of());
        Attachment broken = attachment(102L, "image/png", 3);
        when(attachmentService.findByMessageId(9L)).thenReturn(List.of(broken));
        when(attachmentService.readContent(broken))
                .thenThrow(new com.webchat.storage.AttachmentStorageException("丢了", null));
        modelEmits("好");

        collect(service.prepare(CONVERSATION_ID, "还在吗", List.of(102L)));

        UserMessage sent = lastUserMessageOf(sentMessages());
        assertThat(sent.contents()).hasSize(1);
        assertThat(sent.contents().get(0)).isEqualTo(TextContent.from("还在吗"));
    }

    @Test
    @DisplayName("重新生成：尾部用户消息的附件会被一并重建，不会退化成空提问")
    void regenerateKeepsAttachmentsOfLastUserMessage() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(conversationService.requireOwned(CONVERSATION_ID)).thenReturn(conversation("已命名"));
        Message userMessage = new Message();
        userMessage.setId(5L);
        userMessage.setRole("user");
        // 只发了附件的那条，正文是空串
        userMessage.setContent("");
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(userMessage)));
        Attachment image = attachment(103L, "image/jpeg", 3);
        when(attachmentService.findByMessageId(5L)).thenReturn(List.of(image));
        when(attachmentService.readContent(image)).thenReturn(new byte[]{9});
        modelEmits("好");

        collect(service.prepareRegenerate(CONVERSATION_ID));

        UserMessage sent = lastUserMessageOf(sentMessages());
        assertThat(sent.contents()).hasSize(1);
        assertThat(sent.contents().get(0)).isInstanceOf(ImageContent.class);
    }

    // ---------------------------------------------------------------- 检索

    @Test
    @DisplayName("检索到的片段作为本轮提问的第一段文本注入，system 提示词保持常量")
    void injectsRetrievedKnowledgeIntoTheQuestion() {
        modelEmits("好");
        when(documentIndexService.knowledgeFor("它讲了什么", List.of(104L)))
                .thenReturn("【片段 1｜来源：纪要.txt】\n季度目标");

        collect(new ChatContext(1L, 7L, MODEL_MESSAGES, userMessage("它讲了什么"), List.of(),
                "它讲了什么", List.of(104L), false, "它讲了什么"));

        List<ChatMessage> messages = sentMessages();
        // 资料跟着问题走，不动 system 提示词——合并进 system 会破坏 DashScope 的前缀缓存，
        // 而新增一条 system 会被它静默丢弃
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) messages.get(0)).text()).doesNotContain("季度目标");

        UserMessage sent = lastUserMessageOf(messages);
        assertThat(sent.contents().get(0)).isEqualTo(
                TextContent.from("【片段 1｜来源：纪要.txt】\n季度目标"));
        assertThat(sent.contents().get(1)).isEqualTo(TextContent.from("它讲了什么"));
    }

    @Test
    @DisplayName("检索范围取自会话内的文本附件，随 prepare 一并确定")
    void passesConversationDocumentsAsRetrievalScope() {
        prepareSucceeds(9L, List.of());
        when(attachmentService.listTextAttachmentIds(CONVERSATION_ID)).thenReturn(List.of(100L, 101L));
        modelEmits("好");

        collect(service.prepare(CONVERSATION_ID, "总结一下", List.of()));

        verify(documentIndexService).knowledgeFor("总结一下", List.of(100L, 101L));
    }

    @Test
    @DisplayName("检索失败不能把一次对话变成失败：降级成「没有资料」继续")
    void keepsAnsweringWhenRetrievalFails() {
        modelEmits("好");
        assistantInsertReturns(51L);
        when(documentIndexService.knowledgeFor(any(), any()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, MODEL_MESSAGES, userMessage("你好"),
                List.of(), "你好", List.of(100L), false, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(51L));
        assertThat(events).noneMatch(ChatEvent.Failed.class::isInstance);
    }

    // ---------------------------------------------------------------- 附件的媒体预算

    @Test
    @DisplayName("媒体额度用完时，历史里的文本附件仍要带上：它是那轮消息唯一的说明")
    void keepsHistoryDocumentsEvenWhenMediaBudgetIsExhausted() {
        Message historyUser = userMessage("上一轮");
        historyUser.setId(5L);
        prepareSucceeds(9L, List.of(historyUser));
        // 本条消息就把 5 个媒体的额度占满
        List<Attachment> current = List.of(
                attachment(200L, "image/png", 1), attachment(201L, "image/png", 1),
                attachment(202L, "image/png", 1), attachment(203L, "image/png", 1),
                attachment(204L, "image/png", 1));
        when(attachmentService.findByMessageId(9L)).thenReturn(current);
        current.forEach(item -> when(attachmentService.readContent(item)).thenReturn(new byte[]{1}));

        Attachment historyImage = attachment(300L, "image/png", 1, 5L);
        Attachment historyDocument = attachment(301L, "text/plain", 1, 5L);
        when(attachmentService.findByMessageIds(List.of(5L)))
                .thenReturn(List.of(historyImage, historyDocument));
        modelEmits("好");

        collect(service.prepare(CONVERSATION_ID, "都看看吧", List.of()));

        UserMessage historySent = (UserMessage) sentMessages().get(1);
        assertThat(historySent.contents())
                .as("媒体超预算被裁掉，文本附件的说明必须留下")
                .noneMatch(ImageContent.class::isInstance)
                .anyMatch(content -> content instanceof TextContent text
                        && text.text().contains("附件-301"));
    }
}
