package com.webchat.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.webchat.ai.rag.DocumentIndexService;
import com.webchat.common.BizException;
import com.webchat.config.AttachmentProperties;
import com.webchat.config.CurrentUserProvider;
import com.webchat.dto.AttachmentVO;
import com.webchat.entity.Attachment;
import com.webchat.mapper.AttachmentMapper;
import com.webchat.mapper.ConversationMapper;
import com.webchat.service.impl.AttachmentServiceImpl;
import com.webchat.storage.AttachmentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件上传 / 删除 / 绑定的单元测试。
 *
 * <p>{@link AttachmentTypePolicy} 用真实实现而不是 mock —— 白名单与文件头嗅探正是这条链路的安全边界，
 * 把它 mock 掉等于把要测的东西测没了。
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTests {

    private static final long USER_ID = 7L;
    private static final long CONVERSATION_ID = 1L;

    @Mock
    private AttachmentMapper attachmentMapper;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private AttachmentStorage attachmentStorage;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private DocumentIndexService documentIndexService;

    private final AttachmentTypePolicy typePolicy = new AttachmentTypePolicy();

    private AttachmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = serviceWith(DataSize.ofMegabytes(10), DataSize.ofMegabytes(20));
    }

    private AttachmentServiceImpl serviceWith(DataSize maxFileSize, DataSize maxRequestMediaSize) {
        return serviceWith(maxFileSize, maxRequestMediaSize, DataSize.ofMegabytes(1));
    }

    private AttachmentServiceImpl serviceWith(DataSize maxFileSize, DataSize maxRequestMediaSize,
                                              DataSize maxTextFileSize) {
        AttachmentProperties properties = new AttachmentProperties("./data/attachments", maxFileSize,
                maxTextFileSize, 5, maxRequestMediaSize, Duration.ofHours(24), Duration.ofMinutes(30), 5);
        return new AttachmentServiceImpl(attachmentMapper, conversationMapper, attachmentStorage,
                typePolicy, properties, currentUserProvider, documentIndexService);
    }

    // ---------------------------------------------------------------- 上传

    @Test
    @DisplayName("上传：落盘 + 落库，url 由对象键推导，插入前即可确定（不需要二次回写）")
    void uploadStoresObjectAndRow() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);

        AttachmentVO vo = service.upload(null, "照片.png", "image/png", png());

        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentStorage).store(anyString(), any(byte[].class));
        verify(attachmentMapper).insert(captor.capture());
        Attachment row = captor.getValue();

        assertThat(row.getObjectKey()).matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}\\.png");
        assertThat(row.getUrl()).isEqualTo("/api/attachments/content/" + row.getObjectKey());
        assertThat(row.getMimeType()).isEqualTo("image/png");
        assertThat(row.getFileSize()).isEqualTo((long) png().length);
        assertThat(row.getUserId()).isEqualTo(USER_ID);
        assertThat(row.getRetryCount()).isZero();
        assertThat(vo.originalName()).isEqualTo("照片.png");
    }

    @Test
    @DisplayName("原始文件名只用于展示：去掉路径、超长截断，不参与任何路径拼接")
    void sanitizesDisplayNameOnly() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);

        service.upload(null, "C:\\Users\\someone\\照片.png", "image/png", png());
        service.upload(null, "a".repeat(400) + ".png", "image/png", png());

        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(attachmentMapper, org.mockito.Mockito.times(2)).insert(captor.capture());

        assertThat(captor.getAllValues().get(0).getOriginalName()).isEqualTo("照片.png");
        assertThat(captor.getAllValues().get(1).getOriginalName()).hasSize(255);
    }

    @Test
    @DisplayName("超过单文件上限：400，且不落盘也不落库")
    void rejectsOversizeFile() {
        AttachmentServiceImpl tiny = serviceWith(DataSize.ofKilobytes(1), DataSize.ofMegabytes(20));
        byte[] oversize = new byte[2048];
        System.arraycopy(png(), 0, oversize, 0, png().length);

        assertThatThrownBy(() -> tiny.upload(null, "big.png", "image/png", oversize))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过");

        verify(attachmentStorage, never()).store(anyString(), any(byte[].class));
        verify(attachmentMapper, never()).insert(any(Attachment.class));
    }

    @Test
    @DisplayName("空文件直接拒绝")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> service.upload(null, "empty.png", "image/png", new byte[0]))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("空");
    }

    @Test
    @DisplayName("文本文件的上限比媒体严：同样 2KB，txt 被拒而图片照收")
    void appliesStricterLimitToText() {
        AttachmentServiceImpl strict = serviceWith(DataSize.ofMegabytes(10), DataSize.ofMegabytes(20),
                DataSize.ofKilobytes(1));
        // 两种类型各造一份同样大小、各自合法的内容，唯一的差别就只剩上限
        byte[] text = "x".repeat(2048).getBytes(StandardCharsets.UTF_8);
        byte[] image = new byte[2048];
        System.arraycopy(png(), 0, image, 0, png().length);

        assertThatThrownBy(() -> strict.upload(null, "长文.txt", "text/plain", text))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("文本文件超过");

        when(currentUserProvider.userId()).thenReturn(USER_ID);
        assertThat(strict.upload(null, "图.png", "image/png", image)).isNotNull();
    }

    @Test
    @DisplayName("上传成功后就提交向量化索引；媒体也照提交，类型过滤在索引服务里做")
    void submitsUploadedAttachmentForIndexing() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);

        service.upload(null, "纪要.txt", "text/plain", "会议纪要正文".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(documentIndexService).submit(captor.capture());
        assertThat(captor.getValue().getMimeType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("落库失败就没有索引可提交：别为一个不存在的行留下向量")
    void doesNotSubmitWhenInsertFails() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(attachmentMapper.insert(any(Attachment.class))).thenThrow(new RuntimeException("库挂了"));

        assertThatThrownBy(() -> service.upload(null, "纪要.txt", "text/plain",
                "会议纪要正文".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RuntimeException.class);

        verify(documentIndexService, never()).submit(any(Attachment.class));
    }

    @Test
    @DisplayName("带了不属于当前用户的会话：404，不落盘")
    void rejectsForeignConversation() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.upload(CONVERSATION_ID, "a.png", "image/png", png()))
                .isInstanceOf(BizException.class);

        verify(attachmentStorage, never()).store(anyString(), any(byte[].class));
    }

    @Test
    @DisplayName("落库失败时把刚写下的对象收回去，不留无人认领的文件")
    void rollsBackObjectWhenInsertFails() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(attachmentMapper.insert(any(Attachment.class))).thenThrow(new RuntimeException("库挂了"));

        assertThatThrownBy(() -> service.upload(null, "a.png", "image/png", png()))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(attachmentStorage).store(keyCaptor.capture(), any(byte[].class));
        verify(attachmentStorage).delete(keyCaptor.getValue());
    }

    // ---------------------------------------------------------------- 删除

    private Attachment existing(long id, Long messageId) {
        Attachment attachment = new Attachment();
        attachment.setId(id);
        attachment.setUserId(USER_ID);
        attachment.setMessageId(messageId);
        attachment.setObjectKey("2026/09/23/" + id + ".png");
        return attachment;
    }

    @Test
    @DisplayName("删除待绑定附件：先删对象再删行")
    void deleteRemovesObjectThenRow() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(attachmentMapper.selectOne(any(Wrapper.class))).thenReturn(existing(100L, null));

        service.delete(100L);

        verify(attachmentStorage).delete("2026/09/23/100.png");
        verify(attachmentMapper).deleteById(100L);
    }

    @Test
    @DisplayName("已经随消息发出的附件不能删：它不是「待绑定」状态了")
    void deleteRefusesBoundAttachment() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(attachmentMapper.selectOne(any(Wrapper.class))).thenReturn(existing(100L, 9L));

        assertThatThrownBy(() -> service.delete(100L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("随消息发出");

        verify(attachmentStorage, never()).delete(anyString());
    }

    @Test
    @DisplayName("删除不存在的附件是幂等的，不报错")
    void deleteIsIdempotent() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(attachmentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        service.delete(404L);

        verify(attachmentStorage, never()).delete(anyString());
    }

    // ---------------------------------------------------------------- 绑定

    @Test
    @DisplayName("绑定成功：影响行数与传入条数一致才放行")
    void bindsWhenAllAvailable() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(attachmentMapper.selectBindable(any(), eq(USER_ID), any(Long.class)))
                .thenReturn(List.of(existing(100L, null)));
        when(attachmentMapper.bindToMessage(any(), eq(9L), eq(CONVERSATION_ID), eq(USER_ID))).thenReturn(1);

        service.bindToMessage(List.of(100L), 9L, CONVERSATION_ID);

        verify(attachmentMapper).bindToMessage(List.of(100L), 9L, CONVERSATION_ID, USER_ID);
    }

    @Test
    @DisplayName("有附件不可用（过期 / 已被占用 / 不属于当前用户）：整批拒绝，让调用方回滚用户消息")
    void rejectsBindingWhenAnyUnavailable() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(attachmentMapper.selectBindable(any(), eq(USER_ID), any(Long.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.bindToMessage(List.of(100L, 101L), 9L, CONVERSATION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可用");

        verify(attachmentMapper, never()).bindToMessage(any(), any(), any(), any());
    }

    @Test
    @DisplayName("附件总大小超过媒体上限：拒绝。只限个数挡不住 5 个 10MB 的文件")
    void rejectsBindingWhenTotalTooLarge() {
        AttachmentServiceImpl small = serviceWith(DataSize.ofMegabytes(10), DataSize.ofKilobytes(1));
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        Attachment big = existing(100L, null);
        big.setFileSize(DataSize.ofKilobytes(2).toBytes());
        when(attachmentMapper.selectBindable(any(), eq(USER_ID), any(Long.class))).thenReturn(List.of(big));

        assertThatThrownBy(() -> small.bindToMessage(List.of(100L), 9L, CONVERSATION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("总大小");
    }

    @Test
    @DisplayName("超过单条消息的附件数量上限：400")
    void rejectsTooManyAttachments() {
        assertThatThrownBy(() -> service.bindToMessage(List.of(1L, 2L, 3L, 4L, 5L, 6L), 9L, CONVERSATION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最多");

        verify(attachmentMapper, never()).selectBindable(any(), any(), any(Long.class));
    }

    @Test
    @DisplayName("没有附件时绑定是空操作")
    void bindingNothingIsNoop() {
        service.bindToMessage(List.of(), 9L, CONVERSATION_ID);

        verify(attachmentMapper, never()).selectBindable(any(), any(), any(Long.class));
    }

    @Test
    @DisplayName("绑定失败要抛 400 而不是静默少绑——否则会留下一条没有内容的空消息")
    void throwsWhenAffectedRowsMismatch() {
        when(currentUserProvider.userId()).thenReturn(USER_ID);
        when(attachmentMapper.selectBindable(any(), eq(USER_ID), any(Long.class)))
                .thenReturn(List.of(existing(100L, null)));
        when(attachmentMapper.bindToMessage(any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.bindToMessage(List.of(100L), 9L, CONVERSATION_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("被占用");
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    }
}
