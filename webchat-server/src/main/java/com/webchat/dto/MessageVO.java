package com.webchat.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话里的一条消息。
 *
 * <p>{@code attachments} 只在用户消息上可能非空。它是「刷新后附件还能看见」的唯一来源——
 * 少了它，只发附件的那条消息重新拉取时就只剩一个带内边距的空气泡。
 *
 * <p>{@code status} 说明这条助手回复是怎么收场的（用户消息恒为 completed）。生成中断或失败时
 * 半截正文照样在库里，靠它前端才能把「没写完」标出来。
 */
public record MessageVO(Long id, String role, String content, String status,
                        LocalDateTime createTime, List<AttachmentVO> attachments) {
}
