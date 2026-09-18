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
