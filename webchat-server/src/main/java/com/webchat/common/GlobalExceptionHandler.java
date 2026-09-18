package com.webchat.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理，把异常统一收敛成 {@link Result}。
 *
 * <p>只覆盖「流式响应开始之前」抛出的异常：一旦 SSE 已经开始写出，异常到不了这里，
 * 流式过程中的失败由 ChatStreamService 转成 error 事件处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        HttpStatus status = HttpStatus.resolve(e.getCode());
        return build(status != null ? status : HttpStatus.BAD_REQUEST,
                Result.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, Result.error(ResultCode.INTERNAL_ERROR));
    }

    /**
     * 显式指定 JSON，绕开内容协商。
     *
     * <p>聊天接口的调用方会带 {@code Accept: text/event-stream}；此时若让 Spring 自行协商，
     * 找不到能把 {@link Result} 写成 event-stream 的转换器，会退化成 500 空响应，
     * 客户端既拿不到状态码语义也拿不到错误信息。内容类型一旦预设，Spring 就跳过协商直接用它。
     */
    private static ResponseEntity<Result<Void>> build(HttpStatus status, Result<Void> body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
