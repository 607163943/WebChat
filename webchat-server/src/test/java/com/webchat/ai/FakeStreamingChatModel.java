package com.webchat.ai;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.AsyncNotSupported;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 假的流式模型：记下收到的请求，并按测试安排好的剧本回应；不联网、不消耗额度。
 *
 * <p>与真实适配器（{@code QwenStreamingChatModel}）一样只实现<b>回调式</b>的
 * {@code doChat(ChatRequest, handler)}，事件式的那条（返回 {@code Flow.Publisher}）保持默认的
 * 「不支持」——这样跑通就说明框架确实能自己落回回调式，而框架真去走事件式时测试会直接报错，
 * 不会悄悄变成一次沉默的空回复。
 *
 * <p>剧本在 {@code doChat} 里同步执行：模型推增量是同一线程上的回调，
 * 与真实适配器的行为一致（真实实现是在 HTTP 客户端的线程上推）。
 */
class FakeStreamingChatModel implements StreamingChatModel {

    private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());

    /** 这次调用是怎么进来的：true 表示框架走了事件式那条我们没实现的路 */
    private volatile boolean calledViaEventApi;

    private volatile boolean called;

    private volatile Consumer<StreamingChatResponseHandler> script = handler -> {
    };

    /** 依次吐出这些片段后正常结束。ChatResponse 里带上累计正文——框架会把它追加进记忆 */
    void emits(String... tokens) {
        script = handler -> emit(handler, tokens);
    }

    /**
     * 第一次调用回一个工具调用请求，之后的调用（框架执行完工具、把结果带回来再问一次）按 tokens 吐字。
     *
     * <p>即真实的一轮「先搜再答」：第一次请求模型一个字都不说，只点了个工具。
     */
    void callsToolThenEmits(String toolName, String arguments, String... tokens) {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        script = handler -> {
            if (firstCall.getAndSet(false)) {
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.builder()
                                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                                        .id("call-1")
                                        .name(toolName)
                                        .arguments(arguments)
                                        .build()))
                                .build())
                        .build());
                return;
            }
            emit(handler, tokens);
        };
    }

    private static void emit(StreamingChatResponseHandler handler, String... tokens) {
        StringBuilder text = new StringBuilder();
        for (String token : tokens) {
            text.append(token);
            handler.onPartialResponse(token);
        }
        handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from(text.toString()))
                .build());
    }

    /** 吐一个片段后报错 */
    void failsAfter(String token, Throwable error) {
        script = handler -> {
            handler.onPartialResponse(token);
            handler.onError(error);
        };
    }

    /**
     * 只把 handler 交出来，什么时候吐字、什么时候断开都由测试线程决定。
     *
     * <p>真实的取消由容器察觉客户端断开后触发，测试里只能自己制造——先拿到 handler，
     * 手动推一个增量，再取消订阅。
     *
     * <p>推增量时用哪个重载由测试决定：走 {@code onPartialResponse(String)} 就是框架眼里
     * 「本模型不支持取消」的那条路，走带 {@code PartialResponseContext} 的那个则带着真的把手，
     * 可以把 {@code ChatStreamService} 的取消行为一并测掉。
     */
    AtomicReference<StreamingChatResponseHandler> handsOverHandler(CountDownLatch called) {
        AtomicReference<StreamingChatResponseHandler> handler = new AtomicReference<>();
        script = h -> {
            handler.set(h);
            called.countDown();
        };
        return handler;
    }

    /** 真正发出去的消息列表；一次都没调用过时断言失败并给出线索 */
    List<ChatMessage> sentMessages() {
        if (requests.isEmpty()) {
            throw new AssertionError("模型一次都没被调用"
                    + (calledViaEventApi ? "（框架走的是事件式 API，而本假模型没实现那条）" : ""));
        }
        return requests.get(requests.size() - 1).messages();
    }

    boolean wasCalled() {
        return called;
    }

    void reset() {
        requests.clear();
        called = false;
        calledViaEventApi = false;
        script = handler -> {
        };
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        called = true;
        requests.add(request);
        script.accept(handler);
    }

    @Override
    public Flow.Publisher<ChatModelStreamingEvent> doChat(ChatRequest request) {
        calledViaEventApi = true;
        return AsyncNotSupported.failingPublisher(getClass(), "测试用的假模型只实现回调式 API");
    }
}
