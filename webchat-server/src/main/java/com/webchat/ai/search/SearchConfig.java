package com.webchat.ai.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 联网搜索的装配。
 *
 * <p>{@link SearchProperties} 是 record、不参与组件扫描，必须显式
 * {@code @EnableConfigurationProperties} 注册（与 {@code AttachmentConfig} / {@code RagConfig} 同一个坑）。
 *
 * <p>这里只有「建一个提供者」这一件事：连接、握手、工具表全都推迟到第一次提问时
 * （见 {@link SearchToolProvider}），所以这个 bean 建出来是<b>不联网</b>的——
 * 没配密钥、Exa 不通、甚至完全不想联网，都不影响应用启动与其余功能。
 */
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

    @Bean(destroyMethod = "close")
    public SearchToolProvider searchToolProvider(SearchProperties properties) {
        return new SearchToolProvider(properties);
    }
}
