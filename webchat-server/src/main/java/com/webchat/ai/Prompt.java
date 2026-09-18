package com.webchat.ai;

/**
 * 提示词与相关上限常量。集中放一处，便于日后调参。
 */
public final class Prompt {

    private Prompt() {
    }

    /** 系统提示词。当前阶段固定为代码常量，不落库 */
    public static final String SYSTEM_PROMPT = """
            你是 WebChat 的 AI 助手，请用简洁、准确的中文回答问题。
            回答使用 Markdown 格式：代码放进带语言标注的代码块，适当使用列表与小标题。""";

    /** 标题生成提示词模板，参数为用户的首条提问 */
    public static final String TITLE_PROMPT_TEMPLATE = """
            请为下面这段用户提问生成一个简短的会话标题。
            要求：只输出标题本身，不要标点、引号、前缀或任何解释，不超过 15 个字。

            用户提问：
            %s""";

    /**
     * 单次请求最多携带的历史消息条数。
     *
     * <p>不设上限的话，长会话会一路把历史全量发给模型，最终以请求超长报错收场。
     */
    public static final int MAX_HISTORY_MESSAGES = 20;

    /**
     * 用户单条消息的字符上限。
     *
     * <p>{@code tb_message.content} 是 TEXT，65535 <b>字节</b>；utf8mb4 下单字符最多 4 字节，
     * 即最多约 16000 字符，这里取 8000 留足余量。
     */
    public static final int MAX_USER_MESSAGE_LENGTH = 8000;
}
