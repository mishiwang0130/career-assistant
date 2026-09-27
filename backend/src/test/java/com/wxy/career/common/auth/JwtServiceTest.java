package com.wxy.career.common.auth;

import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发与解析测试。
 *
 * @author wxy
 * @date 2026-09-27
 */
class JwtServiceTest {

    /**
     * 被测 JWT 服务。
     */
    private JwtService jwtService;

    /**
     * JWT 测试配置。
     */
    private JwtProperties jwtProperties;

    /**
     * 初始化测试依赖。
     */
    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("career-assistant-test-secret-change-me");
        jwtProperties.setAccessTokenExpireMinutes(120L);
        jwtProperties.setRefreshTokenExpireDays(7L);

        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtProperties", jwtProperties);
    }

    /**
     * 验证正常签发和解析。
     */
    @Test
    void shouldSignAndParseToken() {
        String token = jwtService.generateToken(1L, "alice", "jti-1");

        LoginUser loginUser = jwtService.parseToken(token);

        assertThat(loginUser.getUserId()).isEqualTo(1L);
        assertThat(loginUser.getUsername()).isEqualTo("alice");
        assertThat(loginUser.getJti()).isEqualTo("jti-1");
    }

    /**
     * 验证篡改后的 Token 被拒绝。
     */
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

    /**
     * 验证过期 Token 被拒绝。
     */
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
