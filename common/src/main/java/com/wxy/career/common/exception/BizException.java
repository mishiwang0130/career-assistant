package com.wxy.career.common.exception;

import com.wxy.career.common.result.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 业务异常。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    private final HttpStatus httpStatus;

    public BizException(ErrorCode errorCode) {
        this(errorCode, HttpStatus.BAD_REQUEST);
    }

    public BizException(ErrorCode errorCode, HttpStatus httpStatus) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
