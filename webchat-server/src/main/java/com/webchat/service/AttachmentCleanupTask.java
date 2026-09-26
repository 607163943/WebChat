package com.webchat.service;

import com.webchat.ai.rag.DocumentIndexService;
import com.webchat.config.AttachmentProperties;
import com.webchat.entity.Attachment;
import com.webchat.mapper.AttachmentMapper;
import com.webchat.storage.AttachmentStorage;
import com.webchat.storage.AttachmentStorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回收没有归属的附件对象。
 *
 * <p>业务侧删会话或删消息时<b>完全不碰附件表</b>——「该不该删」只由本表三列加两张表的存在性
 * 推出来（判据见 {@link AttachmentMapper#selectCleanupCandidates}），因此判定只集中在这一处，
 * 谁先跑都不会漏。
 *
 * <p>每轮两步：先删对象，成功才删行；失败则 {@code retry_count} 加一、留在表里等下一轮
 * （判据依旧命中，不需要额外记录状态）。这一步是幂等的：删除一个已不存在的对象同样算成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentCleanupTask {

    /**
     * 首次执行也等一个间隔，而不是启动就跑。
     *
     * <p>两个作用：启动阶段不额外压数据库；{@code @SpringBootTest} 这类刻意不依赖数据库的测试
     * 不会因为兜里揣着一个马上要查库的定时任务而失败。
     */
    private static final String INTERVAL = "${webchat.attachment.cleanup-interval:30m}";

    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorage attachmentStorage;
    private final AttachmentProperties properties;
    /**
     * 行删掉之后回收它的向量。
     *
     * <p>这里覆盖的是「未绑定就过期」「所属消息已不存在」这些判据——其中包括「上传了 txt 却没发出去、
     * 或者发了之后把会话删了」。少了这一步，这些附件的向量会一直留在内存里，既占空间，
     * 也会让检索把一个已经不存在的文件的内容喂给模型。
     */
    private final DocumentIndexService documentIndexService;

    @Scheduled(fixedDelayString = INTERVAL, initialDelayString = INTERVAL)
    public void cleanup() {
        // 整个方法体包一层：定时任务抛出的异常没人接，也不该让它把调度线程带下去
        try {
            int removed = runOnce();
            if (removed > 0) {
                log.info("附件清理完成，删除 {} 个对象", removed);
            }
        } catch (Exception e) {
            log.warn("附件清理本轮失败，等下一轮重试", e);
        }
    }

    /**
     * 跑一轮，返回真正删掉的行数。
     *
     * <p>包级可见，便于脱离调度器单独做单元测试。
     */
    int runOnce() {
        List<Attachment> candidates = attachmentMapper.selectCleanupCandidates(properties.unboundTtl().toSeconds());
        int removed = 0;
        for (Attachment candidate : candidates) {
            if (removeObjectThenRow(candidate)) {
                removed++;
            }
        }
        return removed;
    }

    /** @return 该行是否已被删除；false 表示对象没删掉、留待下一轮 */
    private boolean removeObjectThenRow(Attachment attachment) {
        try {
            // 幂等：对象本就不存在也算成功（「删对象成功、删行前进程挂掉」正是这种情形）
            attachmentStorage.delete(attachment.getObjectKey());
        } catch (AttachmentStorageException e) {
            int retries = (attachment.getRetryCount() == null ? 0 : attachment.getRetryCount()) + 1;
            attachmentMapper.increaseRetryCount(attachment.getId());
            if (retries >= properties.retryAlertThreshold()) {
                // 只告警、不自动降级成「放弃」——静默放弃就是静默的存储泄漏
                log.error("附件对象反复删除失败，需要人工介入：id={}, objectKey={}, retryCount={}",
                        attachment.getId(), attachment.getObjectKey(), retries, e);
            } else {
                log.warn("删除附件对象失败，下一轮重试：id={}, objectKey={}",
                        attachment.getId(), attachment.getObjectKey(), e);
            }
            return false;
        }
        attachmentMapper.deleteById(attachment.getId());
        documentIndexService.forget(attachment.getId());
        return true;
    }
}
