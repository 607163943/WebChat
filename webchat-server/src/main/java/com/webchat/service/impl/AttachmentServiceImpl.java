package com.webchat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.webchat.ai.rag.DocumentIndexService;
import com.webchat.common.BizException;
import com.webchat.common.ResultCode;
import com.webchat.config.AttachmentProperties;
import com.webchat.config.CurrentUserProvider;
import com.webchat.dto.AttachmentVO;
import com.webchat.entity.Attachment;
import com.webchat.entity.Conversation;
import com.webchat.mapper.AttachmentMapper;
import com.webchat.mapper.ConversationMapper;
import com.webchat.service.AttachmentService;
import com.webchat.service.AttachmentTypePolicy;
import com.webchat.storage.AttachmentStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements AttachmentService {

    /** {@code tb_attachment.original_name} 是 varchar(255) */
    private static final int MAX_NAME_LENGTH = 255;

    private final AttachmentMapper attachmentMapper;
    /**
     * 这里刻意直接查会话表，而不是注入 {@code ConversationService}：
     * 那个 service 的 {@code listMessages} 要反过来用本类填附件回显，互相注入会构成循环依赖，
     * 而 Spring Boot 默认禁止循环引用、启动就会失败。归属校验的写法与
     * {@code ConversationServiceImpl#delete} 里的那次自查一致。
     */
    private final ConversationMapper conversationMapper;
    private final AttachmentStorage attachmentStorage;
    private final AttachmentTypePolicy typePolicy;
    private final AttachmentProperties properties;
    private final CurrentUserProvider currentUserProvider;
    /**
     * 上传后提交索引、删除时回收向量。
     *
     * <p>方向是「附件 → 检索」，反过来不成环：{@code DocumentIndexService} 依赖的是
     * {@link AttachmentStorage} 与 {@link AttachmentTypePolicy}，<b>不依赖本类</b>。
     * 那条边一旦加上去（哪怕只是想顺手查一下 mime），启动就会因循环引用直接失败。
     */
    private final DocumentIndexService documentIndexService;

    @Override
    public AttachmentVO upload(Long conversationId, String originalName, String contentType, byte[] content) {
        long userId = currentUserProvider.userId();
        requireNotEmpty(content);
        // 带了会话就必须是当前用户的，否则 404——不能让附件挂到别人的会话上
        if (conversationId != null) {
            requireOwnedConversation(conversationId, userId);
        }
        // 先定出权威类型，再按类型选大小上限：文本的阈值（1MB）比媒体（10MB）严得多，
        // 反过来的话就只能拿客户端声明的类型去猜该用哪个阈值了。
        // 代价是一个改名的超大「文本」会先被解码一次才因超限被拒——multipart 那 10MB 的上限
        // 兜住了最坏情况，这点开销不值得为它多绕一层
        String mimeType = typePolicy.resolveMimeType(contentType, content);
        requireWithinSizeLimit(content, mimeType);

        String objectKey = buildObjectKey(mimeType);
        attachmentStorage.store(objectKey, content);

        Attachment attachment = new Attachment();
        attachment.setUserId(userId);
        attachment.setConversationId(conversationId);
        attachment.setOriginalName(displayName(originalName));
        attachment.setObjectKey(objectKey);
        attachment.setUrl(CONTENT_PATH + objectKey);
        attachment.setMimeType(mimeType);
        attachment.setFileSize((long) content.length);
        attachment.setRetryCount(0);
        try {
            attachmentMapper.insert(attachment);
        } catch (RuntimeException e) {
            // 落库失败就把刚写下的对象收回去，免得留下一个没人认领的文件
            deleteObjectQuietly(objectKey);
            throw e;
        }
        // 提交向量化索引。异步，且只对文本附件生效——这里拿到响应时索引多半还没跑完，
        // 用户通常在打字，索引基本能在点发送之前结束
        documentIndexService.submit(attachment);
        return toVO(attachment);
    }

    @Override
    public Attachment requireByObjectKey(String objectKey) {
        Attachment attachment = attachmentMapper.selectOne(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getObjectKey, objectKey)
                .eq(Attachment::getUserId, currentUserProvider.userId()));
        if (attachment == null) {
            throw new BizException(ResultCode.NOT_FOUND, "附件不存在");
        }
        return attachment;
    }

    @Override
    public byte[] readContent(Attachment attachment) {
        return attachmentStorage.read(attachment.getObjectKey());
    }

    @Override
    public void delete(Long id) {
        Attachment attachment = attachmentMapper.selectOne(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getId, id)
                .eq(Attachment::getUserId, currentUserProvider.userId()));
        if (attachment == null) {
            // 幂等：已经没了就当删过了
            return;
        }
        if (attachment.getMessageId() != null) {
            throw new BizException(ResultCode.BAD_REQUEST, "附件已经随消息发出，不能删除");
        }
        // 先删对象再删行。反过来的话，对象删除失败就再也找不到那个键，成了永久泄漏
        attachmentStorage.delete(attachment.getObjectKey());
        attachmentMapper.deleteById(id);
        // 行删掉了，它的向量也不能留着：否则检索会把一个用户已经删掉的文件内容喂给模型
        documentIndexService.forget(id);
    }

    @Override
    public void bindToMessage(List<Long> ids, Long messageId, Long conversationId) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // 请求里可能有重复 id，比对的是去重后的数量
        int expected = new HashSet<>(ids).size();
        if (expected > properties.maxFilesPerMessage()) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "一条消息最多带 " + properties.maxFilesPerMessage() + " 个附件");
        }
        long userId = currentUserProvider.userId();
        List<Attachment> bindable = attachmentMapper.selectBindable(ids, userId, ttlSeconds());
        if (bindable.size() != expected) {
            throw new BizException(ResultCode.BAD_REQUEST, "有附件不可用（可能已过期或已被使用），请重新上传");
        }
        // 本轮附件全部发出去（不能悄悄丢掉用户刚传的文件），但总量要封顶：
        // 只限个数挡不住 5 个 10MB 的文件 base64 后变成 ~67MB 的请求体
        long totalBytes = bindable.stream().mapToLong(a -> a.getFileSize() == null ? 0L : a.getFileSize()).sum();
        long mediaLimit = properties.maxRequestMediaSize().toBytes();
        if (totalBytes > mediaLimit) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    "附件总大小超过 " + readableSize(mediaLimit) + "，请减少一些再发送");
        }
        // 上面那次查询已经挡掉了绝大多数情况，这里比对影响行数是最后一道：
        // 少绑一条却放过去，用户消息会变成一条既无文字也无附件的空消息
        int affected = attachmentMapper.bindToMessage(ids, messageId, conversationId, userId);
        if (affected != expected) {
            throw new BizException(ResultCode.BAD_REQUEST, "有附件在提交时被占用，请重试");
        }
    }

    @Override
    public Map<Long, List<AttachmentVO>> listByMessageIds(Collection<Long> messageIds) {
        return findByMessageIds(messageIds).stream()
                .collect(Collectors.groupingBy(Attachment::getMessageId,
                        Collectors.mapping(AttachmentServiceImpl::toVO, Collectors.toList())));
    }

    @Override
    public List<Attachment> findByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getUserId, currentUserProvider.userId())
                .in(Attachment::getMessageId, messageIds)
                // 按上传顺序，保证同一批附件的展示次序稳定
                .orderByAsc(Attachment::getId));
    }

    @Override
    public List<Attachment> findByMessageId(Long messageId) {
        return findByMessageIds(List.of(messageId));
    }

    @Override
    public List<Long> listTextAttachmentIds(Long conversationId) {
        return attachmentMapper.selectTextAttachmentIds(conversationId, currentUserProvider.userId());
    }

    /** 对象键：按天分目录，避免单目录文件过多；扩展名只能来自白名单表 */
    private String buildObjectKey(String mimeType) {
        LocalDate today = LocalDate.now();
        return "%04d/%02d/%02d/%s.%s".formatted(
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID(), typePolicy.extensionOf(mimeType));
    }

    /** 会话必须存在且属于当前用户，否则 404 */
    private void requireOwnedConversation(Long conversationId, long userId) {
        Conversation owned = conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId));
        if (owned == null) {
            throw new BizException(ResultCode.NOT_FOUND);
        }
    }

    /**
     * 按<b>权威类型</b>选大小上限。
     *
     * <p>文本的阈值比媒体严得多，理由是成本而非安全：文本要切分后逐段调 embedding，
     * 1MB 中文已约合 35 万 token，而模型的 TPM 是 100 万——一个满额文件就吃掉三分之一的
     * 每分钟配额。图片视频没有这个问题，它们是整块塞进一次请求的。
     */
    private void requireWithinSizeLimit(byte[] content, String mimeType) {
        long limit = limitFor(mimeType);
        if (content.length > limit) {
            throw new BizException(ResultCode.BAD_REQUEST,
                    (typePolicy.isText(mimeType) ? "文本文件" : "文件")
                            + "超过 " + readableSize(limit) + "，换一个小一点的");
        }
    }

    private long limitFor(String mimeType) {
        return typePolicy.isText(mimeType)
                ? properties.maxTextFileSize().toBytes()
                : properties.maxFileSize().toBytes();
    }

    /**
     * 空文件单独先判，且文案与「超限」分开。
     *
     * <p>若把它并进大小校验，图片路径上空内容会走到 {@code detect()} 那一步，拿到的提示会变成
     * 「不是可识别的图片或视频」——比「文件是空的」难懂得多。
     */
    private static void requireNotEmpty(byte[] content) {
        if (content.length == 0) {
            throw new BizException(ResultCode.BAD_REQUEST, "文件是空的，换一个再试");
        }
    }

    /**
     * 展示用的文件名。
     *
     * <p>只用于展示，但 {@code original_name} 是 NOT NULL 的 varchar(255)，
     * 超长或为空都会让插入失败，所以在这里收拾干净。个别客户端会把整个路径塞进来，只取末段。
     */
    private static String displayName(String originalName) {
        String name = originalName == null ? "" : originalName.strip();
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        if (name.isEmpty()) {
            name = "未命名文件";
        }
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private static String readableSize(long bytes) {
        long megabytes = bytes / (1024 * 1024);
        return megabytes > 0 && bytes % (1024 * 1024) == 0 ? megabytes + "MB" : bytes + " 字节";
    }

    /** 存活时长统一换算成秒交给 SQL，免得按小时取整把窗口悄悄改短 */
    private long ttlSeconds() {
        return properties.unboundTtl().toSeconds();
    }

    private void deleteObjectQuietly(String objectKey) {
        try {
            attachmentStorage.delete(objectKey);
        } catch (RuntimeException e) {
            // 本来就是在处理失败路径，回收不掉就交给日志，别盖住真正的异常
            log.warn("回滚附件对象失败，objectKey={}", objectKey, e);
        }
    }

    static AttachmentVO toVO(Attachment attachment) {
        return new AttachmentVO(attachment.getId(), attachment.getOriginalName(),
                attachment.getUrl(), attachment.getMimeType(), attachment.getFileSize());
    }
}
