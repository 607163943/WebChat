package com.webchat.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Service 这条装配的契约测试：记忆怎么放、本轮提问怎么传。
 *
 * <p>钉住的都是「框架行为」而不是本项目的行为——它们决定了 {@link ChatStreamService} 能不能
 * 只靠「每轮灌一次记忆」就把上下文管住，也决定了本轮提问为什么必须用
 * {@code @UserMessage List<Content>} 的形状传（换成字符串或裸的 {@code List<Content>} 都会坏，
 * 见 {@link ChatAssistant} 的说明）。框架升级后这里要是红了，说明那套假设不成立了。
 *
 * <p>用假的 {@link FakeStreamingChatModel} 驱动，不联网、不碰数据库。
 */
class ChatAssistantTests {

    private static final long CONVERSATION_ID = 1L;

    private final FakeStreamingChatModel model = new FakeStreamingChatModel();
    private final ConversationMemoryStore memoryStore = new ConversationMemoryStore();

    private ChatAssistant assistant;

    @BeforeEach
    void setUp() {
        // 与 AssistantConfig 用同一处装配，避免测试里再抄一份、抄歪了还测不出来
        assistant = new AssistantConfig().chatAssistant(model, memoryStore);
    }

    /** 按 ChatStreamService 的传法灌一份记忆：系统提示词在最前，随后是历史 */
    private void seed(List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(Prompt.SYSTEM_PROMPT));
        messages.addAll(history);
        memoryStore.seed(CONVERSATION_ID, messages);
    }

    private List<String> ask(List<Content> question) {
        model.emits("答");
        return assistant.chat(CONVERSATION_ID, question)
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    /** 发出去的那条本轮提问 */
    private UserMessage sentQuestion() {
        List<ChatMessage> sent = model.sentMessages();
        return (UserMessage) sent.get(sent.size() - 1);
    }

    @Test
    @DisplayName("模型收到的顺序：系统提示词 → 记忆里的历史 → 本轮提问")
    void sendsSystemPromptThenHistoryThenQuestion() {
        seed(List.of(UserMessage.from("上一问"), AiMessage.from("上一答")));

        ask(List.of(TextContent.from("这一问")));

        assertThat(model.sentMessages()).containsExactly(
                SystemMessage.from(Prompt.SYSTEM_PROMPT),
                UserMessage.from("上一问"),
                AiMessage.from("上一答"),
                UserMessage.from("这一问"));
    }

    @Test
    @DisplayName("本轮提问的内容原样发出去：正文、文件说明、媒体一个不少")
    void deliversTheQuestionContentsVerbatim() {
        seed(List.of());
        List<Content> question = List.of(
                TextContent.from("这是什么"),
                TextContent.from("（用户上传了文件：纪要.txt）"),
                ImageContent.from("AQID", "image/png"));

        ask(question);

        assertThat(sentQuestion().contents()).containsExactlyElementsOf(question);
    }

    @Test
    @DisplayName("只带附件没打字时，模型收到的就是一条纯媒体的消息")
    void keepsAttachmentOnlyQuestionFreeOfText() {
        seed(List.of());

        ask(List.of(ImageContent.from("AQID", "image/png")));

        assertThat(sentQuestion().contents())
                .containsExactly(ImageContent.from("AQID", "image/png"));
    }

    @Test
    @DisplayName("本轮提问与助手回复都会补进记忆，供下一轮当历史用")
    void keepsBothSidesOfTheTurnInMemory() {
        seed(List.of());

        ask(List.of(TextContent.from("这一问")));

        assertThat(memoryStore.getMessages(CONVERSATION_ID)).containsExactly(
                SystemMessage.from(Prompt.SYSTEM_PROMPT),
                UserMessage.from("这一问"),
                AiMessage.from("答"));
    }

    @Test
    @DisplayName("生成失败时框架不往记忆里写助手消息：写库那边得自己收尾")
    void modelFailureLeavesNoAssistantMessageInMemory() {
        seed(List.of());
        model.failsAfter("半截", new RuntimeException("模型挂了"));

        List<String> tokens = assistant.chat(CONVERSATION_ID, List.of(TextContent.from("这一问")))
                .onErrorReturn("ERR")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(tokens).containsExactly("半截", "ERR");
        // 记忆里只剩本轮提问——半截回复要落库、要让下一轮看见，只能由我们自己做（见 ChatStreamService）
        assertThat(memoryStore.getMessages(CONVERSATION_ID)).containsExactly(
                SystemMessage.from(Prompt.SYSTEM_PROMPT), UserMessage.from("这一问"));
    }

    @Test
    @DisplayName("取消订阅时框架不留半条助手消息在记忆里")
    void cancellationLeavesNoAssistantMessageInMemory() throws Exception {
        seed(List.of());
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<dev.langchain4j.model.chat.response.StreamingChatResponseHandler> handler =
                model.handsOverHandler(called);

        Disposable subscription = assistant.chat(CONVERSATION_ID, List.of(TextContent.from("这一问")))
                .subscribe();
        assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
        handler.get().onPartialResponse("半截");
        subscription.dispose();
        // 断开之后的收尾是异步的，给它一点时间再断言「什么都没留下」
        Thread.sleep(300);

        assertThat(memoryStore.getMessages(CONVERSATION_ID)).containsExactly(
                SystemMessage.from(Prompt.SYSTEM_PROMPT), UserMessage.from("这一问"));
    }

    @Test
    @DisplayName("每轮灌进来的历史原样发出去，系统提示词在最前")
    void sendsTheSeededHistoryAsIs() {
        seed(historyOf(Prompt.MAX_HISTORY_MESSAGES));

        ask(List.of(TextContent.from("最新一问")));

        // 系统提示词 + 灌进去的整批历史 + 本轮提问，一条不多一条不少
        assertThat(model.sentMessages()).hasSize(Prompt.MAX_HISTORY_MESSAGES + 2);
        assertThat(model.sentMessages().get(0)).isEqualTo(SystemMessage.from(Prompt.SYSTEM_PROMPT));
    }

    @Test
    @DisplayName("灌再多也不会无限增长：窗口满了从最旧的历史开始丢，系统提示词始终留着")
    void keepsMemoryBoundedAndTheSystemMessageFirst() {
        seed(historyOf(Prompt.MAX_HISTORY_MESSAGES * 4));

        ask(List.of(TextContent.from("最新一问")));

        // 超出的部分被窗口丢掉了：留下的是系统提示词 + 窗口允许的那么多条
        assertThat(memoryStore.getMessages(CONVERSATION_ID))
                .hasSize(Prompt.MAX_HISTORY_MESSAGES + 2)
                // 系统提示词不占额度、也不会被挤掉——这正是 alwaysKeepSystemMessageFirst 的作用，
                // 没有它，长会话一过窗口模型就会失去人设
                .first().isEqualTo(SystemMessage.from(Prompt.SYSTEM_PROMPT));
        assertThat(model.sentMessages().get(0)).isEqualTo(SystemMessage.from(Prompt.SYSTEM_PROMPT));
    }

    /** 一问一答算两条：给「灌 20 条历史」这种说法一个准确的构造入口 */
    private static List<ChatMessage> historyOf(int messages) {
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < messages / 2; i++) {
            history.add(UserMessage.from("第 " + i + " 问"));
            history.add(AiMessage.from("第 " + i + " 答"));
        }
        return history;
    }
}
