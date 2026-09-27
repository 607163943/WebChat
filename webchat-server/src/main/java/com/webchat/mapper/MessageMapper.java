package com.webchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webchat.entity.Message;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 原地覆盖一条助手回复（重新生成用）。
     *
     * <p>显式列出 SET 的字段，不走 {@code updateById}：后者的「null 字段不更新」是全局
     * 字段策略给的隐式契约，一旦有人改了策略，这条 UPDATE 会把 role、create_time 一起写成 NULL。
     * 同理也不该动 {@code create_time}——重新生成不该改变这条消息的位置与身份。
     *
     * <p>WHERE 里带 conversation_id，与 {@link ConversationMapper#touch} 同一套路，顺带承担归属校验：
     * 返回 0 表示这一行不属于该会话（或已被删除）。
     */
    @Update("UPDATE tb_message SET content = #{content}, status = #{status} "
            + "WHERE id = #{id} AND conversation_id = #{conversationId}")
    int updateReply(@Param("id") Long id, @Param("conversationId") Long conversationId,
                    @Param("content") String content, @Param("status") String status);
}
