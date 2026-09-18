package com.webchat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.webchat.entity.Conversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 刷新会话的最后活跃时间。
     *
     * <p>显式刷新而不依赖 {@code ON UPDATE CURRENT_TIMESTAMP}，并顺带承担归属校验：
     * 返回 0 表示该会话不属于当前用户。
     */
    @Update("UPDATE tb_conversation SET update_time = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int touch(@Param("id") Long id, @Param("userId") Long userId);

    /** 写入模型生成的会话标题（仅新会话首条消息时调用一次） */
    @Update("UPDATE tb_conversation SET title = #{title} WHERE id = #{id} AND user_id = #{userId}")
    int updateTitle(@Param("id") Long id, @Param("userId") Long userId, @Param("title") String title);
}
