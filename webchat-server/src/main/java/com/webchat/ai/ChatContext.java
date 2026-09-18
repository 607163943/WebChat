package com.webchat.ai;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 流式生成所需的全部输入，在同步阶段（{@link ChatStreamService#prepare}）组装完毕。
 *
 * <p>把「准备」与「流式」拆开的目的是：准备阶段的任何失败都还发生在响应提交之前，
 * 可以被全局异常处理转成普通的 JSON 错误响应；一旦开始流式输出，就只能走 error 事件了。
 *
 * @param conversationId 会话 ID
 * @param userId         所属用户，用于刷新会话活跃时间时做归属校验
 * @param modelMessages  真正发给模型的消息列表（系统提示词 + 截断后的历史 + 本轮提问）
 * @param titleNeeded    是否需要在这轮之后生成会话标题（新会话的首条消息）
 * @param titleSource    用于生成标题的用户提问原文；仅在 {@code titleNeeded} 为真时有意义
 */
public record ChatContext(
        Long conversationId,
        Long userId,
        List<ChatMessage> modelMessages,
        boolean titleNeeded,
        String titleSource) {
}
