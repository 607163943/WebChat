package com.webchat.ai.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 文档检索的装配。
 *
 * <p>{@link RagProperties} 是 record、不参与组件扫描，必须显式
 * {@code @EnableConfigurationProperties} 注册（与 {@code AttachmentConfig} 同一个坑）。
 *
 * <p>{@code EmbeddingModel} 不用在这里装配：它由 {@code langchain4j-community-dashscope-spring-boot-starter}
 * 按 {@code langchain4j.community.dashscope.embedding-model.api-key} 条件生成。那个前缀少配了不会
 * 启动报错、只会少一个 bean，要到注入时才失败——所以 {@code AiModelConfigTests} 专门断言了它。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    /**
     * 纯内存向量库，进程重启即清空。
     *
     * <p>这是当前的既定取舍：重启后已绑定的附件仍在库里，但它们的向量没了，要重新上传才有检索。
     * 换成持久化实现时只需替换这个 bean，检索侧的代码不用动。
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 索引文档专用的线程池。
     *
     * <p>核心与最大都设 1 是<b>有意串行</b>：embedding 接口有 TPM 限额（该模型每分钟 100 万 token），
     * 并发索引几个大文件很容易撞限流，而限流会让整批索引失败。排队慢一点，但结果可预期。
     *
     * <p>注意这个 bean 的名字会被 {@link DocumentIndexService} 用 {@code @Qualifier} 点名注入：
     * Spring Boot 的 Web 自动配置也会提供 Executor，按类型注入是歧义的。
     */
    @Bean(name = DocumentIndexService.EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor documentIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("document-index-");
        // 拒绝策略必须是「丢弃并记日志」这一种：
        // CallerRunsPolicy 会把任务退回调用线程执行，也就是堵住上传请求，正好违背异步的初衷；
        // AbortPolicy 会让 RejectedExecutionException 冒进 upload()，把一次已经成功的上传变成 500。
        executor.setRejectedExecutionHandler((task, pool) -> log.warn(
                "文档索引队列已满，本次不再索引：activeCount={}, queueSize={}",
                pool.getActiveCount(), pool.getQueue().size()));
        // 停机时等在途的索引跑完，否则「重启即失效」会提前到关闭那一刻
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
