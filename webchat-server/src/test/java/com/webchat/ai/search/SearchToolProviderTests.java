package com.webchat.ai.search;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 联网搜索这一路的降级行为。
 *
 * <p>要钉住的其实只有一件事：<b>联网搜索出任何问题，对话都不该跟着出问题</b>。所以这里既不连真的
 * Exa，也不要求 MCP 握手成功——起一个本地假服务，只记下收到的请求地址，然后一律以 400 回绝，
 * 握手必然失败，正好用来检查失败之后我们是什么表现。
 *
 * <p>另一条同样是「失败也要能查」：密钥与工具白名单是不是真的写进了查询参数。写错了不会报错，
 * 只会静默退回免费额度，从现象上根本看不出来，所以在这里钉死。
 */
class SearchToolProviderTests {

    private static final ToolProviderRequest REQUEST = new ToolProviderRequest(1L, UserMessage.from("你好"));

    private HttpServer server;

    /** 假服务收到的请求地址，按到达顺序 */
    private final List<String> requestedUris = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个本地假 MCP 服务并返回指向它的配置；握手会以 400 告终 */
    private SearchProperties propertiesAgainstStub(String apiKey, boolean enabled) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            requestedUris.add(exchange.getRequestURI().toString());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new SearchProperties(enabled, endpointOfStub(), apiKey,
                List.of("web_search_exa", "web_fetch_exa"),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private String endpointOfStub() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @Test
    @DisplayName("连不上 Exa：按「这一轮没有工具」处理，不把异常抛给对话")
    void degradesToNoToolsWhenTheServerIsUnreachable() throws IOException {
        SearchProperties properties = propertiesAgainstStub("test-key", true);

        try (SearchToolProvider provider = new SearchToolProvider(properties)) {
            assertThat(provider.provideTools(REQUEST).tools()).isEmpty();
        }
    }

    @Test
    @DisplayName("连不上之后进入冷却：期间每一轮都不再试着去连")
    void doesNotRetryWhileCoolingDown() throws IOException {
        SearchProperties properties = propertiesAgainstStub("test-key", true);

        try (SearchToolProvider provider = new SearchToolProvider(properties)) {
            provider.provideTools(REQUEST);
            int afterFirstAttempt = requestedUris.size();
            // 先确认第一次真的发出了请求，否则下面那条「没有新请求」等于什么都没测
            assertThat(afterFirstAttempt).isPositive();

            provider.provideTools(REQUEST);

            // 没有冷却的话，Exa 不可用期间每一轮提问都要先白等一次连接超时，
            // 用户看到的就是「发出去半天没有任何反应」
            assertThat(requestedUris).hasSize(afterFirstAttempt);
        }
    }

    @Test
    @DisplayName("密钥与工具白名单走查询参数——Exa 只认 exaApiKey 这个参数")
    void passesTheKeyAndToolWhitelistAsQueryParameters() throws IOException {
        SearchProperties properties = propertiesAgainstStub("test-key", true);

        try (SearchToolProvider provider = new SearchToolProvider(properties)) {
            provider.provideTools(REQUEST);
        }

        assertThat(requestedUris).isNotEmpty();
        assertThat(requestedUris.get(0))
                .contains("exaApiKey=test-key")
                // 逗号是分隔符，不编码
                .contains("tools=web_search_exa,web_fetch_exa");
    }

    @Test
    @DisplayName("没配密钥、或总开关关掉：一次连接都不建")
    void neverConnectsWhenNotConfigured() throws IOException {
        SearchProperties withoutKey = propertiesAgainstStub("", true);

        try (SearchToolProvider provider = new SearchToolProvider(withoutKey)) {
            assertThat(provider.provideTools(REQUEST).tools()).isEmpty();
        }
        assertThat(requestedUris).isEmpty();

        // 换了配置就换一个假服务：下面这条断言的是「关掉之后连请求都不发」
        requestedUris.clear();
        SearchProperties disabled = propertiesAgainstStub("test-key", false);
        try (SearchToolProvider provider = new SearchToolProvider(disabled)) {
            assertThat(provider.provideTools(REQUEST).tools()).isEmpty();
        }
        assertThat(requestedUris).isEmpty();
    }

    @Test
    @DisplayName("是动态提供者：工具到每一轮提问时才解析，而不是装配 AI Service 那一刻")
    void resolvesToolsPerRequest() throws IOException {
        // 这一条看着像在测实现细节，其实决定了「Exa 挂了应用还能不能启动」：
        // 非动态的提供者会在建 AI Service 时就被调用一次，而 DefaultMcpClient 在构造函数里
        // 就做 MCP 握手——那样 Exa 一抖动，整个应用连启动都过不去
        try (SearchToolProvider provider = new SearchToolProvider(propertiesAgainstStub("test-key", true))) {
            assertThat(provider.isDynamic()).isTrue();
        }
    }
}
