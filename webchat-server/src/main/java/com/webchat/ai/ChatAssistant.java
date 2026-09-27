package com.webchat.ai;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话的 AI Service：记忆由框架装配，本接口只声明形状。
 *
 * <p><b>系统提示词不在这里声明</b>（没有 {@code @SystemMessage}）：框架会把它追加到记忆已有的
 * 内容之后，于是长这样——{@code [历史..., system, 本轮提问]}，而 system 必须紧跟 user 才安全
 * （DashScope 的适配在清洗消息时，遇到「system 之后不是 user」会把那条消息静默丢掉，
 * 不报错也不抛异常）。所以它改成由 {@link ChatMessageAssembler} 灌在记忆的最前面，
 * 位置与直接用底层 API 时一模一样。
 *
 * <p>记忆——{@link MemoryId} 即会话 ID，实现见 {@link AssistantConfig} 的 chatMemoryProvider。
 *
 * <p><b>本轮提问用 {@code @UserMessage List<Content>} 传，而不是 {@code @UserMessage String}</b>，
 * 两个原因：
 * <ol>
 *   <li>字符串会被当 {@code PromptTemplate} 解析：用户正文里出现 {@code {{...}}} 就会被当成模板变量，
 *       抛「Value for the variable 'x' is missing」；而且模板渲染结果不允许为空，
 *       「只发图片不打字」那一轮根本没法表达</li>
 *   <li>只有这一种形状能把媒体送进去。{@code List<Content>} 若不加 {@code @UserMessage} 注解，
 *       参数的合法性校验就过不去；而 {@code @UserMessage String} 那条路上，框架会把当前消息重建为
 *       「框架收集到的内容 + 消息里的文本内容」，媒体会被丢掉（只带媒体的那一轮还会因为内容为空直接抛异常）。
 *       加了注解的这一条路则是把我们给的内容原样用上，见 {@code DefaultAiServices#addContentsToUserMessage}</li>
 * </ol>
 *
 * @param conversationId 会话 ID，同时是记忆的槽位号
 * @param question       本轮提问的全部内容，顺序见 {@link ChatMessageAssembler#renderQuestion}；
 *                       检索到的资料由调用方拼在最前面，不在这里
 * @return 增量文本流；一字未吐就结束、或中途报错，分别由订阅方按「空回复」「生成失败」收场
 */
public interface ChatAssistant {

    Flux<String> chat(@MemoryId Long conversationId, @UserMessage List<Content> question);
}
