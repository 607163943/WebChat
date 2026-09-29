package com.webchat.ai.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * 联网搜索（Exa 的 MCP 服务）相关的可调项，集中在 application.yaml 的 {@code webchat.search} 段。
 *
 * <p>与 {@code AttachmentProperties} / {@code RagProperties} 一样是 record、不参与组件扫描，必须在
 * {@link SearchConfig} 上显式 {@code @EnableConfigurationProperties}，否则注入它的地方会以
 * {@code NoSuchBeanDefinitionException} 启动失败。
 */
@ConfigurationProperties(prefix = "webchat.search")
public record SearchProperties(

        /**
         * 总开关。关掉之后模型手上就没有工具，下面几项一律不再读取。
         *
         * <p>留这个开关是为了能<b>不做改代码、不删密钥</b>地关掉联网：Exa 限流、额度用尽、
         * 或者只是想比对一下不带搜索的回答时，改这一行就够了。
         */
        @DefaultValue("true") boolean enabled,

        /** Exa 托管的 MCP 端点 */
        @DefaultValue("https://mcp.exa.ai/mcp") String url,

        /**
         * Exa 的 API Key，来自环境变量 {@code MCP_SEARCH_API_KEY}。
         *
         * <p><b>它是作为查询参数发给 MCP 服务的</b>（{@code ?exaApiKey=...}）：Exa 那边只认这个参数
         * （以及 {@code Authorization} 头），{@code x-api-key} 头并不作为鉴权依据。也正因为密钥坐在
         * URL 里，拼接出来的那条地址绝不能进日志——{@link SearchToolProvider} 里对失败只记一句话，
         * 不记异常带来的地址。
         *
         * <p>留空即「没配」：不建连接、不给工具，对话照常进行。联网搜索是锦上添花，不该因为
         * 少一个环境变量就让整个应用起不来。
         */
        String apiKey,

        /**
         * 放行哪些工具。写进 {@code ?tools=} 之后服务端只暴露这几个。
         *
         * <p>不限制的话 Exa 会把默认那一套全给出来（还有更重的 agent 类工具），工具 schema 白占
         * 上下文，模型也更容易挑错。这两个够用了：{@code web_search_exa} 搜索、
         * {@code web_fetch_exa} 读某个具体网址的全文。
         */
        @DefaultValue({"web_search_exa", "web_fetch_exa"}) List<String> tools,

        /** 单次工具调用的超时。搜索结果要现场抓取，给得比普通接口宽 */
        @DefaultValue("30s") Duration toolTimeout,

        /** 与 MCP 服务握手（initialize）的超时 */
        @DefaultValue("15s") Duration initializationTimeout) {
}
