package com.webchat.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「流式模型 + 工具」这条路的契约测试——联网搜索整个架在上面。
 *
 * <p>三个框架行为必须成立，缺一个搜索就得换做法：
 * <ol>
 *   <li>工具<b>开始执行</b>时有 {@code beforeToolExecution} 回调，且带着工具名。
 *       搜索期间模型一个字都不吐，前端那几秒的「正在搜索…」只有这一个来源</li>
 *   <li>工具会被真的执行，结果作为消息回给模型，模型据此接着吐字——
 *       也就是说「先搜再答」在同一轮里完成，不需要我们自己串两次调用</li>
 *   <li>工具自己失败（搜索服务抖动之类）<b>不会</b>把整轮变成错误，而是把错误当工具结果回给模型，
 *       让模型就现有信息作答。这一条决定了搜索故障时用户是「收到一个稍差的回答」还是「收到报错」</li>
 * </ol>
 *
 * <p>用假的 {@link FakeStreamingChatModel} 驱动，不联网、不碰数据库、不消耗额度。
 */
class ChatAssistantToolTests {

    private static final long CONVERSATION_ID = 1L;
    private static final String TOOL_NAME = "web_search_exa";
    private static final String TOOL_RESULT = "搜索结果：今天晴，26 度";

    private final FakeStreamingChatModel model = new FakeStreamingChatModel();
    private final ConversationMemoryStore memoryStore = new ConversationMemoryStore();

    /** 工具每次被调用时的入参，用来断言「真的执行了」而不只是「框架说它要执行」 */
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

    private ChatAssistant assistant;

    @BeforeEach
    void setUp() {
        assistant = assistantWith((request, memoryId) -> {
            calls.add(request.arguments());
            return TOOL_RESULT;
        });
    }

    /** 按生产同一处装配建一个带工具的助手；工具本身由入参决定，好让失败那条用例换掉它 */
    private ChatAssistant assistantWith(ToolExecutor executor) {
        ToolSpecification specification = ToolSpecification.builder()
                .name(TOOL_NAME)
                .description("联网搜索")
                .build();
        ToolProvider provider = request -> new ToolProviderResult(Map.of(specification, executor));
        return new AssistantConfig().chatAssistant(model, memoryStore, provider);
    }

    /** 问一句，把这一轮跑完 */
    private TokenStreamRecorder ask(String question) {
        return TokenStreamRecorder.start(
                assistant.chat(CONVERSATION_ID, List.of(TextContent.from(question))));
    }

    /** 框架回给模型的那条工具结果消息 */
    private ToolExecutionResultMessage toolResultSentToModel() {
        return model.sentMessages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("模型压根没收到工具结果"));
    }

    @Test
    @DisplayName("模型点工具：先回调（带工具名）→ 执行 → 结果回给模型 → 接着吐字")
    void executesTheToolAndFeedsTheResultBack() {
        model.callsToolThenEmits(TOOL_NAME, "{\"query\":\"今天天气\"}", "今", "天晴");

        TokenStreamRecorder recorder = ask("今天天气如何");

        // 工具开始执行时的回调——search 事件就架在它上面
        assertThat(recorder.toolsCalled()).containsExactly(TOOL_NAME);
        // 工具真的跑了，入参就是模型给的那份
        assertThat(calls).containsExactly("{\"query\":\"今天天气\"}");
        // 结果作为一条消息回给了模型，模型接着把话说完
        assertThat(toolResultSentToModel().text()).isEqualTo(TOOL_RESULT);
        assertThat(recorder.tokens()).containsExactly("今", "天晴");
        assertThat(recorder.errorAfter()).isNull();
    }

    @Test
    @DisplayName("工具执行失败不打断整轮：错误当工具结果回给模型，模型照常作答")
    void toolFailureIsReportedToTheModelInsteadOfFailingTheTurn() {
        assistant = assistantWith((request, memoryId) -> {
            throw new IllegalStateException("搜索服务不可用");
        });
        model.callsToolThenEmits(TOOL_NAME, "{}", "没搜到，先这样答");

        TokenStreamRecorder recorder = ask("今天天气如何");

        assertThat(recorder.errorAfter()).isNull();
        assertThat(recorder.tokens()).containsExactly("没搜到，先这样答");
        // 那条结果被标成错误，并且带着原因——模型据此知道自己是在「没有搜索结果」的情况下作答
        ToolExecutionResultMessage result = toolResultSentToModel();
        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("搜索服务不可用");
    }
}
