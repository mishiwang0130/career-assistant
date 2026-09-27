package com.wxy.career.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.AuthenticationInterceptor;
import com.wxy.career.common.auth.JwtService;
import com.wxy.career.common.auth.LoginTokenValidator;
import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.service.AuthService;
import com.wxy.career.vo.UserInfoRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private MockMvc mockMvc;

    private AuthService authService;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        AuthController authController = new AuthController();
        ReflectionTestUtils.setField(authController, "authService", authService);

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("career-assistant-test-secret-change-me");
        jwtProperties.setAccessTokenExpireMinutes(120L);
        jwtProperties.setRefreshTokenExpireDays(7L);
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtProperties", jwtProperties);

        LoginTokenValidator loginTokenValidator = mock(LoginTokenValidator.class);
        when(loginTokenValidator.isValid(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        AuthenticationInterceptor interceptor = new AuthenticationInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtService", jwtService);
        ReflectionTestUtils.setField(interceptor, "loginTokenValidator", loginTokenValidator);
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());

        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/auth/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("未登录或登录已过期"));
    }

    @Test
    void shouldReturnCurrentUserWithValidToken() throws Exception {
        when(authService.getCurrentUser()).thenReturn(new UserInfoRespVO(1L, "alice", "Alice"));
        String token = jwtService.generateToken(1L, "alice", "jti-1");

        mockMvc.perform(get("/api/auth/info")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.nickname").value("Alice"));
    }
}
