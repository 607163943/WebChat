package com.webchat.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把附件写在本机文件系统上的实现，根目录来自 {@code webchat.attachment.dir}。
 *
 * <p>目录按需创建（不在构造器里建），免得 {@code @SpringBootTest} 这类不碰附件的测试
 * 也在工作目录里留下一堆空目录。
 */
public class LocalAttachmentStorage implements AttachmentStorage {

    private final Path root;

    public LocalAttachmentStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void store(String objectKey, byte[] content) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            // 默认选项即 CREATE + TRUNCATE_EXISTING + WRITE
            Files.write(target, content);
        } catch (IOException e) {
            throw new AttachmentStorageException("写入附件失败：" + objectKey, e);
        }
    }

    @Override
    public byte[] read(String objectKey) {
        Path target = resolve(objectKey);
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new AttachmentStorageException("读取附件失败：" + objectKey, e);
        }
    }

    /** deleteIfExists 天然满足幂等约定：文件本就不存在时返回 false，不抛异常 */
    @Override
    public void delete(String objectKey) {
        Path target = resolve(objectKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new AttachmentStorageException("删除附件失败：" + objectKey, e);
        }
    }

    /**
     * 归一化后确认最终路径仍落在根目录内。
     *
     * <p>对象键完全由服务端生成（UUID + 白名单推出的扩展名），照理不会带 {@code ..}；
     * 但落盘与删除都是不可逆操作，这里再兜一道，宁可失败也不越出根目录。
     */
    private Path resolve(String objectKey) {
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new AttachmentStorageException("非法的对象键：" + objectKey, null);
        }
        return resolved;
    }
}
