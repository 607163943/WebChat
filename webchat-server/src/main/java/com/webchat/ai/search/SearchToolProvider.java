package com.webchat.ai.search;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * 联网搜索工具的提供者：把 Exa 的 MCP 服务接成一组可被模型调用的工具。
 *
 * <p>这类提供者有「静态」与「动态」两种，本类是<b>动态</b>的（见 {@link #isDynamic()}），
 * 于是「与 Exa 建连接」被推迟到第一次真的要用它的时候——{@code DefaultMcpClient} 的构造函数里
 * 就做了 MCP 握手，若在装配 AI Service 时就把连接建起来，Exa 一抖动整个应用连启动都过不去。
 *
 * <p>三处刻意的降级，目的都是<b>让联网搜索失败不要变成对话失败</b>：
 * <ol>
 *   <li>没配密钥（或总开关关着）——不给工具，对话照常</li>
 *   <li>连不上 Exa——记一条 warn，本次及随后 {@link #RETRY_COOLDOWN} 内都不给工具，
 *       而不是每轮都去白等一次连接超时</li>
 *   <li>取工具表时抛异常——同上，按没有工具处理</li>
 * </ol>
 * 工具<b>执行</b>失败（搜到一半断网之类）不在这里兜：框架默认会把错误作为工具结果回给模型，
 * 模型据此作答即可，比整轮报错好。
 */
@Slf4j
public class SearchToolProvider implements ToolProvider, AutoCloseable {

    /**
     * 建连接失败后的冷却时长。
     *
     * <p>没有它的话，Exa 不可用期间每一轮提问都要先白等一次连接超时（最长可达
     * {@code initialization-timeout}），用户看到的就是「发出去半天没有任何反应」。
     * 冷却期内直接按没有工具处理，请求照常走到模型。
     */
    private static final Duration RETRY_COOLDOWN = Duration.ofMinutes(5);

    private final SearchProperties properties;
    private final Object lock = new Object();

    /** 建好的委托；null 表示「还没建」或「上次建失败了」。读的多写的少，用 volatile 而不是锁 */
    private volatile McpToolProvider delegate;

    /** 建连接那一次拿到的客户端，仅用于关停时释放 */
    private McpClient client;

    /** 冷却截止时刻（{@link System#currentTimeMillis()}）；0 表示不在冷却中。只在锁内读写 */
    private long retryAt;

    public SearchToolProvider(SearchProperties properties) {
        this.properties = properties;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        McpToolProvider provider = providerOrNull();
        if (provider == null) {
            // 这一轮没有工具：模型照常回答，只是没法联网
            return ToolProviderResult.builder().build();
        }
        try {
            return provider.provideTools(request);
        } catch (RuntimeException e) {
            log.warn("取联网搜索工具失败，本轮按没有工具处理：{}", e.getMessage());
            return ToolProviderResult.builder().build();
        }
    }

    /**
     * 本提供者是动态的：工具要到每一轮提问时才解析。
     *
     * <p>这不是性能考虑，而是启动能否成功的问题。框架对动态提供者会在<b>每个请求</b>上调用
     * {@code provideTools}，对非动态的则在装配 AI Service 那一刻就调用一次
     * （见 {@code ToolService#createContextFromStaticToolsAndProviders}）。后者意味着
     * 「Exa 不通 → 应用起不来」，而联网搜索本来就只是个可选的增强。
     */
    @Override
    public boolean isDynamic() {
        return true;
    }

    /** 拿到可用的委托；还没建就建一次，建不了（没配 / 连不上 / 在冷却中）返回 null */
    private McpToolProvider providerOrNull() {
        McpToolProvider current = delegate;
        if (current != null) {
            return current;
        }
        if (!isConfigured()) {
            return null;
        }
        synchronized (lock) {
            if (delegate != null) {
                return delegate;
            }
            if (System.currentTimeMillis() < retryAt) {
                return null;
            }
            try {
                client = buildClient();
                delegate = McpToolProvider.builder()
                        .mcpClients(client)
                        // 眼下只有一个服务，这一项是留给日后加第二个的：挂了其中一个不该整体失去工具
                        .failIfOneServerFails(false)
                        .build();
                log.info("联网搜索已就绪，可用工具：{}", properties.tools());
                return delegate;
            } catch (RuntimeException e) {
                // 只记异常自己的一句话，不记整个异常——它里面可能带着拼好的端点，而那段 URL 挂着密钥
                log.warn("连接 Exa 搜索 MCP 失败，{} 内不再重试，期间各轮都按没有工具处理：{}",
                        RETRY_COOLDOWN, e.getMessage());
                retryAt = System.currentTimeMillis() + RETRY_COOLDOWN.toMillis();
                return null;
            }
        }
    }

    /** 总开关开着、且配了密钥，才谈得上连接 */
    private boolean isConfigured() {
        return properties.enabled() && properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    private McpClient buildClient() {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(endpoint())
                .timeout(properties.toolTimeout())
                // 两项都显式关掉：请求 URL 上挂着密钥，任何一行把它打出来的日志都是泄漏
                .logRequests(false)
                .logResponses(false)
                .build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .clientName("webchat")
                .initializationTimeout(properties.initializationTimeout())
                .toolExecutionTimeout(properties.toolTimeout())
                // 工具表在服务端是固定的：缓存下来，不必每轮都去问一次
                .cacheToolList(true)
                .build();
    }

    /**
     * 拼出带鉴权与工具白名单的端点。
     *
     * <p>密钥走查询参数而不是请求头：Exa 只把 {@code exaApiKey} 参数（与 {@code Authorization} 头）
     * 当鉴权依据，{@code x-api-key} 头并不作数——换个头传的后果不是报错，而是<b>静默地</b>退回免费额度，
     * 查起来很费劲。代价是密钥落在 URL 里，所以这里拼出来的字符串只进请求，不进日志。
     */
    private String endpoint() {
        StringBuilder url = new StringBuilder(properties.url());
        url.append(properties.url().contains("?") ? '&' : '?')
                .append("exaApiKey=").append(encode(properties.apiKey()));
        if (!properties.tools().isEmpty()) {
            // 逗号不编码：Exa 按逗号切分这个列表，逗号本身是分隔符而不是值的组成部分
            url.append("&tools=").append(properties.tools().stream()
                    .map(SearchToolProvider::encode)
                    .collect(Collectors.joining(",")));
        }
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 关停时释放连接；没建过就什么都不做 */
    @Override
    public void close() {
        McpClient current = client;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (Exception e) {
            log.warn("关闭 Exa 搜索 MCP 连接失败：{}", e.getMessage());
        }
    }
}
