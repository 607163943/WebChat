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
     * 检索到的文档片段模板，参数为拼好的片段正文。
     *
     * <p>它会作为本轮用户消息的<b>第一段文本</b>注入（见 {@code ChatStreamService}），而不是新加一条
     * system 消息：DashScope 的适配在清洗消息时，遇到「system 之后不是 user」会<b>静默丢弃</b>那条消息，
     * 不报错也不抛异常——RAG 会彻底失效却查不出原因。
     *
     * <p>末句是必须的：不明确禁止，模型会拿文档里的只言片语去补全一个看起来合理的答案。
     */
    public static final String KNOWLEDGE_PROMPT_TEMPLATE = """
            以下是从用户上传的文档中检索到的片段，请优先依据这些内容回答。
            若其中没有答案，直接说明文档里没有相关信息，不要凭推测补充。

            %s""";

    /** 有文档、但一条都没检索到时的提示。不吭声的话模型会凭常识硬答 */
    public static final String KNOWLEDGE_EMPTY_HINT =
            "（用户上传了文档，但没有检索到与本次提问相关的内容）";

    /**
     * 文档还在索引队列里时的提示。
     *
     * <p>上传后立刻提问必然走到这里——索引是异步的，而用户打字只要几秒。此时如实说明，
     * 比让模型装作读过文件要好。
     */
    public static final String KNOWLEDGE_PENDING_HINT =
            "（用户上传的文档仍在处理中，其内容暂时无法检索）";

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
