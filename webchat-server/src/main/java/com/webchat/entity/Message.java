package com.webchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表：一行代表会话中的一条消息。用户提问与 AI 回复同表，靠 {@code role} 区分。
 */
@Data
@TableName("tb_message")
public class Message {

    /** 自增主键，同时决定同一会话内的消息顺序 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** user / assistant / system */
    private String role;

    private String content;

    private LocalDateTime createTime;
}
