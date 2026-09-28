package com.wxy.career.common.result;

/**
 * 统一错误码常量。
 *
 * @author wxy
 * @date 2026-09-27
 */
public final class ErrorConstant {

    /**
     * 成功。
     */
    public static final ErrorCode SUCCESS = new ErrorCode(200, "成功");

    /**
     * 参数错误。
     */
    public static final ErrorCode PARAM_ERROR = new ErrorCode(400, "参数错误");

    /**
     * 未登录或登录已过期。
     */
    public static final ErrorCode UNAUTHORIZED = new ErrorCode(401, "未登录或登录已过期");

    /**
     * 无权限。
     */
    public static final ErrorCode FORBIDDEN = new ErrorCode(403, "无权限");

    /**
     * 资源不存在。
     */
    public static final ErrorCode NOT_FOUND = new ErrorCode(404, "资源不存在");

    /**
     * 系统异常。
     */
    public static final ErrorCode SYSTEM_ERROR = new ErrorCode(500, "系统异常");

    /**
     * 用户名已存在。
     */
    public static final ErrorCode USERNAME_ALREADY_EXISTS = new ErrorCode(1001, "用户名已存在");

    /**
     * 用户名或密码错误。
     */
    public static final ErrorCode USERNAME_OR_PASSWORD_ERROR = new ErrorCode(1002, "用户名或密码错误");

    /**
     * 账号已禁用。
     */
    public static final ErrorCode ACCOUNT_DISABLED = new ErrorCode(1003, "账号已禁用");

    /**
     * 刷新令牌无效或已过期。
     */
    public static final ErrorCode REFRESH_TOKEN_INVALID = new ErrorCode(1004, "刷新令牌无效或已过期");

    /**
     * 目标会话已有请求在处理中。
     */
    public static final ErrorCode SESSION_BUSY = new ErrorCode(1050, "会话正在处理中，请稍后再试");

    /**
     * 工具类禁止实例化。
     */
    private ErrorConstant() {
    }
}
