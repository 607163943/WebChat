package com.webchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话表：一行代表一个会话（对话窗口）。
 */
@Data
@TableName("tb_conversation")
public class Conversation {

    /** MyBatis-Plus 主键默认走雪花算法，而表是自增列，必须显式声明 AUTO，否则会往自增列写雪花值 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    /** 以下两个时间由数据库默认值维护，插入时不赋值（MyBatis-Plus 默认跳过 null 字段） */
    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
