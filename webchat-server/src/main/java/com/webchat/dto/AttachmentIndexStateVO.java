package com.webchat.dto;

/**
 * 附件索引状态的最小投影，只给轮询用。
 *
 * <p>刻意不复用 {@link AttachmentVO}：轮询每隔一两秒来一次，而那个结构要回显文件名与 url，
 * 每轮都得为几个 id 多查一遍库、多传一份前端已有的数据。状态是这里唯一会变的东西。
 */
public record AttachmentIndexStateVO(Long id, String indexState) {
}
