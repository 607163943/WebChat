package com.webchat.dto;

/**
 * 附件对外的样子。上传响应与消息回显共用同一个结构——两处前端要做的事是一样的：
 * 按 {@code mimeType} 的顶层类型分派渲染，用 {@code url} 取内容。
 *
 * <p>{@code url} 是相对路径（如 {@code /api/attachments/content/2026/09/23/xxx.png}），
 * 由前端拼上后端地址。这样库里不会存下与部署环境绑定的绝对地址。
 */
public record AttachmentVO(Long id, String originalName, String url, String mimeType, Long fileSize) {
}
