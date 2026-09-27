package com.wxy.career.common.auth;

/**
 * 当前登录用户上下文。
 *
 * @author wxy
 * @date 2026-09-27
 */
public final class LoginUserHolder {

    /**
     * 请求级登录用户上下文，请求结束后必须清理。
     */
    private static final ThreadLocal<LoginUser> LOGIN_USER_THREAD_LOCAL = new ThreadLocal<>();

    /**
     * 工具类禁止实例化。
     */
    private LoginUserHolder() {
    }

    /**
     * 设置当前登录用户。
     *
     * @param loginUser 登录用户
     */
    public static void set(LoginUser loginUser) {
        LOGIN_USER_THREAD_LOCAL.set(loginUser);
    }

    /**
     * 获取当前登录用户。
     *
     * @return 当前登录用户，未登录时返回 null
     */
    public static LoginUser get() {
        return LOGIN_USER_THREAD_LOCAL.get();
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID，未登录时返回 null
     */
    public static Long getUserId() {
        LoginUser loginUser = get();
        return loginUser == null ? null : loginUser.getUserId();
    }

    /**
     * 清理当前登录用户。
     */
    public static void clear() {
        LOGIN_USER_THREAD_LOCAL.remove();
    }
}
