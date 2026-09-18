package com.webchat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把接口契约导出成 openapi.json 并提交进仓库。
 *
 * <p>前端据此生成 TypeScript 类型，因此接口改动会直接体现在 git diff 里，不需要起后端也能类型检查。
 * 之所以用测试而不是 springdoc-openapi-maven-plugin：这样不必起独立进程、不占 8000 端口，
 * 也不会和本机正在跑的 {@code mvn spring-boot:run} 打架。
 *
 * <p>surefire 的工作目录是模块根目录，所以产出落在 {@code webchat-server/openapi.json}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("apidocs")
class OpenApiExportTests {

    private static final Path OUTPUT = Path.of("openapi.json");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("导出 openapi.json 到模块根目录")
    void exportsOpenApiJson() throws IOException {
        String json = restTemplate.getForObject("/v3/api-docs", String.class);

        assertThat(json).isNotBlank();
        assertThat(json).contains("/api/conversations");

        Files.writeString(OUTPUT, json, StandardCharsets.UTF_8);
        assertThat(OUTPUT).exists();
    }
}
