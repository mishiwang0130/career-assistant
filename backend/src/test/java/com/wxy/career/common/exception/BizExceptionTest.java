package com.wxy.career.common.exception;

import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class BizExceptionTest {

    @Test
    void shouldUseBadRequestAsDefaultStatus() {
        BizException exception = new BizException(ErrorConstant.USERNAME_ALREADY_EXISTS);

        assertThat(exception.getErrorCode().getCode()).isEqualTo(1001);
        assertThat(exception.getErrorCode().getMsg()).isEqualTo("用户名已存在");
        assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

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
}
