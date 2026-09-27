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

/**
 * 账号服务测试。
 *
 * @author wxy
 * @date 2026-09-27
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    /**
     * 用户 Mapper。
     */
    @Mock
    private SysUserMapper sysUserMapper;

    /**
     * 密码编码器。
     */
    @Mock
    private PasswordEncoder passwordEncoder;

    /**
     * 令牌服务。
     */
    @Mock
    private TokenService tokenService;

    /**
     * 被测账号服务。
     */
    @InjectMocks
    private AuthServiceImpl authService;

    /**
     * 验证重复用户名返回 1001。
     */
    @Test
    void shouldRejectDuplicateUsername() {
        when(sysUserMapper.selectByUsername(any())).thenReturn(existingUser());
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

    /**
     * 验证密码错误返回 1002。
     */
    @Test
    void shouldRejectWrongPassword() {
        SysUser user = existingUser();
        when(sysUserMapper.selectByUsername(any())).thenReturn(user);
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

    /**
     * 验证禁用账号返回 1003。
     */
    @Test
    void shouldRejectDisabledAccount() {
        SysUser user = existingUser();
        user.setStatus(SysUser.STATUS_DISABLED);
        when(sysUserMapper.selectByUsername(any())).thenReturn(user);
        when(passwordEncoder.matches("password123", user.getPassword())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(loginReq("password123")))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> {
                    BizException bizException = (BizException) exception;
                    assertThat(bizException.getErrorCode().getCode()).isEqualTo(1003);
                    assertThat(bizException.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    /**
     * 验证注册成功后签发 token。
     */
    @Test
    void shouldRegisterAndIssueTokens() {
        when(sysUserMapper.selectByUsername(any())).thenReturn(null);
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

    /**
     * 验证登录成功后签发 token。
     */
    @Test
    void shouldLoginAndIssueTokens() {
        SysUser user = existingUser();
        when(sysUserMapper.selectByUsername(any())).thenReturn(user);
        when(passwordEncoder.matches("password123", user.getPassword())).thenReturn(true);
        when(tokenService.issueTokens(user)).thenReturn(new TokenPairVO("access-token", "refresh-token", 7200L));

        AuthRespVO response = authService.login(loginReq("password123"));

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getUser().getId()).isEqualTo(1L);
    }

    /**
     * 构建注册请求。
     *
     * @return 注册请求
     */
    private UserRegisterReqVO registerReq() {
        UserRegisterReqVO reqVO = new UserRegisterReqVO();
        reqVO.setUsername("alice");
        reqVO.setPassword("password123");
        reqVO.setNickname("Alice");
        return reqVO;
    }

    /**
     * 构建登录请求。
     *
     * @param password 登录密码
     * @return 登录请求
     */
    private UserLoginReqVO loginReq(String password) {
        UserLoginReqVO reqVO = new UserLoginReqVO();
        reqVO.setUsername("alice");
        reqVO.setPassword(password);
        return reqVO;
    }

    /**
     * 构建已有用户。
     *
     * @return 用户实体
     */
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
