package com.wxy.career.common.exception;

import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务异常与统一响应测试。
 *
 * @author wxy
 * @date 2026-09-27
 */
class BizExceptionTest {

    /**
     * 验证业务异常默认返回 HTTP 200。
     */
    @Test
    void shouldUseOkAsDefaultStatus() {
        BizException exception = new BizException(ErrorConstant.USERNAME_ALREADY_EXISTS);

        assertThat(exception.getErrorCode().getCode()).isEqualTo(1001);
        assertThat(exception.getErrorCode().getMsg()).isEqualTo("用户名已存在");
        assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.OK);
    }

    /**
     * 验证可指定特殊 HTTP 状态。
     */
    @Test
    void shouldAllowExplicitHttpStatus() {
        BizException exception = new BizException(
                ErrorConstant.USERNAME_OR_PASSWORD_ERROR,
                HttpStatus.UNAUTHORIZED);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Result<Void>> response = handler.handleBizException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(1002);
        assertThat(response.getBody().getMsg()).isEqualTo("用户名或密码错误");
    }

    /**
     * 验证默认业务异常经全局异常处理器后返回 HTTP 200。
     */
    @Test
    void shouldReturnOkForDefaultBizException() {
        BizException exception = new BizException(ErrorConstant.USERNAME_ALREADY_EXISTS);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<Result<Void>> response = handler.handleBizException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(1001);
        assertThat(response.getBody().getMsg()).isEqualTo("用户名已存在");
    }
}
