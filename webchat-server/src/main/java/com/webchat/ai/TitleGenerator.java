package com.webchat.ai;

import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 用模型为用户的首条提问生成一个简短的会话标题。
 *
 * <p>走的是非流式的 {@link ChatModel}，是一次阻塞调用，调用方需自行切到弹性线程池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TitleGenerator {

    /** {@code tb_conversation.title} 是 varchar(100)，超长会被数据库截断报错 */
    static final int MAX_TITLE_LENGTH = 100;

    private final ChatModel chatModel;

    /**
     * @return 清洗后的标题；模型没给出可用内容或调用失败时返回空
     */
    public Optional<String> generate(String userMessage) {
        try {
            String raw = chatModel.chat(Prompt.TITLE_PROMPT_TEMPLATE.formatted(userMessage));
            return sanitize(raw);
        } catch (Exception e) {
            // 标题是锦上添花，失败不该影响已经成功的回复
            log.warn("生成会话标题失败", e);
            return Optional.empty();
        }
    }

    /**
     * 清洗模型输出，使其能安全地写进 varchar(100) 的标题字段。
     *
     * <p>模型常见的不听话表现：返回多行、带「标题：」前缀、给标题套上引号或书名号。
     * 包级可见，便于脱离模型单独做单元测试。
     */
    static Optional<String> sanitize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        // 多行时只取第一行有内容的部分
        String title = raw.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        title = stripPrefix(title);
        title = stripWrappingQuotes(title).strip();
        if (title.isEmpty()) {
            return Optional.empty();
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
        return Optional.of(title);
    }

    private static String stripPrefix(String title) {
        return title.replaceFirst("^(会话)?标题\\s*[:：]\\s*", "");
    }

    /** 去掉成对包裹的引号，中英文都处理一次；只去最外层的一对 */
    private static String stripWrappingQuotes(String title) {
        String result = title;
        for (String[] pair : QUOTE_PAIRS) {
            if (result.length() >= 2 && result.startsWith(pair[0]) && result.endsWith(pair[1])) {
                result = result.substring(1, result.length() - 1);
                break;
            }
        }
        return result;
    }

    private static final String[][] QUOTE_PAIRS = {
            {"\"", "\""},
            {"'", "'"},
            {"“", "”"},
            {"‘", "’"},
            {"「", "」"},
            {"『", "』"},
            {"《", "》"},
    };
}
