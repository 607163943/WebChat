package com.webchat.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项。刻意不含消息正文——列表接口一次性全量返回，控制体积。
 */
public record ConversationVO(Long id, String title, LocalDateTime updateTime) {
}
