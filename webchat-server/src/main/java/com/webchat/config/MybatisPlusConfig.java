package com.webchat.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 接线。
 *
 * <p>刻意不装分页插件：会话列表按约定一次性全量返回，没有分页需求。
 */
@Configuration
@MapperScan("com.webchat.mapper")
public class MybatisPlusConfig {
}
