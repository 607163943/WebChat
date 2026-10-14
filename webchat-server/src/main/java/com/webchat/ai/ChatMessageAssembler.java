package com.webchat.ai;

import com.webchat.common.BizException;
import com.webchat.common.ResultCode;
import com.webchat.constant.MessageRoleConstant;
import com.webchat.entity.Attachment;
import com.webchat.entity.Message;
import com.webchat.service.AttachmentService;
import com.webchat.storage.AttachmentStorageException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 库里的消息行 → 发给模型的消息：历史与本轮提问的内容。
 *
 * <p><b>媒体（图片／视频）只跟着本轮提问走，历史里一概不带 base64</b>：历史每轮都要整份重发，
 * 带上媒体就等于此后每一轮都把此前所有图片的 base64 再传一次，成本随轮次线性上涨。
 * 模型对早先图片的了解改由会话记忆承担——它当时回复里对画面的描述就在历史文本中
 * （系统提示词要求它收到图片时先说明看到了什么）。历史里只留文本：消息正文，
 * 以及每个附件一行说明（「用户上传了图片：x.png」这类，不读文件、不占额度）。
 * 那一行不是可有可无的：只发附件不打字的那一轮，正文是空串，少了它就是一条空的用户消息。
 *
 * <p>渲染出来的两条产物去向不同：历史进 {@link ConversationMemoryStore}（每轮覆盖），
 * 本轮提问作为 {@code ChatAssistant} 的实参直接发出去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageAssembler {

    /** 白名单保证 mime_type 必然以它、{@code video/} 或 {@code text/} 开头，据此分派 */
    private static final String IMAGE_PREFIX = "image/";
    private static final String TEXT_PREFIX = "text/";

    private final AttachmentService attachmentService;

    /**
     * 渲染该会话的记忆：系统提示词打头，随后是最近若干条历史（只含文本）。
     *
     * <p>系统提示词由这里放，而不是让 {@code ChatAssistant} 用 {@code @SystemMessage} 声明：
     * 框架会把它追加在记忆已有内容<b>之后</b>，而 system 必须紧跟 user 才安全
     * （DashScope 的适配遇到「system 之后不是 user」会把那条消息静默丢掉）。
     *
     * @param allHistory 库里该会话的全部消息（升序），不含本轮提问
     */
    public List<ChatMessage> renderMemory(List<Message> allHistory) {
        List<Message> recent = trimToRecent(allHistory);
        Map<Long, List<Attachment>> attachments = pickHistoryAttachments(recent);

        List<ChatMessage> messages = new ArrayList<>(recent.size() + 1);
        messages.add(SystemMessage.from(Prompt.SYSTEM_PROMPT));
        for (Message message : recent) {
            messages.add(toChatMessage(message, attachments.getOrDefault(message.getId(), List.of())));
        }
        return List.copyOf(messages);
    }

    /**
     * 渲染本轮提问的内容，顺序是：用户正文 → 文本附件说明 → 媒体。
     *
     * <p>检索到的资料<i>不</i>在这里——它要等 embedding 返回才知道，由
     * {@link ChatStreamService#stream} 在调用模型前用 {@link #withKnowledge} 拼到最前面。
     *
     * @throws BizException 内容为空时抛出。留着它会让 {@code UserMessage} 自己抛
     *                      IllegalArgumentException，变成一次 500；而此时用户消息已经落库，
     *                      异常会把整个 prepare 事务回滚，空消息不会留下
     */
    public List<Content> renderQuestion(Message message, List<Attachment> attachments) {
        List<Content> contents = new ArrayList<>(textContentsOf(message, attachments));
        attachments.stream()
                .filter(attachment -> !isDocument(attachment))
                .map(this::toMediaContent)
                .flatMap(Optional::stream)
                .forEach(contents::add);
        if (contents.isEmpty()) {
            // 有附件却一个都没读出来（对象已被清理之类）
            throw new BizException(ResultCode.BAD_REQUEST, "附件内容已不可用，请重新上传");
        }
        return List.copyOf(contents);
    }

    /**
     * 正文 + 文本附件说明：本轮提问与历史共用的那部分，不含媒体本身。
     *
     * <p>文本附件在任何地方都只有这一行说明（内容走检索）。媒体则看用在哪儿：本轮提问里它由
     * {@link #renderQuestion} 作为 base64 追加在后面，历史里由 {@link #toHistoryUserMessage}
     * 补一行字。两边都得有东西，否则「只带附件、没有文字」的那一轮会得到一份空的内容。
     */
    private List<Content> textContentsOf(Message message, List<Attachment> attachments) {
        List<Content> contents = new ArrayList<>();
        String text = message.getContent();
        if (text != null && !text.isBlank()) {
            contents.add(TextContent.from(text));
        }
        List<Attachment> documents = attachments.stream().filter(ChatMessageAssembler::isDocument).toList();
        if (!documents.isEmpty()) {
            contents.add(TextContent.from(documentNote(documents)));
        }
        return contents;
    }

    /**
     * 把检索到的资料拼到本轮提问的最前面。
     *
     * <p>资料<b>不作为单独一条消息</b>：DashScope 的适配在清洗消息时，遇到「system 之后不是 user」
     * 会把那条消息静默丢掉，不报错也不抛异常——RAG 会彻底失效却查不出原因。放在提问的第一段文本里，
     * 既紧挨着它要服务的问题，也不会改变消息的角色序列。
     *
     * <p>它会被框架一并写进记忆（记忆里存的就是这一条提问），但记忆每轮重建，
     * 资料不会跟着带到下一轮；历史轮次渲染时也不会注入资料。
     *
     * @param knowledge 检索到的片段正文；没有资料时传空串，此时原样返回
     */
    public static List<Content> withKnowledge(List<Content> question, String knowledge) {
        if (knowledge == null || knowledge.isBlank()) {
            return question;
        }
        List<Content> contents = new ArrayList<>(question.size() + 1);
        contents.add(TextContent.from(knowledge));
        contents.addAll(question);
        return List.copyOf(contents);
    }

    private ChatMessage toChatMessage(Message message, List<Attachment> attachments) {
        return switch (message.getRole()) {
            // 历史轮次不注入资料：检索结果只对本轮提问有意义，混进历史反而会重复占篇幅
            case MessageRoleConstant.ROLE_USER -> toHistoryUserMessage(message, attachments);
            case MessageRoleConstant.ROLE_ASSISTANT -> AiMessage.from(message.getContent());
            case MessageRoleConstant.ROLE_SYSTEM -> SystemMessage.from(message.getContent());
            default -> throw new IllegalStateException("未知的消息角色：" + message.getRole());
        };
    }

    /**
     * 历史里的用户消息：正文 + 每个附件一行的说明，<b>不带媒体本身</b>（原因见类注释）。
     *
     * <p>媒体那一行（「用户上传了图片：x.png」）不能省：只发图片不打字的那一轮，正文是空串，
     * 少了它就是一条空的用户消息——而 {@code qwen3.8-max} 走的是多模态端点，空文本会被原样发出去。
     * 有了这一行，模型也知道那条消息配过图，与它自己当时写下的描述互相印证。
     *
     * <p>与 {@link #renderQuestion} 的区别之一是<b>不拦空内容</b>：历史是既成事实，
     * 一条正文为空、附件又已被回收的旧消息不该把整轮对话变成 400——当初它就是那样发出去的。
     */
    private UserMessage toHistoryUserMessage(Message message, List<Attachment> attachments) {
        List<Content> contents = new ArrayList<>(textContentsOf(message, attachments));
        List<Attachment> media = attachments.stream().filter(attachment -> !isDocument(attachment)).toList();
        if (!media.isEmpty()) {
            contents.add(TextContent.from(mediaNote(media)));
        }
        return contents.isEmpty()
                ? UserMessage.from("")
                : UserMessage.builder().contents(contents).build();
    }

    /**
     * 取出历史消息的附件行，按消息分组——<b>只为了渲染说明行</b>，不读文件、不做预算。
     *
     * <p>于是「本轮优先、从最近往前补」那套额度分配整体消失了：媒体不再重发，本轮附件一律全发，
     * 个数与总量在绑定那一步就已经把关（见 {@code AttachmentService#bindToMessage}）。
     */
    private Map<Long, List<Attachment>> pickHistoryAttachments(List<Message> recentHistory) {
        List<Long> historyIds = recentHistory.stream().map(Message::getId).toList();
        return attachmentService.findByMessageIds(historyIds).stream()
                .collect(Collectors.groupingBy(Attachment::getMessageId,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 组装媒体内容，<b>只用于本轮提问</b>。带附件时走多模态：文字与媒体各自是一个 content，
     * 媒体一律用 base64。
     *
     * <p>用 base64 而不是 URL，是因为开发环境的后端跑在内网（192.168.150.101），
     * 模型侧根本拉不到那个地址；LangChain4j 的 DashScope 适配会把 base64 拼成
     * {@code data:<mime>;base64,<数据>} 再发出去。
     *
     * <p>这里不需要显式打开什么「多模态开关」——DashScope 那个模型适配是按模型名判断的，
     * {@code qwen3.8-max} 的版本号已经让它默认走多模态分支。
     *
     * <p>调用方保证传进来的都是媒体：白名单里的文本类型在 {@link #isDocument} 那里就被拦下了，
     * 不拦的话它会被当成视频塞进 {@code VideoContent}。
     */
    private Optional<Content> toMediaContent(Attachment attachment) {
        try {
            String base64 = Base64.getEncoder().encodeToString(attachmentService.readContent(attachment));
            String mimeType = attachment.getMimeType();
            // 走到这里的只剩图片与视频两类，顶层类型就是这一处要的分派依据。
            // 工厂方法两个参数的顺序都是 (base64Data, mimeType)，且 mimeType 不能为空——
            // DashScope 适配据此拼成 data:<mime>;base64,<数据>
            return Optional.of(mimeType.startsWith(IMAGE_PREFIX)
                    ? ImageContent.from(base64, mimeType)
                    : VideoContent.from(base64, mimeType));
        } catch (AttachmentStorageException e) {
            // 附件读不出来就跳过这一段，不值得让整轮对话失败
            log.warn("附件内容读取失败，本轮跳过：id={}, objectKey={}",
                    attachment.getId(), attachment.getObjectKey(), e);
            return Optional.empty();
        }
    }

    /** 该附件是否走检索（文本），而不是作为媒体塞进请求体 */
    private static boolean isDocument(Attachment attachment) {
        String mimeType = attachment.getMimeType();
        return mimeType != null && mimeType.startsWith(TEXT_PREFIX);
    }

    private static String documentNote(List<Attachment> documents) {
        return documents.stream()
                .map(document -> "（用户上传了文件：" + document.getOriginalName() + "）")
                .collect(Collectors.joining());
    }

    /** 历史里媒体的说明行。只报类型与文件名，内容一概不带——图片与视频本身就是被去掉的那部分 */
    private static String mediaNote(List<Attachment> media) {
        return media.stream()
                .map(attachment -> "（用户上传了" + mediaKindOf(attachment) + "："
                        + attachment.getOriginalName() + "）")
                .collect(Collectors.joining());
    }

    /** 白名单下媒体只有图片与视频两类，顶层类型即可分辨 */
    private static String mediaKindOf(Attachment attachment) {
        String mimeType = attachment.getMimeType();
        return mimeType != null && mimeType.startsWith(IMAGE_PREFIX) ? "图片" : "视频";
    }

    /**
     * 获取最近的历史消息集合
     * @param allHistory 库里该会话的全部消息（升序），不含本轮提问
     * @return 最近的历史消息集合
     */
    private static List<Message> trimToRecent(List<Message> allHistory) {
        int size = allHistory.size();
        if (size <= Prompt.MAX_HISTORY_MESSAGES) {
            return allHistory;
        }
        // 附件说明只在这个裁剪结果上取，否则会为随后被裁掉的消息白查一遍库
        return allHistory.subList(size - Prompt.MAX_HISTORY_MESSAGES, size);
    }
}
