package com.webchat.common;

/**
 * 统一响应体，含 code / message / data 三个字段。
 *
 * <p>聊天接口（返回 text/event-stream）是唯一例外，它不套这层信封。
 */
public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.OK.getCode(), ResultCode.OK.getMessage(), data);
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
