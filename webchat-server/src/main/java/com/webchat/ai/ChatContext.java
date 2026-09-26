package com.webchat.ai;

import com.webchat.entity.Attachment;
import com.webchat.entity.Message;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 流式生成所需的全部输入，在同步阶段（{@link ChatStreamService#prepare}）组装完毕。
 *
 * <p>把「准备」与「流式」拆开的目的是：准备阶段的任何失败都还发生在响应提交之前，
 * 可以被全局异常处理转成普通的 JSON 错误响应；一旦开始流式输出，就只能走 error 事件了。
 *
 * <p><b>本轮提问刻意不在 {@code modelMessages} 里</b>：文档检索是异步阶段才做的，检索到的片段要作为
 * 本轮提问的第一段文本注入——所以要等检索结果出来，再调
 * {@code ChatStreamService} 里的组装方法把它拼成完整的消息列表。历史消息不受影响，
 * 它们每一轮都是现场从库里重新渲染的。
 *
 * @param conversationId          会话 ID
 * @param userId                  所属用户，用于刷新会话活跃时间时做归属校验
 * @param modelMessages           真正发给模型的固定部分（系统提示词 + 截断后的历史）
 * @param currentMessage          本轮用户消息（实体），附件与检索片段都由它派生
 * @param currentAttachments      本轮附件，可能为空
 * @param retrievalQuery          用于检索的提问文本；只带附件没打字时为空串，此时不检索
 * @param retrievableAttachmentIds 本会话内可检索的文本附件 id，检索时按它过滤向量
 * @param titleNeeded             是否需要在这轮之后生成会话标题（新会话的首条消息）
 * @param titleSource             用于生成标题的用户提问原文；仅在 {@code titleNeeded} 为真时有意义
 */
public record ChatContext(
        Long conversationId,
        Long userId,
        List<ChatMessage> modelMessages,
        Message currentMessage,
        List<Attachment> currentAttachments,
        String retrievalQuery,
        List<Long> retrievableAttachmentIds,
        boolean titleNeeded,
        String titleSource) {
}
