package com.webchat.ai;

import dev.langchain4j.data.message.Content;

import java.util.List;

/**
 * 流式生成所需的全部输入，在同步阶段（{@link ChatStreamService#prepare}）组装完毕。
 *
 * <p>把「准备」与「流式」拆开的目的是：准备阶段的任何失败都还发生在响应提交之前，
 * 可以被全局异常处理转成普通的 JSON 错误响应；一旦开始流式输出，就只能走 error 事件了。
 *
 * <p><b>历史不在这里</b>：它由 prepare 直接灌进该会话的记忆槽位（见
 * {@link ChatStreamService} 的 buildContext），调用 AIService 时由框架自己取出、并在尾部补上本轮提问。
 * 这里只带「本轮独有的东西」。
 *
 * <p><b>本轮提问的内容已经组装好了</b>，唯独检索到的资料不在其中：检索要调 embedding、只能在流式阶段做，
 * 拿到之后再拼到最前面（见 {@link ChatStreamService#stream}）。
 *
 * @param conversationId           会话 ID，同时是记忆的槽位号
 * @param userId                   所属用户，用于刷新会话活跃时间时做归属校验
 * @param currentContents          本轮提问的内容（正文 + 文本附件说明 + 媒体），顺序见
 *                                 {@link ChatMessageAssembler#renderQuestion}
 * @param retrievalQuery           用于检索的提问文本；只带附件没打字时为空串，此时不检索
 * @param retrievableAttachmentIds 本会话内可检索的文本附件 ID，检索时按它过滤向量
 * @param titleNeeded              是否需要在这轮之后生成会话标题（新会话的首条消息）
 * @param titleSource              用于生成标题的用户提问原文；仅在 {@code titleNeeded} 为真时有意义
 * @param existingReplyId          本轮要覆盖的既有助手回复 ID，只有「重新生成」会带上；为 null 表示新写一条
 */
public record ChatContext(
        Long conversationId,
        Long userId,
        List<Content> currentContents,
        String retrievalQuery,
        List<Long> retrievableAttachmentIds,
        boolean titleNeeded,
        String titleSource,
        Long existingReplyId) {
}
