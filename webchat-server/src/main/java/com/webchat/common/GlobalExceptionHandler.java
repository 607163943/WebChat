package com.webchat.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 全局异常处理，把异常统一收敛成 {@link Result}。
 *
 * <p>只覆盖「流式响应开始之前」抛出的业务异常：一旦 SSE 已经开始写出，这类异常到不了这里，
 * 流式过程中的失败由 ChatStreamService 转成 error 事件处理。唯一的例外是客户端断开，
 * 它恰恰发生在写出之后，见 {@link #handleClientAbort}。
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

    /**
     * 客户端已经走了——用户点了「停止生成」，或直接关掉页面。
     *
     * <p>连接没了，写不回去任何东西，也不该按「未处理异常」记一条满屏堆栈的 ERROR：
     * 那是服务端自己出问题才有的待遇。这里降级成 info 并直接结束，不构造响应体
     * （void 返回值即「已处理，无需再写」）。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException e) {
        log.info("客户端已断开，不再写出剩余响应：{}", e.getMessage());
    }

    /**
     * 上传内容超过了 {@code spring.servlet.multipart.max-file-size}。
     *
     * <p>这个异常在参数解析阶段就抛出，<b>根本进不到业务的校验代码</b>。不单独接住的话它会落到
     * {@link #handleOther} 变成 500，用户传一张大一点的手机照片就看到「服务器内部错误」——
     * 明明是「文件太大」这种能说清楚的参数问题。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传内容超过限制：{}", e.getMessage());
        return build(HttpStatus.PAYLOAD_TOO_LARGE,
                Result.error(HttpStatus.PAYLOAD_TOO_LARGE.value(), "文件太大了，换一个小一点的"));
    }

    /** 请求里没有 file 部分：客户端拼错了，属于参数问题，同样不该算 500 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<Void>> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("上传请求缺少必需的部分：{}", e.getRequestPartName());
        return build(HttpStatus.BAD_REQUEST,
                Result.error(ResultCode.BAD_REQUEST.getCode(), "没有收到文件"));
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
