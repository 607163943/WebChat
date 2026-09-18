package com.webchat.config;

import org.springframework.stereotype.Component;

/**
 * 固定用户实现：登录功能上线前，所有会话与消息都归属同一个约定用户。
 */
@Component
public class FixedCurrentUserProvider implements CurrentUserProvider {

    /** 全项目唯一一处固定用户 ID 定义 */
    public static final long FIXED_USER_ID = 1L;

    @Override
    public long userId() {
        return FIXED_USER_ID;
    }
}
