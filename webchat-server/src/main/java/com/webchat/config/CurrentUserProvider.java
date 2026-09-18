package com.webchat.config;

/**
 * 当前用户来源。
 *
 * <p>登录功能上线前由 {@link FixedCurrentUserProvider} 提供固定值；届时换掉这一处实现即可，
 * 但业务查询仍需保留 user_id 条件，避免日后变成逐个 mapper 的安全审计。
 */
public interface CurrentUserProvider {

    long userId();
}
