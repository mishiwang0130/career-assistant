package com.wxy.career.service.impl;

import com.wxy.career.common.auth.JwtService;
import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.mapper.SysRefreshTokenMapper;
import com.wxy.career.mapper.SysTokenMapper;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceImplTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private SysTokenMapper sysTokenMapper;

    @Mock
    private SysRefreshTokenMapper sysRefreshTokenMapper;

    @InjectMocks
    private TokenServiceImpl tokenService;

    @Test
    void shouldValidateActiveToken() {
        SysToken token = new SysToken();
        token.setUserId(1L);
        token.setJti("jti-1");
        token.setRevoked(SysToken.STATUS_ACTIVE);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(sysTokenMapper.selectOne(any())).thenReturn(token);

        boolean valid = tokenService.isValid(new LoginUser(1L, "alice", "jti-1"));

        assertThat(valid).isTrue();
    }

    @Test
    void shouldRejectRevokedToken() {
        SysToken token = new SysToken();
        token.setUserId(1L);
        token.setJti("jti-1");
        token.setRevoked(SysToken.STATUS_REVOKED);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        when(sysTokenMapper.selectOne(any())).thenReturn(token);

        boolean valid = tokenService.isValid(new LoginUser(1L, "alice", "jti-1"));

        assertThat(valid).isFalse();
    }

    @Test
    void shouldRejectUnknownRefreshToken() {
        when(sysRefreshTokenMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> tokenService.refresh("unknown-refresh-token"))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1004);
                    assertThat(bizException.getHttpStatus().value()).isEqualTo(401);
                });
    }
}
