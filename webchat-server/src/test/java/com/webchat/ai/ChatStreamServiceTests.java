package com.webchat.ai;

import com.webchat.config.CurrentUserProvider;
import com.webchat.entity.Message;
import com.webchat.mapper.ConversationMapper;
import com.webchat.mapper.MessageMapper;
import com.webchat.service.ConversationService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式事件编排的单元测试。
 *
 * <p>用假的 {@link StreamingChatModel} 驱动整个 Flux，覆盖三处最容易出错、又最难在手工联调中发现的约定：
 * 事件顺序、失败时不落库、标题失败不能污染已成功的回复。全程不联网、不消耗 API 额度。
 */
@ExtendWith(MockitoExtension.class)
class ChatStreamServiceTests {

    @Mock
    private StreamingChatModel streamingChatModel;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ConversationService conversationService;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private TitleGenerator titleGenerator;

    /**
     * 模型输入不能为空——LangChain4j 的 ChatRequest 会直接拒绝空消息列表。
     * 生产代码里 buildContext 至少会拼上系统提示词与本轮提问，这里照做。
     */
    private static final List<ChatMessage> MODEL_MESSAGES = List.of(UserMessage.from("你好"));

    private ChatStreamService service;

    @BeforeEach
    void setUp() {
        service = new ChatStreamService(streamingChatModel, conversationMapper, messageMapper,
                conversationService, currentUserProvider, titleGenerator);
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
}
