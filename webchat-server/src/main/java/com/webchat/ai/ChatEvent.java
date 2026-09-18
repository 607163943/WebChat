package com.webchat.ai;

/**
 * 一次流式回复过程中向后端推送的事件，与手写的 SSE 事件协议一一对应。
 *
 * <p>事件顺序：若干 {@link Delta} → {@link Done} →（仅新会话）{@link Title}；
 * 生成失败则以 {@link Failed} 结束且本次回复不落库。
 *
 * <p>命名上刻意避开 {@code Error}，以免与 {@link java.lang.Error} 混淆——它对外的 SSE 事件名仍是 {@code error}。
 */
public sealed interface ChatEvent {

    /** 增量片段 → SSE 事件名 delta */
    record Delta(String content) implements ChatEvent {
    }

    /** 生成正常结束，携带落库后的助手消息 ID → SSE 事件名 done */
    record Done(Long messageId) implements ChatEvent {
    }

    /** 新会话标题生成完成 → SSE 事件名 title */
    record Title(String title) implements ChatEvent {
    }

    /** 生成中途失败，本次回复不落库 → SSE 事件名 error */
    record Failed(String message) implements ChatEvent {
    }
}
