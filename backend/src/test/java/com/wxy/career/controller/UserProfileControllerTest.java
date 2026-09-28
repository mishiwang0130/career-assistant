package com.wxy.career.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.AuthenticationInterceptor;
import com.wxy.career.common.auth.JwtService;
import com.wxy.career.common.auth.LoginTokenValidator;
import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.GlobalExceptionHandler;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.UserProfileRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 求职目标接口测试。
 *
 * <p>服务层使用 mock，只验证接口契约、鉴权与参数校验：未填写返回 data 为 null、保存后能取回、
 * 非法参数 400、未登录 401。用例不依赖 MySQL、Redis 与模型配置。
 *
 * @author wxy
 * @date 2026-09-28
 */
class UserProfileControllerTest {

    /**
     * MockMvc 测试入口。
     */
    private MockMvc mockMvc;

    /**
     * 求职目标服务 mock。
     */
    private UserProfileService userProfileService;

    /**
     * JWT 服务。
     */
    private JwtService jwtService;

    /**
     * 初始化 MockMvc、服务 mock 与鉴权拦截器。
     */
    @BeforeEach
    void setUp() {
        userProfileService = mock(UserProfileService.class);
        UserProfileController controller = new UserProfileController();
        ReflectionTestUtils.setField(controller, "userProfileService", userProfileService);

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("career-assistant-test-secret-change-me");
        jwtProperties.setAccessTokenExpireMinutes(120L);
        jwtProperties.setRefreshTokenExpireDays(7L);
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtProperties", jwtProperties);

        LoginTokenValidator loginTokenValidator = mock(LoginTokenValidator.class);
        when(loginTokenValidator.isValid(any())).thenReturn(true);
        AuthenticationInterceptor interceptor = new AuthenticationInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtService", jwtService);
        ReflectionTestUtils.setField(interceptor, "loginTokenValidator", loginTokenValidator);
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 验证未填写时返回 code=200 且 data 为 null，前端据此提示补填。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnNullDataWhenProfileMissing() throws Exception {
        when(userProfileService.getCurrentUserProfile()).thenReturn(null);

        mockMvc.perform(get("/api/user-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 验证保存后能再取回同一份档案。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnSavedProfileAfterSave() throws Exception {
        UserProfileRespVO saved = buildProfile("后端开发", 3);
        when(userProfileService.saveCurrentUserProfile(any())).thenReturn(saved);
        when(userProfileService.getCurrentUserProfile()).thenReturn(saved);

        mockMvc.perform(put("/api/user-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPosition\":\"后端开发\",\"workYears\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetPosition").value("后端开发"))
                .andExpect(jsonPath("$.data.workYears").value(3));

        mockMvc.perform(get("/api/user-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetPosition").value("后端开发"))
                .andExpect(jsonPath("$.data.workYears").value(3));
    }

    /**
     * 验证目标岗位为空时返回参数校验失败。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRejectBlankTargetPosition() throws Exception {
        mockMvc.perform(put("/api/user-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPosition\":\"   \",\"workYears\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(userProfileService, never()).saveCurrentUserProfile(any());
    }

    /**
     * 验证工作年限超出 0–60 时返回参数校验失败。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRejectWorkYearsOutOfRange() throws Exception {
        mockMvc.perform(put("/api/user-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPosition\":\"后端开发\",\"workYears\":61}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 验证缺少工作年限时返回参数校验失败。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRejectMissingWorkYears() throws Exception {
        mockMvc.perform(put("/api/user-profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetPosition\":\"后端开发\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 验证未携带令牌时返回统一 Result 包装的 401。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/user-profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(userProfileService, never()).getCurrentUserProfile();
        verify(userProfileService, never()).getUserProfileByUserId(anyLong());
    }

    /**
     * 签发测试用 Access Token。
     *
     * @return Access Token
     */
    private String accessToken() {
        return jwtService.generateToken(1L, "alice", "jti-1");
    }

    /**
     * 构造求职目标响应。
     *
     * @param targetPosition 目标岗位
     * @param workYears 当前工作年限
     * @return 求职目标响应
     */
    private UserProfileRespVO buildProfile(String targetPosition, Integer workYears) {
        UserProfileRespVO respVO = new UserProfileRespVO();
        respVO.setTargetPosition(targetPosition);
        respVO.setWorkYears(workYears);
        return respVO;
    }
}
