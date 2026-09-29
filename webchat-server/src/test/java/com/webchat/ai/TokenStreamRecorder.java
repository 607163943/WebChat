package com.webchat.ai;

import dev.langchain4j.service.TokenStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用：把一条 {@link TokenStream} 跑完，并记下它吐出来的增量与调过的工具。
 *
 * <p>{@code TokenStream} 是「自己接线、自己 start」的：框架要求 {@code onError} 与
 * {@code ignoreErrors} 二选一，不接就根本起不来。每个用例都抄一遍这几行没有意义，
 * 收在这里——顺带把「等它结束」的闩也管了。
 *
 * <p>{@link ChatStreamService} 在线上做的是同一件事（接 partial / tool / complete / error 四个回调），
 * 所以这里接出来的结果与那边看到的一致。
 */
final class TokenStreamRecorder {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final List<String> tokens = Collections.synchronizedList(new ArrayList<>());
    private final List<String> tools = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    private TokenStreamRecorder() {
    }

    /** 接好线并启动 */
    static TokenStreamRecorder start(TokenStream stream) {
        TokenStreamRecorder recorder = new TokenStreamRecorder();
        stream.onPartialResponse(recorder.tokens::add)
                .beforeToolExecution(execution -> recorder.tools.add(execution.request().name()))
                .onCompleteResponse(response -> recorder.finished.countDown())
                .onError(failure -> {
                    recorder.error.set(failure);
                    recorder.finished.countDown();
                })
                .start();
        return recorder;
    }

    /**
     * 等它结束并返回累计的增量。
     *
     * <p>中途报错也照样返回已经拿到的那些——「出错前吐了几个字」本身就是有些用例要断言的东西，
     * 收场是成功还是失败由 {@link #errorAfter()} 单独说。
     */
    List<String> tokens() {
        await();
        return List.copyOf(tokens);
    }

    /** 等它结束并返回失败原因；正常结束返回 null */
    Throwable errorAfter() {
        await();
        return error.get();
    }

    /** 这一轮里模型调用过的工具名，按调用顺序 */
    List<String> toolsCalled() {
        return List.copyOf(tools);
    }

    private void await() {
        try {
            if (!finished.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new AssertionError("等了 " + TIMEOUT + " 还没结束");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", e);
        }
    }
}
