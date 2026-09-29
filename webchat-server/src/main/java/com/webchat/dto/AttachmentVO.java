package com.webchat.dto;

/**
 * 附件对外的样子。上传响应与消息回显共用同一个结构——两处前端要做的事是一样的：
 * 按 {@code mimeType} 的顶层类型分派渲染，用 {@code url} 取内容。
 *
 * <p>{@code url} 是相对路径（如 {@code /api/attachments/content/2026/09/23/xxx.png}），
 * 由前端拼上后端地址。这样库里不会存下与部署环境绑定的绝对地址。
 *
 * <p>{@code indexState} 是文本附件的向量索引状态，取值见 {@code IndexState.wireName()}：
 * {@code pending}（排队或索引中）、{@code ready}（可检索）、{@code empty}（空文件，不进 RAG）、
 * {@code failed}（处理失败——它<b>不会</b>变成可检索，前端要提示用户并把这个附件撤下来）、
 * {@code unavailable}（本进程没有它的记录，即上传发生在上一次启动）、
 * {@code not_indexed}（图片／视频，不进向量库）。
 *
 * <p>它是前端轮询的终止条件，也是「这个文件的内容能不能被检索到」的对外说法。失败<b>原因</b>
 * （片段过多、队列已满、向量库预算耗尽、embedding 异常）刻意不在这里——对用户来说都是「上传失败」，
 * 分了也没有可采取的动作，原因只写后端日志。
 */
public record AttachmentVO(Long id, String originalName, String url, String mimeType, Long fileSize,
                           String indexState) {
}
