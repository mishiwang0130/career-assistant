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
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException exception) {
        return ResponseEntity.status(exception.getHttpStatus())
                .body(Result.error(exception.getErrorCode()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null ? ErrorConstant.PARAM_ERROR.getMsg() : fieldError.getDefaultMessage();
        return buildError(ErrorConstant.PARAM_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null ? ErrorConstant.PARAM_ERROR.getMsg() : fieldError.getDefaultMessage();
        return buildError(ErrorConstant.PARAM_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolationException(
            ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse(ErrorConstant.PARAM_ERROR.getMsg());
        return buildError(ErrorConstant.PARAM_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception exception) {
        log.warn("请求参数解析失败: {}", exception.getMessage());
        return buildError(ErrorConstant.PARAM_ERROR, ErrorConstant.PARAM_ERROR.getMsg(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFoundException(Exception exception) {
        return buildError(ErrorConstant.NOT_FOUND, ErrorConstant.NOT_FOUND.getMsg(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception exception) {
        log.error("系统异常", exception);
        return buildError(ErrorConstant.SYSTEM_ERROR, ErrorConstant.SYSTEM_ERROR.getMsg(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Result<Void>> buildError(
            com.wxy.career.common.result.ErrorCode errorCode, String message, HttpStatus status) {
        return ResponseEntity.status(status).body(Result.error(errorCode, message));
    }
}
