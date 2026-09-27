package com.wxy.career.common.exception;

import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.result.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 *
 * @author wxy
 * @date 2026-09-27
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常。
     *
     * @param exception 业务异常
     * @return 统一错误响应
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException exception) {
        return ResponseEntity.status(exception.getHttpStatus())
                .body(Result.error(exception.getErrorCode()));
    }

    /**
     * 处理请求体字段校验异常。
     *
     * @param exception 字段校验异常
     * @return 参数错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null ? ErrorConstant.PARAM_ERROR.getMsg() : fieldError.getDefaultMessage();
        return buildError(ErrorConstant.PARAM_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理参数绑定异常。
     *
     * @param exception 参数绑定异常
     * @return 参数错误响应
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null ? ErrorConstant.PARAM_ERROR.getMsg() : fieldError.getDefaultMessage();
        return buildError(ErrorConstant.PARAM_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理约束校验异常。
     *
     * @param exception 约束校验异常
     * @return 参数错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolationException(
            ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ErrorConstant.PARAM_ERROR.getMsg());
        return buildError(ErrorConstant.PARAM_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理请求参数解析异常。
     *
     * @param exception 请求参数解析异常
     * @return 参数错误响应
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception exception) {
        log.warn("请求参数解析失败: {}", exception.getMessage());
        return buildError(ErrorConstant.PARAM_ERROR, ErrorConstant.PARAM_ERROR.getMsg(), HttpStatus.BAD_REQUEST);
    }

    /**
     * 处理资源不存在异常。
     *
     * @param exception 资源不存在异常
     * @return 资源不存在响应
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFoundException(Exception exception) {
        return buildError(ErrorConstant.NOT_FOUND, ErrorConstant.NOT_FOUND.getMsg(), HttpStatus.NOT_FOUND);
    }

    /**
     * 处理未捕获的系统异常。
     *
     * @param exception 系统异常
     * @return 系统异常响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception exception) {
        log.error("系统异常", exception);
        return buildError(ErrorConstant.SYSTEM_ERROR, ErrorConstant.SYSTEM_ERROR.getMsg(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 构造统一错误响应。
     *
     * @param errorCode 错误码
     * @param message 错误提示
     * @param status HTTP 状态
     * @return 统一错误响应
     */
    private ResponseEntity<Result<Void>> buildError(
            com.wxy.career.common.result.ErrorCode errorCode, String message, HttpStatus status) {
        return ResponseEntity.status(status).body(Result.error(errorCode, message));
    }
}
