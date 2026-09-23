package com.webchat.service;

import com.webchat.dto.AttachmentVO;
import com.webchat.entity.Attachment;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 附件的完整链路：上传即回显 → 随消息绑定 → 会话或消息消失后清理。
 *
 * <p>业务侧删会话或删消息时<b>不需要</b>碰这里——附件的回收完全由
 * {@link AttachmentCleanupTask} 按派生条件推出来，谁先跑都不会漏。
 */
public interface AttachmentService {

    /**
     * 读取接口的路径前缀。{@code tb_attachment.url} 由它加对象键拼成。
     *
     * <p>用对象键而不是自增 id 是有意的：id 连续可枚举，而当前登录功能未开发、
     * {@code user_id} 恒为 1，归属校验对任何请求都成立——把 id 当边界等于没有边界。
     */
    String CONTENT_PATH = "/api/attachments/content/";

    /** 上传：校验类型与大小、落盘、落库，返回可直接回显的 VO */
    AttachmentVO upload(Long conversationId, String originalName, String contentType, byte[] content);

    /** 按存储键取回一行，带归属校验；不存在时抛 404 */
    Attachment requireByObjectKey(String objectKey);

    /** 读回附件字节。多模态组装与读取接口都用它 */
    byte[] readContent(Attachment attachment);

    /** 删除一个还没绑定消息的附件。幂等：已经没了就当删过了 */
    void delete(Long id);

    /**
     * 把一批附件绑到某条消息上。
     *
     * <p>任何一条不可用（不存在、不属于当前用户、已绑过、已过期）都会抛异常，
     * 由调用方的事务把整条链一起回滚——否则会留下一条既无文字也无附件的空消息。
     */
    void bindToMessage(List<Long> ids, Long messageId, Long conversationId);

    /** 批量取某几条消息的附件，按 messageId 分组。回显消息时用 */
    Map<Long, List<AttachmentVO>> listByMessageIds(Collection<Long> messageIds);

    /** 批量取附件原始行。组装多模态消息时需要 objectKey 与 mimeType */
    List<Attachment> findByMessageIds(Collection<Long> messageIds);

    /** 取某一条消息的附件。重新生成时按尾部用户消息取回 */
    List<Attachment> findByMessageId(Long messageId);
}
