package com.webchat.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话里的一条消息。
 *
 * <p>{@code attachments} 只在用户消息上可能非空。它是「刷新后附件还能看见」的唯一来源——
 * 少了它，只发附件的那条消息重新拉取时就只剩一个带内边距的空气泡。
 */
public record MessageVO(Long id, String role, String content, LocalDateTime createTime,
                        List<AttachmentVO> attachments) {
}
