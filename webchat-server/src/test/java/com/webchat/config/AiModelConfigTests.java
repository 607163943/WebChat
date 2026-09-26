package com.webchat.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住 DashScope 的三个配置前缀。
 *
 * <p>{@code chat-model}、{@code streaming-chat-model} 与 {@code embedding-model} 是彼此独立的配置
 * 前缀，少配任何一个都只是「少一个 bean」而不会在启动时报错，要到注入时才失败。这里显式断言三个
 * 模型 bean 都在。
 *
 * <p>embedding 那个尤其值得钉住：它被文档检索服务注入，少了它连上下文都起不来——测试资源里的
 * {@code application-apidocs.yaml} 就曾经漏配这一项，那会让 {@code OpenApiExportTests} 直接失败、
 * openapi.json 再也导不出来。
 *
 * <p>只做类型断言，不调用模型，不消耗 API 额度。
 */
@SpringBootTest
class AiModelConfigTests {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void allModelBeansArePresent() {
        assertThat(chatModel).isInstanceOf(QwenChatModel.class);
        assertThat(streamingChatModel).isInstanceOf(QwenStreamingChatModel.class);
        assertThat(embeddingModel).isInstanceOf(QwenEmbeddingModel.class);
    }
}
