package com.webchat.dto;

import java.util.List;

/**
 * 发送消息的请求体。
 *
 * @param content       消息正文。允许为空——只发附件不打字是常见用法
 * @param attachmentIds 随本条消息提交的附件 ID，来自上传接口的返回值。可为空
 */
public record SendMessageRequest(String content, List<Long> attachmentIds) {
}
