package com.wxy.career.common.auth;

import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwtService;

    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("career-assistant-test-secret-change-me");
        jwtProperties.setAccessTokenExpireMinutes(120L);
        jwtProperties.setRefreshTokenExpireDays(7L);

        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtProperties", jwtProperties);
    }

    @Test
    void shouldSignAndParseToken() {
        String token = jwtService.generateToken(1L, "alice", "jti-1");

        LoginUser loginUser = jwtService.parseToken(token);

        assertThat(loginUser.getUserId()).isEqualTo(1L);
        assertThat(loginUser.getUsername()).isEqualTo("alice");
        assertThat(loginUser.getJti()).isEqualTo("jti-1");
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = jwtService.generateToken(1L, "alice", "jti-1");
        String tamperedToken = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> jwtService.parseToken(tamperedToken))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode().getCode())
                .isEqualTo(401);
    }

    @Test
    void shouldRejectExpiredToken() {
        jwtProperties.setAccessTokenExpireMinutes(-1L);
        String token = jwtService.generateToken(1L, "alice", "jti-1");

        assertThatThrownBy(() -> jwtService.parseToken(token))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode().getCode())
                .isEqualTo(401);
    }
}
