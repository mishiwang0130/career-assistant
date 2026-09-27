package com.wxy.career.common.exception;

import com.wxy.career.common.result.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 业务异常。
 *
 * @author wxy
 * @date 2026-09-27
 */
public class BizException extends RuntimeException {

    /**
     * 业务错误码。
     */
    private final ErrorCode errorCode;

    /**
     * 响应 HTTP 状态。
     */
    private final HttpStatus httpStatus;

    /**
     * 创建默认 HTTP 400 的业务异常。
     *
     * @param errorCode 业务错误码
     */
    public BizException(ErrorCode errorCode) {
        this(errorCode, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建指定 HTTP 状态的业务异常。
     *
     * @param errorCode 业务错误码
     * @param httpStatus 响应 HTTP 状态
     */
    public BizException(ErrorCode errorCode, HttpStatus httpStatus) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * 获取业务错误码。
     *
     * @return 业务错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取响应 HTTP 状态。
     *
     * @return 响应 HTTP 状态
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
