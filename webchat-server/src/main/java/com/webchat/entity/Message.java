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

    /** 生成正常结束 */
    public static final String STATUS_COMPLETED = "completed";
    /** 用户中断（含切会话、关页面——服务端看到的都是客户端断开） */
    public static final String STATUS_INTERRUPTED = "interrupted";
    /** 模型或写库出错，正文是出错前已生成的部分 */
    public static final String STATUS_FAILED = "failed";

    /** 自增主键，同时决定同一会话内的消息顺序 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** user / assistant / system */
    private String role;

    private String content;

    /**
     * 这条消息是怎么收场的，取值见上面的 {@code STATUS_*} 常量。
     *
     * <p>只对助手消息有意义：用户消息插入时不赋值，走列默认值 {@code completed}。
     */
    private String status;

    private LocalDateTime createTime;
}
