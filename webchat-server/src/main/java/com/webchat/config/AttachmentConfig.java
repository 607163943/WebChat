package com.webchat.config;

import com.webchat.storage.AttachmentStorage;
import com.webchat.storage.LocalAttachmentStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Path;

/**
 * 附件相关的装配。
 *
 * <p>{@code @EnableScheduling} 放在这里而不是主类上，是为了让它和它服务的那个定时任务待在一起。
 * <b>没有它 {@code @Scheduled} 只是元数据、会被静默忽略</b>——附件永远不会被回收，且不报任何错，
 * 测试也发现不了。
 *
 * <p>{@link AttachmentProperties} 是 record、不参与组件扫描，必须显式 {@code @EnableConfigurationProperties}
 * 注册，否则注入它的地方会以 {@code NoSuchBeanDefinitionException} 启动失败。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentConfig {

    @Bean
    public AttachmentStorage attachmentStorage(AttachmentProperties properties) {
        return new LocalAttachmentStorage(Path.of(properties.dir()));
    }
}
