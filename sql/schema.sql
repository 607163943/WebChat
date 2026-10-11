CREATE DATABASE IF NOT EXISTS `webchat`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `webchat`;

SET NAMES utf8mb4;

CREATE TABLE `tb_conversation` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID，登录功能上线前为固定值',
    `title` varchar(100) NOT NULL COMMENT '会话标题',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_update` (`user_id`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话表';

CREATE TABLE `tb_message` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` bigint unsigned NOT NULL COMMENT '所属会话ID',
    `role` varchar(20) NOT NULL COMMENT '消息角色：user/assistant/system',
    `content` text NOT NULL COMMENT '消息正文',
    `status` varchar(20) NOT NULL DEFAULT 'completed' COMMENT '消息状态：completed 正常结束 / interrupted 用户中断 / failed 生成失败',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息表';

CREATE TABLE `tb_attachment` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '附件ID',
    `user_id` bigint unsigned NOT NULL COMMENT '上传者用户ID',
    `conversation_id` bigint unsigned DEFAULT NULL COMMENT '所属会话ID，NULL 表示上传时还没有会话（新对话草稿态）',
    `message_id` bigint unsigned DEFAULT NULL COMMENT '所属消息ID，NULL 表示已上传但尚未随消息提交（即待绑定）',
    `original_name` varchar(255) NOT NULL COMMENT '原始文件名，仅用于展示',
    `object_key` varchar(512) NOT NULL COMMENT '存储对象键，删除对象与重新签名都靠它',
    `url` varchar(2048) NOT NULL COMMENT '访问URL，用于回显',
    `mime_type` varchar(100) NOT NULL COMMENT 'MIME类型，如 image/png、text/plain，是文件类型的唯一依据',
    `file_size` bigint unsigned NOT NULL COMMENT '文件大小（字节）',
    `retry_count` int unsigned NOT NULL DEFAULT 0 COMMENT '清理失败重试次数',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间，未绑定附件的过期判定基准',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后变更时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_object_key` (`object_key`),
    KEY `idx_message_create_time` (`message_id`, `create_time`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '附件表';
