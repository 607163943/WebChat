package com.webchat.ai;

/**
 * 一次流式回复过程中向后端推送的事件，与手写的 SSE 事件协议一一对应。
 *
 * <p>事件顺序：若干（{@link Searching} 与 {@link Delta} 交错）→ {@link Done} →（仅新会话）
 * {@link Title}；生成失败则以 {@link Failed} 结束——此时出错前已生成的部分<b>照样落库</b>
 * （状态为 {@code failed}），只是这一轮没有 {@code done} 事件可发。
 *
 * <p>命名上刻意避开 {@code Error}，以免与 {@link java.lang.Error} 混淆——它对外的 SSE 事件名仍是 {@code error}。
 */
public sealed interface ChatEvent {

    /** 增量片段 → SSE 事件名 delta */
    record Delta(String content) implements ChatEvent {
    }

    /**
     * 模型发起了一次工具调用，服务端正在执行 → SSE 事件名 search。
     *
     * <p>存在的理由是「工具执行期间模型不吐字」：一次网页搜索要几秒，若没有这条事件，前端在这几秒里
     * 与卡死无异。发的是<b>被调用的工具名</b>而不是给用户看的文案——用户看到的中文属于前端
     * （同一个工具叫什么名字、要不要提示，都是界面的事），后端只管如实上报「正在跑哪个工具」。
     *
     * <p>一轮里可能来好几条（先搜、再打开某个网页），每条都意味着后面还会接着吐字。
     */
    record Searching(String tool) implements ChatEvent {
    }

    /** 生成正常结束，携带落库后的助手消息 ID → SSE 事件名 done */
    record Done(Long messageId) implements ChatEvent {
    }

    /** 新会话标题生成完成 → SSE 事件名 title */
    record Title(String title) implements ChatEvent {
    }

    /**
     * 生成中途失败 → SSE 事件名 error。
     *
     * <p>已生成的部分已经落库，但事件里不带它的消息 ID：前端本地那条半截用一个负数 id 就够了
     * （与乐观插入的用户消息同一套路），真实 id 在下次拉取消息时补齐。
     */
    record Failed(String message) implements ChatEvent {
    }
}
