package com.webchat.service;

import com.webchat.config.AttachmentProperties;
import com.webchat.entity.Attachment;
import com.webchat.mapper.AttachmentMapper;
import com.webchat.storage.AttachmentStorage;
import com.webchat.storage.AttachmentStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件清理的单元测试。
 *
 * <p>钉住两条最容易搞反的约定：<b>先删对象、成功才删行</b>（反过来就再也找不到那个键），
 * 以及删对象失败时<b>不能删行</b>——否则对象就永久泄漏了。
 */
@ExtendWith(MockitoExtension.class)
class AttachmentCleanupTaskTests {

    @Mock
    private AttachmentMapper attachmentMapper;
    @Mock
    private AttachmentStorage attachmentStorage;

    private AttachmentCleanupTask task;

    @BeforeEach
    void setUp() {
        AttachmentProperties properties = new AttachmentProperties("./data/attachments",
                DataSize.ofMegabytes(10), 5, DataSize.ofMegabytes(20),
                Duration.ofHours(24), Duration.ofMinutes(30), 5);
        task = new AttachmentCleanupTask(attachmentMapper, attachmentStorage, properties);
    }

    private static Attachment candidate(long id, int retryCount) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setObjectKey("2026/09/23/" + id + ".png");
        attachment.setRetryCount(retryCount);
        return attachment;
    }

    @Test
    @DisplayName("命中判据的候选：删对象成功后删行")
    void deletesObjectThenRow() {
        when(attachmentMapper.selectCleanupCandidates(anyLong())).thenReturn(List.of(candidate(100L, 0)));

        assertThat(task.runOnce()).isEqualTo(1);

        verify(attachmentStorage).delete("2026/09/23/100.png");
        verify(attachmentMapper).deleteById(100L);
    }

    @Test
    @DisplayName("删对象失败：只累加 retry_count，行留着等下一轮，绝不先删行")
    void keepsRowWhenObjectRemovalFails() {
        when(attachmentMapper.selectCleanupCandidates(anyLong())).thenReturn(List.of(candidate(100L, 0)));
        doThrow(new AttachmentStorageException("IO 挂了", null)).when(attachmentStorage).delete(anyString());

        assertThat(task.runOnce()).isZero();

        verify(attachmentMapper).increaseRetryCount(100L);
        verify(attachmentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("对象已不存在也算成功——storage 的 delete 是幂等的，行必须被收走")
    void treatsMissingObjectAsSuccess() {
        // LocalAttachmentStorage 内部把「文件不存在」吞掉，这里用不抛异常来模拟同一语义
        when(attachmentMapper.selectCleanupCandidates(anyLong())).thenReturn(List.of(candidate(100L, 3)));

        assertThat(task.runOnce()).isEqualTo(1);

        verify(attachmentMapper).deleteById(100L);
        verify(attachmentMapper, never()).increaseRetryCount(anyLong());
    }

    @Test
    @DisplayName("一条失败不影响同一轮的其余候选")
    void continuesWithRemainingCandidates() {
        when(attachmentMapper.selectCleanupCandidates(anyLong()))
                .thenReturn(List.of(candidate(100L, 0), candidate(101L, 0)));
        doThrow(new AttachmentStorageException("这条坏了", null))
                .when(attachmentStorage).delete("2026/09/23/100.png");

        assertThat(task.runOnce()).isEqualTo(1);

        verify(attachmentMapper).increaseRetryCount(100L);
        verify(attachmentMapper).deleteById(101L);
    }

    @Test
    @DisplayName("没有候选时什么都不做")
    void doesNothingWhenNoCandidates() {
        when(attachmentMapper.selectCleanupCandidates(anyLong())).thenReturn(List.of());

        assertThat(task.runOnce()).isZero();

        verify(attachmentStorage, never()).delete(anyString());
        verify(attachmentMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("存活时长按秒传给 SQL，避免按小时取整把窗口改短")
    void passesTtlInSeconds() {
        when(attachmentMapper.selectCleanupCandidates(anyLong())).thenReturn(List.of());

        task.runOnce();

        verify(attachmentMapper).selectCleanupCandidates(Duration.ofHours(24).toSeconds());
        verify(attachmentMapper, times(1)).selectCleanupCandidates(anyLong());
    }
}
