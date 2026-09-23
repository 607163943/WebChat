package com.webchat.config;

import com.webchat.service.AttachmentCleanupTask;
import com.webchat.storage.AttachmentStorage;
import com.webchat.storage.LocalAttachmentStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 附件装配的冒烟测试。
 *
 * <p>守两件只在启动时才会暴露、且专门有坑的事：
 * <ol>
 *   <li>{@link AttachmentProperties} 是 record、不参与组件扫描，漏了
 *       {@code @EnableConfigurationProperties} 就会以 {@code NoSuchBeanDefinitionException} 启动失败</li>
 *   <li>{@link AttachmentCleanupTask} 的 {@code @Scheduled} 只在 {@code @EnableScheduling} 存在时才会被注册，
 *       缺了它方法被<b>静默忽略</b>——附件永远不回收，且任何测试都不会红</li>
 * </ol>
 *
 * <p>与 {@code AiModelConfigTests} 同一路数：只要 Spring 上下文，不碰数据库（Hikari 懒连接）、不联网。
 */
@SpringBootTest
class AttachmentConfigTests {

    @Autowired
    private AttachmentProperties properties;

    @Autowired
    private AttachmentStorage storage;

    @Autowired
    private AttachmentCleanupTask cleanupTask;

    @Test
    @DisplayName("附件配置能绑定：目录、大小与数量上限都有值")
    void propertiesBind() {
        assertThat(properties.dir()).isNotBlank();
        assertThat(properties.maxFileSize().toBytes()).isPositive();
        assertThat(properties.maxFilesPerMessage()).isPositive();
        assertThat(properties.maxRequestMediaSize().toBytes()).isPositive();
        assertThat(properties.unboundTtl()).isPositive();
        assertThat(properties.cleanupInterval()).isPositive();
        assertThat(properties.retryAlertThreshold()).isPositive();
    }

    @Test
    @DisplayName("存储与清理任务都已就位")
    void storageAndCleanupAreWired() {
        assertThat(storage).isInstanceOf(LocalAttachmentStorage.class);
        assertThat(cleanupTask).isNotNull();
    }
}
