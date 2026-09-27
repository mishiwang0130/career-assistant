package com.wxy.career.common.auth;

/**
 * 登录令牌有效性校验器，由业务模块实现。
 *
 * @author wxy
 * @date 2026-09-27
 */
public interface LoginTokenValidator {

    /**
     * 校验令牌是否有效。
     *
     * @param loginUser 当前令牌信息
     * @return true 表示令牌有效
     */
    boolean isValid(LoginUser loginUser);
}
