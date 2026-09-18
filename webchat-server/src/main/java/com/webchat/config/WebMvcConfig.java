package com.webchat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。前端 7000 直连后端 8000，跨域由后端放行，Vite 侧不配代理。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // localhost 与 127.0.0.1 是浏览器眼里的两个不同来源，只放行前者的话，
                // 用 127.0.0.1 打开前端就会被 CORS 拦下
                .allowedOrigins("http://localhost:7000", "http://127.0.0.1:7000")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 尚无登录态，不携带凭证，配置保持与实际一致
                .allowCredentials(false)
                .maxAge(3600);
    }
}
