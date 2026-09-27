package com.wxy.career.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应结构。
 *
 * @param <T> 数据类型
 * @author wxy
 * @date 2026-09-27
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /**
     * 业务响应码。
     */
    private Integer code;

    /**
     * 响应提示。
     */
    private String msg;

    /**
     * 响应数据。
     */
    private T data;

    /**
     * 创建无数据成功响应。
     *
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success() {
        return success(null);
    }

    /**
     * 创建带数据成功响应。
     *
     * @param data 响应数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorConstant.SUCCESS.getCode(), ErrorConstant.SUCCESS.getMsg(), data);
    }

    /**
     * 创建自定义提示的成功响应。
     *
     * @param msg 响应提示
     * @param data 响应数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(ErrorConstant.SUCCESS.getCode(), msg, data);
    }

    /**
     * 根据错误码创建失败响应。
     *
     * @param errorCode 错误码
     * @param <T> 数据类型
     * @return 失败响应
     */
    public static <T> Result<T> error(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMsg(), null);
    }

    /**
     * 根据错误码和自定义提示创建失败响应。
     *
     * @param errorCode 错误码
     * @param msg 自定义提示
     * @param <T> 数据类型
     * @return 失败响应
     */
    public static <T> Result<T> error(ErrorCode errorCode, String msg) {
        return new Result<>(errorCode.getCode(), msg, null);
    }
}
