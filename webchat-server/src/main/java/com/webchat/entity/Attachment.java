package com.webchat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件表：一行代表一个已上传的图片 / 视频文件。
 *
 * <p>{@code messageId} 是否为空就是「是否已绑定」——为空表示文件已经传上来、但还没随消息提交。
 * 表里刻意没有 status / type 列：绑定状态由这一列推出，image / video 分类由 {@code mimeType}
 * 的顶层类型推出（前提是上传接口用 MIME 白名单把住了入口，见 AttachmentTypePolicy）。
 */
@Data
@TableName("tb_attachment")
public class Attachment {

    /** 与其余表一致：表是自增列，必须显式声明 AUTO，否则 MyBatis-Plus 会写雪花值 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 上传时可能还没有会话（新对话草稿态），绑定消息时统一回填 */
    private Long conversationId;

    /** 为空 = 待绑定；定时清理据此判定未绑定的附件是否已过期 */
    private Long messageId;

    /** 原始文件名，仅用于展示。用户完全可控，不要拿它拼路径 */
    private String originalName;

    /** 存储对象键，附件的真值：删除对象与读取内容都靠它 */
    private String objectKey;

    /** 访问 URL，由 objectKey 推导，入库前即可确定（不依赖自增 id，无需二次写） */
    private String url;

    /** 文件类型的唯一依据：分派渲染、能否多模态识别都看它 */
    private String mimeType;

    private Long fileSize;

    /** 清理失败的重试次数，纯观测列，用于发现反复删不掉的对象 */
    private Integer retryCount;

    /** create_time 由数据库默认值维护，插入时不赋值（MyBatis-Plus 默认跳过 null 字段） */
    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
