package com.webchat.storage;

/**
 * 存储层失败。刻意不直接用 {@code BizException}：存储层不该知道「HTTP 状态码」这回事，
 * 由 service 层翻译成对外的错误（读不到 → 404，写不进 → 500）。
 */
public class AttachmentStorageException extends RuntimeException {

    public AttachmentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
