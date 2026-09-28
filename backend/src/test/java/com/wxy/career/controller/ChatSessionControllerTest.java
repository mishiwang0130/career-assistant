package com.wxy.career.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.AuthenticationInterceptor;
import com.wxy.career.common.auth.JwtService;
import com.wxy.career.common.auth.LoginTokenValidator;
import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.exception.GlobalExceptionHandler;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.ChatSessionService;
import com.wxy.career.vo.ChatSessionRespVO;
import com.wxy.career.vo.PageRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话中心接口测试。
 *
 * <p>服务层使用 mock，只验证接口契约、鉴权与参数校验，不依赖 MySQL、Redis 与模型配置。
 *
 * @author wxy
 * @date 2026-09-28
 */
class ChatSessionControllerTest {

    /**
     * MockMvc 测试入口。
     */
    private MockMvc mockMvc;

    /**
     * 会话中心服务 mock。
     */
    private ChatSessionService chatSessionService;

    /**
     * JWT 服务。
     */
    private JwtService jwtService;

    /**
     * 初始化 MockMvc、服务 mock 与鉴权拦截器。
     */
    @BeforeEach
    void setUp() {
        chatSessionService = mock(ChatSessionService.class);
        ChatSessionController controller = new ChatSessionController();
        ReflectionTestUtils.setField(controller, "chatSessionService", chatSessionService);

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
     * 验证新建会话返回后端生成的会话 ID。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldCreateSession() throws Exception {
        when(chatSessionService.create(any())).thenReturn(
                new ChatSessionRespVO("12", "新会话", "ASSISTANT", LocalDateTime.now()));

        mockMvc.perform(post("/api/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"ASSISTANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("12"))
                .andExpect(jsonPath("$.data.scene").value("ASSISTANT"));
    }

    /**
     * 验证未携带令牌时返回 401。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"ASSISTANT\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 验证场景为空时参数校验失败返回 400。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnParamErrorWhenSceneBlank() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 验证未开放场景返回业务错误码 1052，HTTP 仍为 200。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnUnsupportedSceneCode() throws Exception {
        when(chatSessionService.create(any()))
                .thenThrow(new BizException(ErrorConstant.CHAT_SCENE_UNSUPPORTED));

        mockMvc.perform(post("/api/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scene\":\"INTERVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1052));
    }

    /**
     * 验证会话列表按分页参数查询并返回分页结构。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldListSessionsWithPaging() throws Exception {
        when(chatSessionService.list(2L, 5L)).thenReturn(PageRespVO.of(
                6L, 2L, 5L, List.of(new ChatSessionRespVO("12", "新会话", "ASSISTANT", LocalDateTime.now()))));

        mockMvc.perform(get("/api/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .param("pageNum", "2")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(5))
                .andExpect(jsonPath("$.data.records[0].sessionId").value("12"));

        verify(chatSessionService).list(2L, 5L);
    }

    /**
     * 验证未传分页参数时使用默认页码与每页条数。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldUseDefaultPagingParameters() throws Exception {
        when(chatSessionService.list(1L, 20L)).thenReturn(PageRespVO.of(0L, 1L, 20L, List.of()));

        mockMvc.perform(get("/api/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isEmpty());

        verify(chatSessionService).list(1L, 20L);
    }

    /**
     * 验证重命名返回归一化后的标题。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRenameSession() throws Exception {
        when(chatSessionService.rename(eq("9"), any())).thenReturn(
                new ChatSessionRespVO("9", "Java 并发问题", "ASSISTANT", LocalDateTime.now()));

        mockMvc.perform(patch("/api/sessions/9")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  Java 并发问题  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Java 并发问题"));
    }

    /**
     * 验证重命名时空标题被参数校验拦截。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnParamErrorWhenTitleBlank() throws Exception {
        mockMvc.perform(patch("/api/sessions/9")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 验证重命名不存在的会话返回业务错误码 1051。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnSessionNotFoundCodeOnRename() throws Exception {
        when(chatSessionService.rename(eq("9"), any()))
                .thenThrow(new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND));

        mockMvc.perform(patch("/api/sessions/9")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1051));
    }

    /**
     * 验证删除会话调用服务层。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldDeleteSession() throws Exception {
        mockMvc.perform(delete("/api/sessions/9")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(chatSessionService).delete("9");
    }

    /**
     * 签发测试用 Access Token。
     *
     * @return Access Token
     */
    private String accessToken() {
        return jwtService.generateToken(1L, "alice", "jti-1");
    }
}
