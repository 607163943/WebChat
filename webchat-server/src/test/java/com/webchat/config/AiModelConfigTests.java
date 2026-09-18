package com.webchat.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住 DashScope 的两个配置前缀。
 *
 * <p>{@code chat-model} 与 {@code streaming-chat-model} 是彼此独立的配置前缀，
 * 少配任何一个都只是「少一个 bean」而不会在启动时报错，要到注入时才失败。
 * 这里显式断言两个模型 bean 都在。
 *
 * <p>只做类型断言，不调用模型，不消耗 API 额度。
 */
@SpringBootTest
class AiModelConfigTests {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private StreamingChatModel streamingChatModel;

    @Test
    void bothModelBeansArePresent() {
        assertThat(chatModel).isInstanceOf(QwenChatModel.class);
        assertThat(streamingChatModel).isInstanceOf(QwenStreamingChatModel.class);
    }
}
