package com.webchat.ai;

import com.webchat.common.BizException;
import com.webchat.common.ResultCode;
import com.webchat.config.AttachmentProperties;
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
 * 库里的消息行 → 发给模型的消息：历史（含附件）与本轮提问的内容。
 *
 * <p>历史<b>每轮重新渲染</b>，不缓存中间结果：媒体预算要按「本轮提问占掉多少额度」重算，
 * 缓存下来就只能沿用上一轮挑好的那批。
 *
 * <p>渲染出来的两条产物去向不同：历史进 {@link ConversationMemoryStore}（每轮覆盖），
 * 本轮提问作为 {@code ChatAssistant} 的实参直接发出去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageAssembler {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_SYSTEM = "system";

    /** 白名单保证 mime_type 必然以它、{@code video/} 或 {@code text/} 开头，据此分派 */
    private static final String IMAGE_PREFIX = "image/";
    private static final String TEXT_PREFIX = "text/";

    private final AttachmentService attachmentService;
    private final AttachmentProperties attachmentProperties;

    /**
     * 渲染该会话的记忆：系统提示词打头，随后是最近若干条历史（媒体按预算挑）。
     *
     * <p>系统提示词由这里放，而不是让 {@code ChatAssistant} 用 {@code @SystemMessage} 声明：
     * 框架会把它追加在记忆已有内容<b>之后</b>，而 system 必须紧跟 user 才安全
     * （DashScope 的适配遇到「system 之后不是 user」会把那条消息静默丢掉）。
     *
     * @param history            库里该会话的全部消息（升序），不含本轮提问
     * @param currentAttachments 本轮附件，用来算还剩多少媒体额度
     */
    public List<ChatMessage> renderMemory(List<Message> history, List<Attachment> currentAttachments) {
        List<Message> recent = trimToRecent(history);
        Map<Long, List<Attachment>> media = pickHistoryMedia(recent, currentAttachments);

        List<ChatMessage> messages = new ArrayList<>(recent.size() + 1);
        messages.add(SystemMessage.from(Prompt.SYSTEM_PROMPT));
        for (Message message : recent) {
            messages.add(toChatMessage(message, media.getOrDefault(message.getId(), List.of())));
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
        List<Content> contents = contentsOf(message, attachments);
        if (contents.isEmpty()) {
            // 有附件却一个都没读出来（对象已被清理之类）
            throw new BizException(ResultCode.BAD_REQUEST, "附件内容已不可用，请重新上传");
        }
        return contents;
    }

    /** 正文 + 文本附件说明 + 挑中的媒体；一段都没有时返回空列表，由调用方决定是拦下还是将就 */
    private List<Content> contentsOf(Message message, List<Attachment> attachments) {
        List<Content> contents = new ArrayList<>();
        String text = message.getContent();
        if (text != null && !text.isBlank()) {
            contents.add(TextContent.from(text));
        }
        // 文本附件本身不作为多模态内容发出去（内容走检索），但必须留下一行说明它是哪个文件，
        // 否则「只带附件、没有文字」的那一轮会得到一份空的 contents
        List<Attachment> documents = attachments.stream().filter(ChatMessageAssembler::isDocument).toList();
        if (!documents.isEmpty()) {
            contents.add(TextContent.from(documentNote(documents)));
        }
        attachments.stream()
                .filter(attachment -> !isDocument(attachment))
                .map(this::toMediaContent)
                .flatMap(Optional::stream)
                .forEach(contents::add);
        return List.copyOf(contents);
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
            case ROLE_USER -> toUserMessage(message, attachments);
            case ROLE_ASSISTANT -> AiMessage.from(message.getContent());
            case ROLE_SYSTEM -> SystemMessage.from(message.getContent());
            default -> throw new IllegalStateException("未知的消息角色：" + message.getRole());
        };
    }

    /**
     * 历史里的用户消息。
     *
     * <p>与 {@link #renderQuestion} 的区别是<b>不拦空内容</b>：历史是既成事实，
     * 一条正文为空、附件又已被回收的旧消息不该把整轮对话变成 400——当初它就是那样发出去的。
     */
    private UserMessage toUserMessage(Message message, List<Attachment> attachments) {
        List<Content> contents = contentsOf(message, attachments);
        return contents.isEmpty()
                ? UserMessage.from("")
                : UserMessage.builder().contents(contents).build();
    }

    /**
     * 挑出历史里还要发给模型的附件。
     *
     * <p><b>媒体</b>（图片／视频）受预算约束：整轮请求的数量不超过单条消息的上限、字节数不超过配置的
     * 上限，本轮提问的附件优先占额度，剩下的从最近的历史消息往前补——最新那批正是用户刚发上去、
     * 语义上也最相关的，更早的只发文本。没有这道闸，20 条历史 × 5 个文件会把请求撑爆。
     *
     * <p><b>文本附件不受预算约束、也不占额度</b>：它的内容不进请求体（走检索），带上它只是为了在历史
     * 那条消息里渲染出一行「用户上传了文件：x.txt」。这一行不能省——那条消息的正文可能是空串
     * （只传文件没打字），少了它就成了一条内容为空的用户消息。
     */
    private Map<Long, List<Attachment>> pickHistoryMedia(List<Message> recentHistory,
                                                         List<Attachment> currentAttachments) {
        if (recentHistory.isEmpty()) {
            return Map.of();
        }
        List<Attachment> currentMedia = currentAttachments.stream().filter(a -> !isDocument(a)).toList();
        long remainingCount = attachmentProperties.maxFilesPerMessage() - currentMedia.size();
        long remainingBytes = attachmentProperties.maxRequestMediaSize().toBytes() - totalBytes(currentMedia);

        List<Long> historyIds = recentHistory.stream().map(Message::getId).toList();
        Map<Long, List<Attachment>> byMessage = attachmentService.findByMessageIds(historyIds).stream()
                .collect(Collectors.groupingBy(Attachment::getMessageId,
                        LinkedHashMap::new, Collectors.toList()));

        Map<Long, List<Attachment>> picked = new LinkedHashMap<>();
        long count = 0;
        long bytes = 0;
        // 从最新的一条往前取
        for (int index = recentHistory.size() - 1; index >= 0; index--) {
            Message message = recentHistory.get(index);
            if (!ROLE_USER.equals(message.getRole())) {
                continue;
            }
            for (Attachment attachment : byMessage.getOrDefault(message.getId(), List.of())) {
                if (isDocument(attachment)) {
                    picked.computeIfAbsent(message.getId(), key -> new ArrayList<>()).add(attachment);
                    continue;
                }
                if (count >= remainingCount || bytes + sizeOf(attachment) > remainingBytes) {
                    // 媒体额度用完就不再带更早的媒体，但循环要继续——后面的文本附件还得收进来说明文件
                    continue;
                }
                picked.computeIfAbsent(message.getId(), key -> new ArrayList<>()).add(attachment);
                count++;
                bytes += sizeOf(attachment);
            }
        }
        return picked;
    }

    /**
     * 组装媒体内容。带附件时走多模态：文字与媒体各自是一个 content，媒体一律用 base64。
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
            // 历史附件读不出来就跳过这一段，不值得让整轮对话失败
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

    private static List<Message> trimToRecent(List<Message> history) {
        int size = history.size();
        if (size <= Prompt.MAX_HISTORY_MESSAGES) {
            return history;
        }
        // 附件预算在这个裁剪结果上算，否则会为随后被裁掉的消息白读一遍磁盘
        return history.subList(size - Prompt.MAX_HISTORY_MESSAGES, size);
    }

    private static long totalBytes(List<Attachment> attachments) {
        return attachments.stream().mapToLong(ChatMessageAssembler::sizeOf).sum();
    }

    private static long sizeOf(Attachment attachment) {
        return attachment.getFileSize() == null ? 0L : attachment.getFileSize();
    }
}
