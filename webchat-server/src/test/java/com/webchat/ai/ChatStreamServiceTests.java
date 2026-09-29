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
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式对话编排的单元测试。
 *
 * <p>用假的 {@link FakeStreamingChatModel} 驱动整个 Flux——AIService 那一层是真的（记忆、消息组装
 * 都走框架自己的实现），覆盖几处最容易出错、又最难在手工联调中发现的约定：事件顺序、失败时不落库、
 * 标题失败不能污染已成功的回复，以及附件如何变成多模态消息。全程不联网、不消耗 API 额度、不碰数据库。
 */
@ExtendWith(MockitoExtension.class)
class ChatStreamServiceTests {

    private static final long USER_ID = 7L;
    private static final long CONVERSATION_ID = 1L;
    /** 落库时回填给助手消息的 id，与用户消息那个分开，便于断言 done 事件带的是哪一个 */
    private static final long ASSISTANT_MESSAGE_ID = 50L;
    /** 重新生成时被覆盖的那条旧回复的 id */
    private static final long EXISTING_REPLY_ID = 30L;

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
            5, 0.7, 1200, 200, 1000, 10000, Duration.ofSeconds(3));

    private final FakeStreamingChatModel model = new FakeStreamingChatModel();
    private final ConversationMemoryStore memoryStore = new ConversationMemoryStore();
    private ChatMessageAssembler messageAssembler;
    private ChatStreamService service;

    @BeforeEach
    void setUp() {
        // 与 AssistantConfig 用同一处装配：记忆与消息组装都走真的框架实现，测试才说明得了问题。
        // 装配写在 setUp 里而不是字段初始化里——@Mock 的字段要到这时才被注入
        messageAssembler = new ChatMessageAssembler(attachmentService, attachmentProperties);
        // 第三个参数是工具提供者：null 即「这一轮模型手上没有工具」，多数用例与联网搜索无关
        installService(new AssistantConfig().chatAssistant(model, memoryStore, null));
        // 检索默认返回「没有资料」：多数用例测的是事件编排，与 RAG 无关。
        // 必须显式 stub —— mock 默认返回 null，而 Mono.fromCallable 拿到 null 会变成空流，
        // 表现是一次 delta 都收不到，排查起来会莫名其妙
        lenient().when(documentIndexService.knowledgeFor(any(), any())).thenReturn("");
    }

    /** 用给定的助手重装一遍 service：联网搜索那条用例需要一个手上有工具的助手 */
    private void installService(ChatAssistant assistant) {
        service = new ChatStreamService(assistant, memoryStore, messageAssembler, conversationMapper,
                messageMapper, conversationService, attachmentService, currentUserProvider,
                titleGenerator, documentIndexService, ragProperties);
    }

    /** 装配一个「模型手上有一个联网搜索工具」的助手，用来跑搜索事件那条用例 */
    private void installAssistantWithSearchTool() {
        ToolSpecification specification = ToolSpecification.builder()
                .name("web_search_exa")
                .description("联网搜索")
                .build();
        installService(new AssistantConfig().chatAssistant(model, memoryStore,
                request -> new ToolProviderResult(Map.of(specification, (request1, memoryId) -> "搜索结果"))));
    }

    /** 落库时回填自增主键，模拟 MyBatis-Plus 的行为 */
    private void assistantInsertReturns(long id) {
        when(messageMapper.insert(any(Message.class))).thenAnswer(invocation -> {
            ((Message) invocation.getArgument(0)).setId(id);
            return 1;
        });
    }

    /** 同上，另外在真的写库那一下放行一个闩——取消路径的落库是异步的，测试只能等它 */
    private void assistantInsertReturns(long id, CountDownLatch saved) {
        when(messageMapper.insert(any(Message.class))).thenAnswer(invocation -> {
            ((Message) invocation.getArgument(0)).setId(id);
            saved.countDown();
            return 1;
        });
    }

    /** 全轮唯一一次落库的那条助手消息——顺带钉住「只写一次」 */
    private Message singleSavedReply() {
        org.mockito.ArgumentCaptor<Message> captor =
                org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 手工构造 context 的用例跳过了 prepare，系统提示词得自己补进记忆——否则请求里根本没有它 */
    private void seedSystemPrompt() {
        memoryStore.seed(CONVERSATION_ID, List.of(SystemMessage.from(Prompt.SYSTEM_PROMPT)));
    }

    private List<ChatEvent> collect(ChatContext context) {
        return service.stream(context).collectList().block(Duration.ofSeconds(5));
    }

    /** 真正发给模型的消息列表 */
    private List<ChatMessage> sentMessages() {
        return model.sentMessages();
    }

    /** 发出去的最后一条就是本轮提问 */
    private static UserMessage lastUserMessageOf(List<ChatMessage> messages) {
        return (UserMessage) messages.get(messages.size() - 1);
    }

    /** 造一个「事件编排」用例用的 context：没有附件、不检索、不覆盖既有回复 */
    private static ChatContext contextOf(boolean titleNeeded, String titleSource) {
        return new ChatContext(1L, 7L, List.of(TextContent.from("你好")), "你好", List.of(),
                titleNeeded, titleSource, null);
    }

    /** 造一个「重新生成」用例用的 context：本轮要覆盖 id 为 30 的那条旧回复 */
    private static ChatContext regenerateContextOf() {
        return new ChatContext(1L, 7L, List.of(TextContent.from("你好")), "你好", List.of(),
                false, "你好", EXISTING_REPLY_ID);
    }

    private static Message userMessage(String content) {
        return messageRow(8L, "user", content);
    }

    /** 库里已有的一行 */
    private static Message messageRow(long id, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setConversationId(CONVERSATION_ID);
        message.setRole(role);
        message.setContent(content);
        message.setStatus(Message.STATUS_COMPLETED);
        return message;
    }

    // ---------------------------------------------------------------- 事件编排

    @Test
    @DisplayName("新会话：若干 delta → done → title，顺序由结构保证")
    void emitsDeltaThenDoneThenTitle() {
        model.emits("你", "好");
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
    @DisplayName("模型调用联网搜索：searching 插在 delta 之前，工具名原样上报")
    void emitsSearchingEventWhileAToolRuns() {
        installAssistantWithSearchTool();
        model.callsToolThenEmits("web_search_exa", "{\"query\":\"今天天气\"}", "晴");
        assistantInsertReturns(42L);
        seedSystemPrompt();

        List<ChatEvent> events = collect(contextOf(false, "你好"));

        // 「先搜再答」那一轮：模型一个字都没说就先点了工具，搜索期间唯一的动静就是这条事件。
        // 工具名照原样发出去——翻译成「正在联网搜索…」是前端的事，后端不管界面文案
        assertThat(events).containsExactly(
                new ChatEvent.Searching("web_search_exa"),
                new ChatEvent.Delta("晴"),
                new ChatEvent.Done(42L));
    }

    @Test
    @DisplayName("没有工具时不会冒出 searching：事件流与从前一样")
    void emitsNoSearchingEventWithoutTools() {
        model.emits("你", "好");
        assistantInsertReturns(42L);
        seedSystemPrompt();

        assertThat(collect(contextOf(false, "你好"))).containsExactly(
                new ChatEvent.Delta("你"),
                new ChatEvent.Delta("好"),
                new ChatEvent.Done(42L));
    }

    @Test
    @DisplayName("非首条消息不发 title 事件，也不去调模型生成标题")
    void skipsTitleWhenNotNeeded() {
        model.emits("好");
        assistantInsertReturns(43L);

        List<ChatEvent> events = collect(contextOf(false, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(43L));
        verify(titleGenerator, never()).generate(anyString());
    }

    @Test
    @DisplayName("标题为空时不发 title 事件")
    void skipsTitleEventWhenTitleIsEmpty() {
        model.emits("好");
        assistantInsertReturns(44L);
        when(titleGenerator.generate(anyString())).thenReturn(Optional.empty());

        List<ChatEvent> events = collect(contextOf(true, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(44L));
    }

    @Test
    @DisplayName("标题生成抛异常时，已落库的回复仍然是 done 而不是 error")
    void titleFailureDoesNotTurnSuccessIntoError() {
        model.emits("好");
        assistantInsertReturns(45L);
        when(titleGenerator.generate(anyString())).thenThrow(new RuntimeException("标题服务不可用"));

        List<ChatEvent> events = collect(contextOf(true, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(45L));
        assertThat(events).noneMatch(ChatEvent.Failed.class::isInstance);
    }

    @Test
    @DisplayName("写入标题失败同样不影响 done")
    void updateTitleFailureDoesNotBreakStream() {
        model.emits("好");
        assistantInsertReturns(46L);
        when(titleGenerator.generate(anyString())).thenReturn(Optional.of("问候"));
        doThrow(new RuntimeException("库挂了")).when(conversationMapper)
                .updateTitle(anyLong(), anyLong(), anyString());

        List<ChatEvent> events = collect(contextOf(true, "你好"));

        assertThat(events).containsExactly(new ChatEvent.Delta("好"), new ChatEvent.Done(46L));
    }

    @Test
    @DisplayName("模型中途报错：发 error 事件，但出错前已生成的部分以 failed 落库")
    void keepsPartialReplyWhenModelFails() {
        model.failsAfter("半截", new RuntimeException("模型挂了"));

        List<ChatEvent> events = collect(contextOf(false, "你好"));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new ChatEvent.Delta("半截"));
        assertThat(events.get(1)).isInstanceOf(ChatEvent.Failed.class);
        // 用户已经看见这半句了，丢掉它等于刷新后内容凭空消失
        assertThat(singleSavedReply().getContent()).isEqualTo("半截");
        assertThat(singleSavedReply().getStatus()).isEqualTo(Message.STATUS_FAILED);
    }

    @Test
    @DisplayName("模型一个字都没返回：发 error 事件，不落库空消息")
    void emitsErrorWhenModelReturnsNothing() {
        model.emits();

        List<ChatEvent> events = collect(contextOf(false, "你好"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ChatEvent.Failed.class);
        verify(messageMapper, never()).insert(any(Message.class));
        verify(messageMapper, never()).updateReply(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("正常结束：回复以 completed 落库")
    void savesCompletedStatusOnNormalCompletion() {
        model.emits("好");
        assistantInsertReturns(48L);

        collect(contextOf(false, "你好"));

        assertThat(singleSavedReply().getStatus()).isEqualTo(Message.STATUS_COMPLETED);
    }

    @Test
    @DisplayName("落库后的助手消息会刷新会话活跃时间")
    void refreshesConversationActivityTime() {
        model.emits("好");
        assistantInsertReturns(47L);

        collect(contextOf(false, "你好"));

        verify(conversationMapper).touch(1L, 7L);
    }

    // ---------------------------------------------------------------- 重新生成

    @Test
    @DisplayName("重新生成：原地覆盖那条旧回复，既不删也不新插")
    void regenerateOverwritesExistingReply() {
        model.emits("新答案");
        when(messageMapper.updateReply(EXISTING_REPLY_ID, CONVERSATION_ID, "新答案",
                Message.STATUS_COMPLETED)).thenReturn(1);

        List<ChatEvent> events = collect(regenerateContextOf());

        assertThat(events).containsExactly(new ChatEvent.Delta("新答案"),
                new ChatEvent.Done(EXISTING_REPLY_ID));
        verify(messageMapper).updateReply(EXISTING_REPLY_ID, CONVERSATION_ID, "新答案",
                Message.STATUS_COMPLETED);
        verify(messageMapper, never()).insert(any(Message.class));
        verify(messageMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("重新生成中途报错：半截内容照样覆盖上去，状态是 failed")
    void regenerateMarksFailureOnExistingReply() {
        model.failsAfter("半截", new RuntimeException("模型挂了"));
        when(messageMapper.updateReply(EXISTING_REPLY_ID, CONVERSATION_ID, "半截",
                Message.STATUS_FAILED)).thenReturn(1);

        collect(regenerateContextOf());

        verify(messageMapper).updateReply(EXISTING_REPLY_ID, CONVERSATION_ID, "半截",
                Message.STATUS_FAILED);
        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    @DisplayName("重新生成一个字都没产出：旧回复原样不动")
    void regenerateLeavesExistingReplyWhenNothingGenerated() {
        model.emits();

        collect(regenerateContextOf());

        verify(messageMapper, never()).updateReply(anyLong(), anyLong(), anyString(), anyString());
        verify(messageMapper, never()).insert(any(Message.class));
    }

    // ---------------------------------------------------------------- 客户端断开

    @Test
    @DisplayName("客户端断开：把断开那一刻已生成的部分以 interrupted 落库")
    void keepsPartialReplyWhenClientDisconnects() throws Exception {
        CountDownLatch modelCalled = new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.StreamingChatResponseHandler> handler =
                model.handsOverHandler(modelCalled);
        CountDownLatch saved = new CountDownLatch(1);
        assistantInsertReturns(60L, saved);

        Disposable subscription = service.stream(contextOf(false, "你好")).subscribe();
        // 先等到 chat 真的被调过：取消回调要等订阅建立之后才挂得上
        assertThat(modelCalled.await(5, TimeUnit.SECONDS)).isTrue();
        handler.get().onPartialResponse("半截");
        subscription.dispose();
        // 取消（以及随后的落库）跑在弹性池上，别假设 dispose() 返回时就写完了
        assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();

        Message reply = singleSavedReply();
        assertThat(reply.getContent()).isEqualTo("半截");
        assertThat(reply.getStatus()).isEqualTo(Message.STATUS_INTERRUPTED);
    }

    @Test
    @DisplayName("断开之后模型才走到完成：这一轮不会再被写第二遍")
    void doesNotWriteTwiceWhenModelCompletesAfterDisconnect() throws Exception {
        CountDownLatch modelCalled = new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.StreamingChatResponseHandler> handler =
                model.handsOverHandler(modelCalled);
        CountDownLatch saved = new CountDownLatch(1);
        assistantInsertReturns(61L, saved);

        Disposable subscription = service.stream(contextOf(false, "你好")).subscribe();
        assertThat(modelCalled.await(5, TimeUnit.SECONDS)).isTrue();
        handler.get().onPartialResponse("半截");
        subscription.dispose();
        assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
        // 这条假模型走的是不带上下文的重载，也就是「不支持取消」那条路；真实适配器即使能被掐断，
        // 也仍存在掐断与完成回调擦身而过的可能。这次晚到的调用必须被吞掉：
        // singleSavedReply() 断言全轮只 insert 过一次，重复写会让它失败
        handler.get().onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                .aiMessage(AiMessage.from("半截"))
                .build());

        assertThat(singleSavedReply().getStatus()).isEqualTo(Message.STATUS_INTERRUPTED);
        verify(messageMapper, never()).updateReply(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("断开时把模型那边的生成也掐掉：不只是停止投递")
    void cancelsTheModelGenerationWhenClientDisconnects() throws Exception {
        RecordingStreamingHandle handle = new RecordingStreamingHandle();
        CountDownLatch modelCalled = new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.StreamingChatResponseHandler> handler =
                model.handsOverHandler(modelCalled);
        CountDownLatch saved = new CountDownLatch(1);
        assistantInsertReturns(62L, saved);

        Disposable subscription = service.stream(contextOf(false, "你好")).subscribe();
        assertThat(modelCalled.await(5, TimeUnit.SECONDS)).isTrue();
        // 真实适配器走的是带上下文的重载（DashScope 调的就是它），把手就在上下文里
        handler.get().onPartialResponse(new PartialResponse("半截"),
                new PartialResponseContext(handle));
        subscription.dispose();
        assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();

        // 没有这一步的话，用户那边看着像停了，模型其实在后台把整段话生成完，额度照扣
        assertThat(handle.cancelCount()).isEqualTo(1);
        assertThat(handle.isCancelled()).isTrue();
        assertThat(singleSavedReply().getStatus()).isEqualTo(Message.STATUS_INTERRUPTED);
    }

    @Test
    @DisplayName("模型还没吐字就断开：把手一到手就补上那一刀")
    void cancelsTheModelWhenDisconnectHappensBeforeAnyToken() throws Exception {
        RecordingStreamingHandle handle = new RecordingStreamingHandle();
        CountDownLatch modelCalled = new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.StreamingChatResponseHandler> handler =
                model.handsOverHandler(modelCalled);

        Disposable subscription = service.stream(contextOf(false, "你好")).subscribe();
        assertThat(modelCalled.await(5, TimeUnit.SECONDS)).isTrue();
        // 一个增量都还没有：此时候手还没到手，断开只能先记下「已取消」
        subscription.dispose();

        // 模型随后才吐出第一个增量——这也正是「模型刚开个头就被停掉」那种情形
        handler.get().onPartialResponse(new PartialResponse("半截"),
                new PartialResponseContext(handle));

        assertThat(handle.cancelCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("模型先点工具、还没吐字时断开：靠工具调用那条回调提前拿到的把手也能掐断")
    void cancelsThroughTheToolCallHandleBeforeAnyToken() throws Exception {
        RecordingStreamingHandle handle = new RecordingStreamingHandle();
        CountDownLatch modelCalled = new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.StreamingChatResponseHandler> handler =
                model.handsOverHandler(modelCalled);
        installAssistantWithSearchTool();

        Disposable subscription = service.stream(contextOf(false, "你好")).subscribe();
        assertThat(modelCalled.await(5, TimeUnit.SECONDS)).isTrue();
        // 「先搜再答」那一轮的顺序：模型只点了个工具，一个字都还没说，第一个文本增量要等搜索结束才来
        subscription.dispose();

        handler.get().onPartialToolCall(PartialToolCall.builder()
                        .index(0)
                        .id("call-1")
                        .name("web_search_exa")
                        .partialArguments("{\"query\":\"今天天气\"}")
                        .build(),
                new PartialToolCallContext(handle));

        // 只认 onPartialResponseWithContext 的话，这一刀要等到搜索跑完才落得下去——
        // 而搜索本身要几秒，用户点的「停止」在搜索期间等于没生效
        assertThat(handle.cancelCount()).isEqualTo(1);
    }

    /** 可观测的把手：真实适配器把它藏在 {@code PartialResponseContext} 里递给框架 */
    private static final class RecordingStreamingHandle implements StreamingHandle {

        private final AtomicInteger cancels = new AtomicInteger();
        private volatile boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
            cancels.incrementAndGet();
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        int cancelCount() {
            return cancels.get();
        }
    }

    // ---------------------------------------------------------------- 记忆

    @Test
    @DisplayName("记忆每轮重建：系统提示词在最前，接着是库里的历史，本轮提问在最后")
    void seedsMemoryFromTheDatabaseOnEveryTurn() {
        prepareSucceeds(9L, List.of(messageRow(5L, "user", "上一问"), messageRow(6L, "assistant", "上一答")));
        model.emits("好");

        collect(service.prepare(CONVERSATION_ID, "这一问", List.of()));

        List<ChatMessage> sent = sentMessages();
        assertThat(sent).containsExactly(
                SystemMessage.from(Prompt.SYSTEM_PROMPT),
                UserMessage.from("上一问"),
                AiMessage.from("上一答"),
                UserMessage.from("这一问"));
    }

    @Test
    @DisplayName("重新生成时，历史只到那条提问之前，提问本身作为本轮提问再发一次")
    void regenerateRebuildsMemoryWithoutTheTrailingReply() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(conversationService.requireOwned(CONVERSATION_ID)).thenReturn(conversation("已命名"));
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>(List.of(
                messageRow(7L, "user", "第二问"), messageRow(8L, "assistant", "要覆盖的答"))));
        when(attachmentService.findByMessageId(7L)).thenReturn(List.of());
        model.emits("新答案");
        when(messageMapper.updateReply(8L, CONVERSATION_ID, "新答案", Message.STATUS_COMPLETED))
                .thenReturn(1);

        collect(service.prepareRegenerate(CONVERSATION_ID));

        assertThat(sentMessages()).containsExactly(
                SystemMessage.from(Prompt.SYSTEM_PROMPT),
                UserMessage.from("第二问"));
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
        model.emits("好");

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
        model.emits("好");

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
        model.emits("好");

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
        model.emits("好");

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
        model.emits("好");

        collect(service.prepare(CONVERSATION_ID, "还在吗", List.of(102L)));

        UserMessage sent = lastUserMessageOf(sentMessages());
        assertThat(sent.contents()).hasSize(1);
        assertThat(sent.contents().get(0)).isEqualTo(TextContent.from("还在吗"));
    }

    @Test
    @DisplayName("一条附件都读不出来：prepare 直接 400，不会留下一条空消息")
    void rejectsQuestionWhoseAttachmentsAreAllUnreadable() {
        prepareSucceeds(9L, List.of());
        Attachment broken = attachment(106L, "image/png", 3);
        when(attachmentService.findByMessageId(9L)).thenReturn(List.of(broken));
        when(attachmentService.readContent(broken))
                .thenThrow(new com.webchat.storage.AttachmentStorageException("丢了", null));

        // 「空的 contents 会让 UserMessage 抛 IllegalArgumentException 变成 500」——
        // 这道守卫把它挪到了 prepare 里，用户拿到的是干净的 400，事务也会把用户消息回滚掉
        assertThatThrownBy(() -> service.prepare(CONVERSATION_ID, "   ", List.of(106L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("附件内容已不可用");
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
        model.emits("好");

        collect(service.prepareRegenerate(CONVERSATION_ID));

        UserMessage sent = lastUserMessageOf(sentMessages());
        assertThat(sent.contents()).hasSize(1);
        assertThat(sent.contents().get(0)).isInstanceOf(ImageContent.class);
    }

    // ---------------------------------------------------------------- 检索

    @Test
    @DisplayName("检索到的片段作为本轮提问的第一段文本注入，system 提示词保持常量")
    void injectsRetrievedKnowledgeIntoTheQuestion() {
        seedSystemPrompt();
        model.emits("好");
        when(documentIndexService.knowledgeFor("它讲了什么", List.of(104L)))
                .thenReturn("【片段 1｜来源：纪要.txt】\n季度目标");

        collect(new ChatContext(1L, 7L, List.of(TextContent.from("它讲了什么")), "它讲了什么",
                List.of(104L), false, "它讲了什么", null));

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
    @DisplayName("资料与媒体同处一条消息：资料在最前，媒体照旧")
    void keepsMediaNextToTheInjectedKnowledge() {
        seedSystemPrompt();
        model.emits("好");
        when(documentIndexService.knowledgeFor("它讲了什么", List.of(104L))).thenReturn("资料");

        collect(new ChatContext(1L, 7L,
                List.of(TextContent.from("它讲了什么"), ImageContent.from("AQID", "image/png")),
                "它讲了什么", List.of(104L), false, "它讲了什么", null));

        assertThat(lastUserMessageOf(sentMessages()).contents()).containsExactly(
                TextContent.from("资料"),
                TextContent.from("它讲了什么"),
                ImageContent.from("AQID", "image/png"));
    }

    @Test
    @DisplayName("检索范围取自会话内的文本附件，随 prepare 一并确定")
    void passesConversationDocumentsAsRetrievalScope() {
        prepareSucceeds(9L, List.of());
        when(attachmentService.listTextAttachmentIds(CONVERSATION_ID)).thenReturn(List.of(100L, 101L));
        model.emits("好");

        collect(service.prepare(CONVERSATION_ID, "总结一下", List.of()));

        verify(documentIndexService).knowledgeFor("总结一下", List.of(100L, 101L));
    }

    @Test
    @DisplayName("检索失败不能把一次对话变成失败：降级成「没有资料」继续")
    void keepsAnsweringWhenRetrievalFails() {
        model.emits("好");
        assistantInsertReturns(51L);
        when(documentIndexService.knowledgeFor(any(), any()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        List<ChatEvent> events = collect(new ChatContext(1L, 7L, List.of(TextContent.from("你好")),
                "你好", List.of(100L), false, "你好", null));

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
        model.emits("好");

        collect(service.prepare(CONVERSATION_ID, "都看看吧", List.of()));

        // 请求 = 系统提示词 + 历史 + 本轮提问，历史那条在第 2 位
        UserMessage historySent = (UserMessage) sentMessages().get(1);
        assertThat(historySent.contents())
                .as("媒体超预算被裁掉，文本附件的说明必须留下")
                .noneMatch(ImageContent.class::isInstance)
                .anyMatch(content -> content instanceof TextContent text
                        && text.text().contains("附件-301"));
    }

    @Test
    @DisplayName("历史里的媒体在预算内时照常带上：最新那批优先")
    void keepsHistoryMediaWithinBudget() {
        Message historyUser = userMessage("上一轮");
        historyUser.setId(5L);
        prepareSucceeds(9L, List.of(historyUser));
        Attachment historyImage = attachment(300L, "image/png", 1, 5L);
        when(attachmentService.findByMessageIds(List.of(5L))).thenReturn(List.of(historyImage));
        when(attachmentService.readContent(historyImage)).thenReturn(new byte[]{7});
        model.emits("好");

        collect(service.prepare(CONVERSATION_ID, "接着看", List.of()));

        UserMessage historySent = (UserMessage) sentMessages().get(1);
        assertThat(historySent.contents()).anyMatch(ImageContent.class::isInstance);
    }
}
