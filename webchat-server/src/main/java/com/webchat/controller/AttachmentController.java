package com.webchat.controller;

import com.webchat.common.BizException;
import com.webchat.common.Result;
import com.webchat.common.ResultCode;
import com.webchat.dto.AttachmentVO;
import com.webchat.entity.Attachment;
import com.webchat.service.AttachmentService;
import com.webchat.storage.AttachmentStorageException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Tag(name = "附件")
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @Operation(summary = "上传附件", description = """
            图片、MP4 视频或 txt 文本文件，单文件上限与允许的类型见 webchat.attachment 配置与 AttachmentTypePolicy。
            上传即落库并返回可回显的 url，此时 message_id 为空（待绑定），随发送消息时提交 id 完成绑定。
            conversationId 可选：新对话草稿态还没有会话，留空即可。
            文本文件会异步切分并向量化，供提问时检索；索引状态不落库，前端也不展示。""")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AttachmentVO> upload(@RequestPart("file") MultipartFile file,
                                       @RequestParam(value = "conversationId", required = false) Long conversationId) {
        return Result.ok(attachmentService.upload(conversationId, file.getOriginalFilename(),
                file.getContentType(), bytesOf(file)));
    }

    @Operation(summary = "读取附件内容", description = """
            按存储键读取原始字节，用于前端回显。
            用对象键而不是自增 id 是有意的：id 连续可枚举，而当前登录功能未开发、user_id 恒为 1，
            归属校验对任何请求都成立——把 id 当边界等于没有边界。""")
    @GetMapping("/content/{*objectKey}")
    public ResponseEntity<byte[]> content(@PathVariable String objectKey) {
        Attachment attachment = attachmentService.requireByObjectKey(stripLeadingSlash(objectKey));
        byte[] content;
        try {
            content = attachmentService.readContent(attachment);
        } catch (AttachmentStorageException e) {
            // 行还在、对象没了。对使用者来说结果一样，都是「取不到」
            throw new BizException(ResultCode.NOT_FOUND, "附件内容已不可用");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getMimeType()))
                // private 不能省：附件正文不该被共享代理缓存下来
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                // 文本附件的白名单判据是「能解码成 UTF-8 / GB18030」，所以一个内容是 HTML 的文件
                // 也能合法地传进来。这里返回的确实是 text/plain、浏览器不会执行它，
                // 但显式禁止嗅探更稳——免得日后哪次改动让类型协商出岔子
                .header("X-Content-Type-Options", "nosniff")
                .body(content);
    }

    @Operation(summary = "删除附件", description = "只允许删还没随消息发出的附件；幂等，已经没了也不报错")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        attachmentService.delete(id);
        return Result.ok();
    }

    /** {@code {*objectKey}} 捕获的是含前导斜杠的剩余路径 */
    private static String stripLeadingSlash(String objectKey) {
        return objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
    }

    private static byte[] bytesOf(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "没有收到文件内容");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BizException(ResultCode.BAD_REQUEST, "读取上传内容失败，请重试");
        }
    }
}
