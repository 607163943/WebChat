package com.webchat.ai;

/**
 * 提示词与相关上限常量。集中放一处，便于日后调参。
 */
public final class Prompt {

    private Prompt() {
    }

    /**
     * 系统提示词。当前阶段固定为代码常量，不落库。
     *
     * <p>末段是联网搜索的用法说明。工具本身由 MCP 服务提供、自带描述，但<b>什么时候该用</b>得由
     * 这里说清：不写的话模型要么守着记忆里的旧知识硬答，要么反过来凡事都先搜一遍——后者每次
     * 要多花几秒，而多数问题（写代码、算数、改文案）根本不需要联网。
     */
    public static final String SYSTEM_PROMPT = """
            你是 WebChat 的 AI 助手，请用简洁、准确的中文回答问题。
            回答使用 Markdown 格式：代码放进带语言标注的代码块，适当使用列表与小标题。
            你有联网搜索工具：需要最新消息、版本号、价格、时事等可能已经变化的信息，或你对答案
            没有把握时，先用 web_search_exa 搜索、必要时用 web_fetch_exa 打开具体网页再回答；
            引用了搜索结果时给出可点击的来源链接。稳定的常识、写作与代码问题不必搜索。""";

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
     *
     * <p>只有<b>真的</b>还在处理中才用它。判据是「已提交、尚未落终态」，而不是「向量库里没有」——
     * 后者还包括索引失败、空文件、以及重启后向量被清空，对着它们说「稍后再问」是把用户引向
     * 一个永远不会到来的稍后（见 {@link #KNOWLEDGE_EMPTY_FILE_HINT} 与 {@link #KNOWLEDGE_LOST_HINT}）。
     */
    public static final String KNOWLEDGE_PENDING_HINT =
            "（用户上传的文档仍在处理中，其内容暂时无法检索）";

    /**
     * 附件是空文件时的提示，参数为文件名。
     *
     * <p>空文件不进向量库（在上传后、入队前就判掉了），所以它永远检索不出东西。这条要说清
     * 「是空的」，而不是「没检索到」——后者会让模型以为文件里有内容、只是没匹配上。
     */
    public static final String KNOWLEDGE_EMPTY_FILE_HINT =
            "（用户上传的文件「%s」是空文件，没有任何可检索的内容）";

    /**
     * 附件处理失败时的提示，参数为文件名。
     *
     * <p>正常路径上走不到这里：索引失败的附件会在前端就被移除，压根不会随消息发出去。
     * 它兜的是竞态——附件在轮询发现失败之前就被发出去了，此时它已经在消息里，模型手上也有
     * 「用户上传了文件：x」那一行，必须告诉它内容其实拿不到。
     */
    public static final String KNOWLEDGE_FAILED_FILE_HINT =
            "（用户上传的文件「%s」处理失败，其内容当前不可用）";

    /**
     * 附件是本进程启动之前上传的：向量库纯内存，重启即清空。
     *
     * <p>不带文件名——重启后内存里连「这个 id 存在过」都没有了，只有 id 列表，取不到名字。
     * 末句给出唯一的恢复路径：重新上传。
     */
    public static final String KNOWLEDGE_LOST_HINT =
            "（用户此前上传的文件内容已不可用：服务重启后向量索引已清空，可提示用户重新上传）";

    /** 上面几条要列的文件太多时的省略，参数为省略掉的文件数 */
    public static final String KNOWLEDGE_MORE_FILES_HINT = "（另有 %d 个文件同样不可用）";

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

    /**
     * 一轮提问里最多来回几次工具调用（联网搜索）。
     *
     * <p>不设上限时模型可能反复搜同一件事，而每次搜索都要几秒——用户那边就是一段没有尽头的
     * 「正在搜索…」。到顶之后框架不再执行工具，模型只能就手上的信息作答。
     */
    public static final int MAX_TOOL_CALLING_ROUND_TRIPS = 4;
}
