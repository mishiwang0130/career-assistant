package com.wxy.career.common.auth;

/**
 * 当前登录用户上下文。
 */
public final class LoginUserHolder {

    private static final ThreadLocal<LoginUser> LOGIN_USER_THREAD_LOCAL = new ThreadLocal<>();

    private LoginUserHolder() {
    }

    public static void set(LoginUser loginUser) {
        LOGIN_USER_THREAD_LOCAL.set(loginUser);
    }

    public static LoginUser get() {
        return LOGIN_USER_THREAD_LOCAL.get();
    }

    public static Long getUserId() {
        LoginUser loginUser = get();
        return loginUser == null ? null : loginUser.getUserId();
    }

    public static void clear() {
        LOGIN_USER_THREAD_LOCAL.remove();
    }
}
