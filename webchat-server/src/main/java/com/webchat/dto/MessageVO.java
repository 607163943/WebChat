package com.webchat.dto;

import java.time.LocalDateTime;

public record MessageVO(Long id, String role, String content, LocalDateTime createTime) {
}
