package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.TokenService;
import com.wxy.career.vo.AuthRespVO;
import com.wxy.career.vo.TokenPairVO;
import com.wxy.career.vo.UserLoginReqVO;
import com.wxy.career.vo.UserRegisterReqVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void shouldRejectDuplicateUsername() {
        when(sysUserMapper.selectOne(any())).thenReturn(existingUser());
        UserRegisterReqVO reqVO = registerReq();

        assertThatThrownBy(() -> authService.register(reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1001);
                    assertThat(bizException.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void shouldRejectWrongPassword() {
        SysUser user = existingUser();
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong-password", user.getPassword())).thenReturn(false);
        UserLoginReqVO reqVO = loginReq("wrong-password");

        assertThatThrownBy(() -> authService.login(reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1002);
                    assertThat(bizException.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                });
    }

    @Test
    void shouldRejectDisabledAccount() {
        SysUser user = existingUser();
        user.setStatus(SysUser.STATUS_DISABLED);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("password123", user.getPassword())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(loginReq("password123")))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1003);
                    assertThat(bizException.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    @Test
    void shouldRegisterAndIssueTokens() {
        when(sysUserMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(1L);
            return 1;
        }).when(sysUserMapper).insert(any(SysUser.class));
        TokenPairVO tokenPair = new TokenPairVO("access-token", "refresh-token", 7200L);
        when(tokenService.issueTokens(any(SysUser.class))).thenReturn(tokenPair);

        AuthRespVO response = authService.register(registerReq());

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getUser().getUsername()).isEqualTo("alice");
        assertThat(response.getUser().getNickname()).isEqualTo("Alice");
    }

    @Test
    void shouldLoginAndIssueTokens() {
        SysUser user = existingUser();
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("password123", user.getPassword())).thenReturn(true);
        when(tokenService.issueTokens(user)).thenReturn(new TokenPairVO("access-token", "refresh-token", 7200L));

        AuthRespVO response = authService.login(loginReq("password123"));

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getUser().getId()).isEqualTo(1L);
    }

    private UserRegisterReqVO registerReq() {
        UserRegisterReqVO reqVO = new UserRegisterReqVO();
        reqVO.setUsername("alice");
        reqVO.setPassword("password123");
        reqVO.setNickname("Alice");
        return reqVO;
    }

    private UserLoginReqVO loginReq(String password) {
        UserLoginReqVO reqVO = new UserLoginReqVO();
        reqVO.setUsername("alice");
        reqVO.setPassword(password);
        return reqVO;
    }

    private SysUser existingUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword("encoded-password");
        user.setNickname("Alice");
        user.setStatus(SysUser.STATUS_ENABLED);
        return user;
    }
}
