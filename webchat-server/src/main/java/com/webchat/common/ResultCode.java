package com.webchat.common;

import lombok.Getter;

/**
 * 业务响应码。code 同时用作 HTTP 状态码，便于前端拦截器与 curl 调试时一眼看出成败。
 */
@Getter
public enum ResultCode {

    OK(200, "success"),
    BAD_REQUEST(400, "请求参数有误"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
