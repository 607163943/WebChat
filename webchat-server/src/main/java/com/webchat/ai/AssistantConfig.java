package com.webchat.ai;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话用 AI Service 的装配：模型与记忆在这里拼起来。
 *
 * <p>记忆的实现是 {@link MessageWindowChatMemory}——按<b>消息条数</b>保留最近的若干条：
 * 条数比 token 数好估，而超长会话真正压垮请求的是条数，不是长度。窗口取
 * 「历史条数 + 系统提示词 + 本轮提问」，正好放下每轮灌进去的那一批，不多不少。
 *
 * <p>槽位号就是会话 ID（{@code ChatAssistant} 上的 {@code @MemoryId}），内容存在
 * {@link ConversationMemoryStore} 里，每轮由 {@link ChatStreamService} 用库里的历史覆盖。
 * 框架自己也会按槽位号缓存 ChatMemory 实例，所以 provider 里每次新建并无所谓——
 * 真正存消息的是那个 store。
 *
 * <p>{@code alwaysKeepSystemMessageFirst} 必须开：窗口满了会从最旧的一条开始丢，
 * 而系统提示词正好是最旧的那条（由 {@link ChatMessageAssembler} 灌在历史最前面），
 * 不开的话长会话一过窗口就会把它挤掉，模型从此失去人设。
 */
@Slf4j
@Configuration
public class AssistantConfig {

    @Bean
    public ChatAssistant chatAssistant(StreamingChatModel streamingChatModel,
                                       ConversationMemoryStore memoryStore) {
        return AiServices.builder(ChatAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(Prompt.MAX_HISTORY_MESSAGES + 2)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(memoryStore)
                        .build())
                .build();
    }
}
