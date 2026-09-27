package com.wxy.career.common.result;

/**
 * 业务错误码。
 *
 * @author wxy
 * @date 2026-09-27
 */
public final class ErrorCode {

    /**
     * 业务错误码。
     */
    private final int code;

    /**
     * 错误提示。
     */
    private final String msg;

    /**
     * 创建错误码。
     *
     * @param code 业务错误码
     * @param msg 错误提示
     */
    public ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    /**
     * 获取业务错误码。
     *
     * @return 业务错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取错误提示。
     *
     * @return 错误提示
     */
    public String getMsg() {
        return msg;
    }
}
