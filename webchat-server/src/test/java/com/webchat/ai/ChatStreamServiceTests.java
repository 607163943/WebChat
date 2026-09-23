package com.webchat.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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

    /** 直接用 record 构造，无需启动 Spring 上下文 */
    private final AttachmentProperties attachmentProperties = new AttachmentProperties(
            "./data/attachments", DataSize.ofMegabytes(10), 5, DataSize.ofMegabytes(20),
            Duration.ofHours(24), Duration.ofMinutes(30), 5);

    /**
     * 模型输入不能为空——LangChain4j 的 ChatRequest 会直接拒绝空消息列表。
     * 生产代码里 buildContext 至少会拼上系统提示词与本轮提问，这里照做。
     */
    private static final List<ChatMessage> MODEL_MESSAGES = List.of(UserMessage.from("你好"));

    private ChatStreamService service;

    @BeforeEach
    void setUp() {
        service = new ChatStreamService(streamingChatModel, conversationMapper, messageMapper,
                conversationService, attachmentService, attachmentProperties,
                currentUserProvider, titleGenerator);
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

    // ---------------------------------------------------------------- 事件编排

    @Test
    @DisplayName("新会话：若干 delta → done → title，顺序由结构保证")
    void emitsDeltaThenDoneThenTitle() {
        modelEmits("你", "好");
        assistantInsertReturns(42L);
        when(titleGenerator.generate("你好")).thenReturn(Optional.of("问候"));

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, MODEL_MESSAGES, true, "你好"));

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

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, MODEL_MESSAGES, false, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(43L));
        verify(titleGenerator, never()).generate(anyString());
    }

    @Test
    @DisplayName("标题为空时不发 title 事件")
    void skipsTitleEventWhenTitleIsEmpty() {
        modelEmits("好");
        assistantInsertReturns(44L);
        when(titleGenerator.generate(anyString())).thenReturn(Optional.empty());

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, MODEL_MESSAGES, true, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(44L));
    }

    @Test
    @DisplayName("标题生成抛异常时，已落库的回复仍然是 done 而不是 error")
    void titleFailureDoesNotTurnSuccessIntoError() {
        modelEmits("好");
        assistantInsertReturns(45L);
        when(titleGenerator.generate(anyString())).thenThrow(new RuntimeException("标题服务不可用"));

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, MODEL_MESSAGES, true, "你好"));

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

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, MODEL_MESSAGES, true, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(46L));
    }

    @Test
    @DisplayName("模型中途报错：发 error 事件，且助手回复不落库")
    void emitsErrorAndSkipsPersistence() {
        modelFailsAfter("半截", new RuntimeException("模型挂了"));

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, MODEL_MESSAGES, false, "你好"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new ChatEvent.Delta("半截"));
        assertThat(events.get(1)).isInstanceOf(ChatEvent.Failed.class);
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("模型一个字都没返回：发 error 事件，不落库空消息")
    void emitsErrorWhenModelReturnsNothing() {
        modelEmits();

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, MODEL_MESSAGES, false, "你好"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ChatEvent.Failed.class);
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("落库后的助手消息会刷新会话活跃时间")
    void refreshesConversationActivityTime() {
        modelEmits("好");
        assistantInsertReturns(47L);

        collect(new ChatContext(1L, 7L, MODEL_MESSAGES, false, "你好"));

        verify(conversationMapper).touch(1L, 7L);
    }

    // ---------------------------------------------------------------- 附件与多模态

    /** 让 prepare 能顺利走到「落用户消息」那一步，并让新消息拿到 id */
    private void prepareSucceeds(long userMessageId, List<Message> history) {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(conversationService.requireOwned(CONVERSATION_ID)).thenReturn(conversation("新对话"));
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(history));
        when(messageMapper.insert(any(Message.class))).thenAnswer(invocation -> {
            ((Message) invocation.getArgument(0)).setId(userMessageId);
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
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setMimeType(mimeType);
        attachment.setFileSize(size);
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

        ChatContext context = service.prepare(CONVERSATION_ID, "这是什么", List.of(100L));

        verify(attachmentService).bindToMessage(List.of(100L), 9L, CONVERSATION_ID);
        // 系统提示词 + 本轮提问
        UserMessage sent = (UserMessage) context.modelMessages().get(1);
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

        ChatContext context = service.prepare(CONVERSATION_ID, "   ", List.of(101L));

        UserMessage sent = (UserMessage) context.modelMessages().get(1);
        // 文字为空时不塞空 TextContent，只留媒体本身。
        // 非图片的媒体走 VideoContent 分支（音频已被移出白名单，见 AttachmentTypePolicy）
        assertThat(sent.contents()).hasSize(1);
        assertThat(sent.contents().get(0)).isInstanceOf(VideoContent.class);
        assertThat(context.titleSource()).contains("附件-101");
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

        ChatContext context = service.prepare(CONVERSATION_ID, "还在吗", List.of(102L));

        UserMessage sent = (UserMessage) context.modelMessages().get(1);
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

        ChatContext context = service.prepareRegenerate(CONVERSATION_ID);

        UserMessage sent = (UserMessage) context.modelMessages().get(1);
        assertThat(sent.contents()).hasSize(1);
        assertThat(sent.contents().get(0)).isInstanceOf(ImageContent.class);
    }
}
