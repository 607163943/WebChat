-- ============================================================
-- WebChat 数据库建表脚本
-- 环境：MySQL 8.0 / InnoDB / utf8mb4
-- ============================================================

-- 数据库名未在表设计中约定，此处取项目名 webchat
CREATE DATABASE IF NOT EXISTS `webchat`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE `webchat`;

-- 指定会话字符集为 utf8mb4，确保后续语句中的中文注释按 UTF-8 读取，
-- 避免 mysql 客户端默认 latin1 导致注释乱码
SET NAMES utf8mb4;

-- 会话表：一条记录代表一个会话（对话窗口）
CREATE TABLE `tb_conversation` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `user_id` bigint unsigned NOT NULL COMMENT '所属用户ID，登录功能上线前为固定值',
    `title` varchar(100) NOT NULL COMMENT '会话标题',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_update` (`user_id`, `update_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话表';

-- 消息表：一条记录代表会话中的一条消息，用户提问与 AI 回复同表，通过 role 区分
CREATE TABLE `tb_message` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` bigint unsigned NOT NULL COMMENT '所属会话ID',
    `role` varchar(20) NOT NULL COMMENT '消息角色：user/assistant/system',
    `content` text NOT NULL COMMENT '消息正文',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息表';

-- 附件表：一条记录代表一个已上传的图片 / 音频文件
-- message_id 是否为空即附件的绑定状态（为空=待绑定）；清理判定与对象回收见 Obsidian《数据库表设计.md》「五、附件表」
-- 无 type 列：image/audio 分类由 mime_type 的顶层类型推出，合法性由上传接口的 MIME 白名单保证（同上文「五、附件表」）
CREATE TABLE `tb_attachment` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '附件ID',
    `user_id` bigint unsigned NOT NULL COMMENT '上传者用户ID',
    `conversation_id` bigint unsigned DEFAULT NULL COMMENT '所属会话ID，NULL 表示上传时还没有会话（新对话草稿态）',
    `message_id` bigint unsigned DEFAULT NULL COMMENT '所属消息ID，NULL 表示已上传但尚未随消息提交（即待绑定）',
    `original_name` varchar(255) NOT NULL COMMENT '原始文件名，仅用于展示',
    `object_key` varchar(512) NOT NULL COMMENT '存储对象键，删除对象与重新签名都靠它',
    `url` varchar(2048) NOT NULL COMMENT '访问URL，用于回显',
    `mime_type` varchar(100) NOT NULL COMMENT 'MIME类型，如 image/png，是文件类型的唯一依据',
    `file_size` bigint unsigned NOT NULL COMMENT '文件大小（字节）',
    `retry_count` int unsigned NOT NULL DEFAULT 0 COMMENT '清理失败重试次数',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间，未绑定附件的过期判定基准',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后变更时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_object_key` (`object_key`),
    KEY `idx_message_create_time` (`message_id`, `create_time`),
    KEY `idx_conversation_id` (`conversation_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '附件表';
