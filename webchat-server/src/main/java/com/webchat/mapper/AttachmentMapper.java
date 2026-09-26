package com.webchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webchat.entity.Attachment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AttachmentMapper extends BaseMapper<Attachment> {

    /**
     * 把一批待绑定附件挂到某条消息上。
     *
     * <p>{@code user_id} 写进 WHERE 兼作归属校验，{@code message_id IS NULL} 挡住重复绑定——
     * 返回的影响行数因此就是「真正绑成功的条数」，调用方<b>必须</b>拿它和传入的 id 数量比对：
     * 少绑一条就说明有 id 失效（重发、已被别的消息绑走），此时用户消息已经落库，
     * 不报错就会留下一条既无文字也无附件的空消息。
     *
     * <p>{@code IN} 必须用 {@code <foreach>}：注解 SQL 里直接写 {@code IN (#{ids})} 会把整个
     * List 当成一个参数、只产生一个 {@code ?}，MySQL 侧直接报错。
     */
    @Update("""
            <script>
            UPDATE tb_attachment
            SET message_id = #{messageId}, conversation_id = #{conversationId}
            WHERE user_id = #{userId}
              AND message_id IS NULL
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int bindToMessage(@Param("ids") List<Long> ids,
                      @Param("messageId") Long messageId,
                      @Param("conversationId") Long conversationId,
                      @Param("userId") Long userId);

    /**
     * 挑出真正可以绑定的那几条：属于当前用户、还没绑过、且未超过存活时长。
     *
     * <p>过期即不可用是《数据库表设计》明确要求的：清理任务可能已经删掉了对象、只是行还在，
     * 此时若放它绑上去，消息里就会回显出一张坏图。过期就让用户重传。
     */
    @Select("""
            <script>
            SELECT * FROM tb_attachment
            WHERE user_id = #{userId}
              AND message_id IS NULL
              AND create_time &gt; NOW() - INTERVAL #{ttlSeconds} SECOND
              AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Attachment> selectBindable(@Param("ids") List<Long> ids,
                                    @Param("userId") Long userId,
                                    @Param("ttlSeconds") long ttlSeconds);

    /**
     * 清理候选：命中《数据库表设计》判定表三条判据中任意一条的行。
     *
     * <p>判据 1 —— 未绑定且已超过存活时长（走 idx_message_create_time）；
     * 判据 2 —— 所属消息已不存在（覆盖「删会话」与「重新生成」删掉助手回复）；
     * 判据 3 —— 有会话归属但未绑定，而该会话已不存在（覆盖草稿态上传后又删了会话）。
     *
     * <p>时效比较刻意放在 SQL 里用 {@code NOW()}：连接串把会话时区钉在 Asia/Shanghai，
     * 而 Java 侧 {@code LocalDateTime.now()} 跟 JVM 默认时区走，两者不一致时窗口会静默偏移。
     */
    @Select("""
            SELECT * FROM tb_attachment a
            WHERE (a.message_id IS NULL AND a.create_time < NOW() - INTERVAL #{ttlSeconds} SECOND)
               OR (a.message_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM tb_message m WHERE m.id = a.message_id))
               OR (a.message_id IS NULL
                   AND a.conversation_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM tb_conversation c WHERE c.id = a.conversation_id))
            """)
    List<Attachment> selectCleanupCandidates(@Param("ttlSeconds") long ttlSeconds);

    /** 清理失败时累加观测计数 */
    @Update("UPDATE tb_attachment SET retry_count = retry_count + 1 WHERE id = #{id}")
    int increaseRetryCount(@Param("id") Long id);

    /**
     * 某会话内全部文本附件的 id，供文档检索按它过滤向量库。
     *
     * <p>只按顶层类型取 {@code text/}：图片与视频是 base64 塞进多模态消息的，压根不在向量库里，
     * 混进过滤条件只会白白拉长 {@code IN} 列表。用 {@code LIKE 'text/%'} 而不是写死
     * {@code text/plain}，是为了日后加 md / csv 时不必回来改这里（白名单先行，这条自然跟上）。
     */
    @Select("""
            SELECT id FROM tb_attachment
            WHERE user_id = #{userId}
              AND conversation_id = #{conversationId}
              AND mime_type LIKE 'text/%'
            ORDER BY id
            """)
    List<Long> selectTextAttachmentIds(@Param("conversationId") Long conversationId,
                                       @Param("userId") Long userId);
}
