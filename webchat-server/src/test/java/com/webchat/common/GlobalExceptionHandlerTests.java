package com.webchat.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 没有任何 handler 认领的路径怎么收场。
 *
 * <p>Spring 6.1 起这类请求抛的是 {@code NoResourceFoundException}，而它是 {@code Exception}
 * 的子类——不单独接住就会落进 {@link GlobalExceptionHandler} 的兜底分支，把「接口地址写错了」
 * 报成 500「服务器内部错误」，日志里还留一条满屏堆栈的 ERROR。这里钉住它停在 404，
 * 并且和其余失败一样是 {@link Result} 信封（前端靠 {@code message} 字段出文案）。
 *
 * <p>用 apidocs profile 起上下文：本用例只想验证一个 404，不该依赖 {@code .env} 里的真实
 * API Key（那个文件不入库，新克隆的仓库里没有）。道理与 {@code OpenApiExportTests} 相同。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("apidocs")
class GlobalExceptionHandlerTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("认不出来的路径返回 404，而不是兜底的 500")
    void unmatchedPathIsNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/nonexistent", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("认不出来的路径同样是 Result 信封，文案可直接展示")
    void unmatchedPathUsesResultEnvelope() {
        Result<?> result = restTemplate.getForObject("/totally/unknown", Result.class);

        assertThat(result).isNotNull();
        assertThat(result.code()).isEqualTo(ResultCode.NOT_FOUND.getCode());
        assertThat(result.message()).isEqualTo(ResultCode.NOT_FOUND.getMessage());
    }
}
