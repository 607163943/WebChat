package com.webchat.common;

import lombok.Getter;

/**
 * 业务异常，由 {@link GlobalExceptionHandler} 统一转换成 {@link Result}。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    /** 沿用枚举的状态码，但换一条更具体的提示 */
    public BizException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
