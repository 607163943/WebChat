package com.webchat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 附件相关的可调项，集中在 application.yaml 的 {@code webchat.attachment} 段。
 *
 * <p>用 record + 构造器绑定（Spring Boot 3 对 record 默认走构造器绑定）。注意 {@code @DefaultValue}
 * <b>只在绑定那一刻兜底，不会往 Environment 里写 property</b>——所以 {@code @Scheduled} 的占位符
 * 仍然要自带内联默认值，不能指望这里。
 */
@ConfigurationProperties(prefix = "webchat.attachment")
public record AttachmentProperties(

        /** 本地存储根目录，相对路径按后端进程的工作目录解析 */
        @DefaultValue("./data/attachments") String dir,

        /** 单个文件大小上限，与 spring.servlet.multipart.max-file-size 保持一致 */
        @DefaultValue("10MB") DataSize maxFileSize,

        /**
         * 单个<b>文本</b>文件的大小上限，比媒体严得多。
         *
         * <p>文本要切分后逐段调 embedding，成本随体量线性上升：1MB 中文约 35 万 token，而
         * {@code qwen3.7-text-embedding-flash} 的 TPM 是 100 万——一个满额的文件就吃掉三分之一的
         * 每分钟配额，再同时传几个就会撞限流。10MB 的文本则要几百次调用、索引期间该文件一直不可检索。
         */
        @DefaultValue("1MB") DataSize maxTextFileSize,

        /** 单条消息能带的附件数量上限 */
        @DefaultValue("5") int maxFilesPerMessage,

        /**
         * 整轮请求发给模型的媒体总量上限（base64 编码前）。
         *
         * <p>只限个数是不够的：5 个 10MB 的文件 base64 之后是 ~67MB 的请求体，
         * 所以还要有一道按字节算的闸。
         */
        @DefaultValue("20MB") DataSize maxRequestMediaSize,

        /** 未绑定附件的存活时长，超过即被清理任务回收 */
        @DefaultValue("24h") Duration unboundTtl,

        /** 清理任务的扫描间隔，同时用作首次执行的延迟 */
        @DefaultValue("30m") Duration cleanupInterval,

        /** retry_count 超过它就在日志里告警、提示人工介入 */
        @DefaultValue("5") int retryAlertThreshold) {
}
