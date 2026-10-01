package com.wxy.career.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.AuthenticationInterceptor;
import com.wxy.career.common.auth.JwtService;
import com.wxy.career.common.auth.LoginTokenValidator;
import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.common.exception.GlobalExceptionHandler;
import com.wxy.career.common.redis.RedisUtil;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.middleware.MetricsMiddleware;
import com.wxy.career.middleware.SystemPromptMiddleware;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.ChatSessionService;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.service.SystemPromptProvider;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.service.impl.AgentFactoryImpl;
import com.wxy.career.service.impl.AssistantServiceImpl;
import com.wxy.career.tool.ReadResumeTool;
import com.wxy.career.tool.SubmitResumeDiagnosisTool;
import com.wxy.career.vo.ResumeDiagnosisResultVO;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通用助手 SSE 接口测试。
 *
 * <p>模型使用测试内桩实现（产品代码没有 mock 分支），会话状态使用内存存储，因此用例完全不依赖
 * MySQL、Redis 与 DASHSCOPE_API_KEY。
 *
 * @author wxy
 * @date 2026-09-28
 */
class AssistantControllerSseTest {

    /**
     * SSE 等待超时，单位毫秒。
     */
    private static final long STREAM_WAIT_MILLIS = 10_000L;

    /**
     * 轮询间隔，单位毫秒。
     */
    private static final long POLL_INTERVAL_MILLIS = 20L;

    /**
     * MockMvc 测试入口。
     */
    private MockMvc mockMvc;

    /**
     * 消息服务 mock。
     */
    private AssistantMessageService assistantMessageService;

    /**
     * 会话中心服务 mock，用于校验自动标题与活跃时间回调。
     */
    private ChatSessionService chatSessionService;

    /**
     * JWT 服务。
     */
    private JwtService jwtService;

    /**
     * Redis 操作工具 mock，用于埋点计数。
     */
    private RedisUtil redisUtil;

    /**
     * 会话并发锁桩，用于断言同一会话的并发请求被拒绝。
     */
    private RLock sessionLock;

    /**
     * 简历诊断服务 mock，用于验证流结束前的结构化结果下发。
     */
    private ResumeDiagnosisService resumeDiagnosisService;

    /**
     * 初始化 MockMvc、Agent 装配与拦截器。
     */
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentProperties agentProperties = buildAgentProperties();

        SysUserMapper sysUserMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("alice");
        user.setNickname("Alice");
        when(sysUserMapper.selectById(1L)).thenReturn(user);

        SystemPromptProvider systemPromptProvider = agentId -> "测试系统提示词";
        SystemPromptMiddleware systemPromptMiddleware = new SystemPromptMiddleware();
        ReflectionTestUtils.setField(systemPromptMiddleware, "systemPromptProvider", systemPromptProvider);
        ReflectionTestUtils.setField(systemPromptMiddleware, "sysUserMapper", sysUserMapper);
        UserProfileService userProfileService = mock(UserProfileService.class);
        ReflectionTestUtils.setField(systemPromptMiddleware, "userProfileService", userProfileService);

        redisUtil = mock(RedisUtil.class);
        when(redisUtil.getHash(anyString(), anyString(), eq(Long.class))).thenReturn(null);
        MetricsMiddleware metricsMiddleware = new MetricsMiddleware();
        ReflectionTestUtils.setField(metricsMiddleware, "redisUtil", redisUtil);

        // 默认会话锁可获取，并发拒绝场景在用例内重新打桩。
        sessionLock = mock(RLock.class);
        when(sessionLock.tryLockAsync(ArgumentMatchers.anyLong())).thenReturn(completedFuture(Boolean.TRUE));
        when(sessionLock.unlockAsync(ArgumentMatchers.anyLong())).thenReturn(completedFuture(null));
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getLock(anyString())).thenReturn(sessionLock);

        AgentFactoryImpl agentFactory = new AgentFactoryImpl();
        ReflectionTestUtils.setField(agentFactory, "agentModel", new StubChatModel());
        ReflectionTestUtils.setField(agentFactory, "agentStateStore", new InMemoryAgentStateStore());
        ReflectionTestUtils.setField(agentFactory, "agentProperties", agentProperties);
        ReflectionTestUtils.setField(agentFactory, "systemPromptProvider", systemPromptProvider);
        ReflectionTestUtils.setField(agentFactory, "systemPromptMiddleware", systemPromptMiddleware);
        ReflectionTestUtils.setField(agentFactory, "metricsMiddleware", metricsMiddleware);
        ReflectionTestUtils.setField(agentFactory, "agentSkillRepository", mock(AgentSkillRepository.class));
        ReflectionTestUtils.setField(agentFactory, "readResumeTool", new ReadResumeTool());
        ReflectionTestUtils.setField(
                agentFactory, "submitResumeDiagnosisTool", new SubmitResumeDiagnosisTool());

        assistantMessageService = mock(AssistantMessageService.class);
        chatSessionService = mock(ChatSessionService.class);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        AssistantServiceImpl assistantService = new AssistantServiceImpl();
        ReflectionTestUtils.setField(assistantService, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(assistantService, "agentProperties", agentProperties);
        ReflectionTestUtils.setField(assistantService, "assistantMessageService", assistantMessageService);
        ReflectionTestUtils.setField(assistantService, "chatSessionService", chatSessionService);
        ReflectionTestUtils.setField(assistantService, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(assistantService, "sseTaskScheduler", scheduler);
        ReflectionTestUtils.setField(assistantService, "redissonClient", redissonClient);
        resumeDiagnosisService = mock(ResumeDiagnosisService.class);
        ReflectionTestUtils.setField(assistantService, "resumeDiagnosisService", resumeDiagnosisService);
        // F5：助手会话不是面试会话，面试流程服务返回 null，本轮仍走原对话链路。
        ReflectionTestUtils.setField(assistantService, "interviewFlowService", mock(InterviewFlowService.class));

        AssistantController assistantController = new AssistantController();
        ReflectionTestUtils.setField(assistantController, "assistantService", assistantService);

        jwtService = buildJwtService();
        mockMvc = MockMvcBuilders.standaloneSetup(assistantController)
                .addInterceptors(buildAuthenticationInterceptor())
                // standaloneSetup 不会自动扫描 @RestControllerAdvice，需要显式注册才能验证 Result 错误体。
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 验证 mock 模型下的事件序列：meta → tool(START/END) → delta… → done。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldStreamMetaToolDeltaAndDone() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"1\",\"content\":\"你好\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitStreamBody(result.getResponse());

        assertThat(body).startsWith("event:meta");
        assertThat(body).contains("\"scene\":\"assistant\"");
        assertThat(body).contains("\"sessionId\":\"1\"");
        assertThat(body).contains("\"provider\":\"dashscope\"");
        // 桩模型不调用工具，流里不应出现工具调用事件。
        assertThat(body).doesNotContain("event:tool");
        assertThat(body).contains("event:delta");
        assertThat(body).endsWith("event:done\ndata:{}\n\n");
        assertThat(body).doesNotContain("event:error");
        // 纯文本问答不发 result：result 只承载结构化产物。
        assertThat(body).doesNotContain("event:result");

        // 用户消息与助手回复都必须落库，助手回复内容为全部文本增量拼接。
        verify(assistantMessageService).saveMessage(1L, 1L, MessageRoleEnum.USER, "你好");
        verify(assistantMessageService).saveMessage(
                eq(1L), eq(1L), eq(MessageRoleEnum.ASSISTANT), ArgumentMatchers.contains("桩模型回复"));
        // 保存用户消息后必须回写会话标题与活跃时间，左侧列表才能显示首条消息标题。
        verify(chatSessionService).recordUserMessage(1L, "1", "你好");
    }

    /**
     * 验证有结构化诊断结论时，流在 done 之前下发 result 事件。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldSendStructuredResultBeforeDone() throws Exception {
        ResumeDiagnosisResultVO diagnosis = new ResumeDiagnosisResultVO();
        diagnosis.setResumeId(5L);
        diagnosis.setResumeTitle("Java 开发简历");
        diagnosis.setOverallScore(72);
        when(resumeDiagnosisService.consumeDiagnosis(1L, "1")).thenReturn(diagnosis);

        MvcResult result = mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"1\",\"content\":\"帮我诊断简历\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitStreamBody(result.getResponse());

        assertThat(body).contains("event:result");
        assertThat(body).contains("\"type\":\"resume_diagnosis\"");
        assertThat(body).contains("\"overallScore\":72");
        // result 属于本次回答的一部分，必须排在 done 之前。
        assertThat(body.indexOf("event:result")).isLessThan(body.indexOf("event:done"));
    }

    /**
     * 验证未登录时仍然返回统一 Result 包装的 401。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnUnauthorizedResultBeforeStreamStarts() throws Exception {
        mockMvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"1\",\"content\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("未登录或登录已过期"));
    }

    /**
     * 验证参数非法时仍然返回统一 Result 包装的 400。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnParamErrorBeforeStreamStarts() throws Exception {
        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"1\",\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 验证会话 ID 含通配符时被参数校验拦截。
     *
     * <p>会话 ID 会参与 Redis key 拼接与 SCAN 模式匹配，必须限制字符集。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRejectSessionIdWithWildcard() throws Exception {
        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"*\",\"content\":\"你好\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    /**
     * 验证旧的清空会话接口已经下线，清空语义并入删除会话。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRejectRemovedClearSessionEndpoint() throws Exception {
        mockMvc.perform(delete("/api/assistant/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .param("sessionId", "1"))
                .andExpect(status().isNotFound());
    }

    /**
     * 验证同一会话并发请求被拒绝，避免两份上下文交错写入。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRejectConcurrentRequestOnSameSession() throws Exception {
        // 锁被占用：Redisson 抢占失败，直接以 1050 拒绝，不排队等待。
        when(sessionLock.tryLockAsync(ArgumentMatchers.anyLong())).thenReturn(completedFuture(Boolean.FALSE));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"1\",\"content\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1050));
    }

    /**
     * 构造已完成的 Redisson 异步结果桩。
     *
     * @param value 结果值
     * @param <T> 结果类型
     * @return 已完成的异步结果
     */
    private static <T> RFuture<T> completedFuture(T value) {
        CompletableFuture<T> future = CompletableFuture.completedFuture(value);
        return new CompletableFutureWrapper<T>(future);
    }

    /**
     * 等待 SSE 流结束并返回响应报文。
     *
     * @param response Mock 响应
     * @return 响应报文
     * @throws Exception 读取响应异常
     */
    private String awaitStreamBody(MockHttpServletResponse response) throws Exception {
        long deadline = System.currentTimeMillis() + STREAM_WAIT_MILLIS;
        String body = decodeBody(response);
        while (System.currentTimeMillis() < deadline) {
            body = decodeBody(response);
            if (body.contains("event:done") || body.contains("event:error")) {
                return body;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return body;
    }

    /**
     * 按 UTF-8 解码响应报文。
     *
     * <p>MockMvc 的响应默认字符集不是 UTF-8，直接使用 getContentAsString 会把中文解码成乱码。
     *
     * @param response Mock 响应
     * @return 响应报文
     */
    private String decodeBody(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
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
     * 构建 Agent 配置。
     *
     * @return Agent 配置
     */
    private AgentProperties buildAgentProperties() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setProvider("dashscope");
        agentProperties.setApiKey("test-key");
        agentProperties.setModel("stub-model");
        agentProperties.setMaxIters(6);
        agentProperties.setStreamTimeoutSeconds(60L);
        return agentProperties;
    }

    /**
     * 构建 JWT 服务。
     *
     * @return JWT 服务
     */
    private JwtService buildJwtService() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("career-assistant-test-secret-change-me");
        jwtProperties.setAccessTokenExpireMinutes(120L);
        jwtProperties.setRefreshTokenExpireDays(7L);
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "jwtProperties", jwtProperties);
        return service;
    }

    /**
     * 构建登录态拦截器。
     *
     * @return 登录态拦截器
     */
    private AuthenticationInterceptor buildAuthenticationInterceptor() {
        LoginTokenValidator loginTokenValidator = mock(LoginTokenValidator.class);
        when(loginTokenValidator.isValid(ArgumentMatchers.any())).thenReturn(true);
        AuthenticationInterceptor interceptor = new AuthenticationInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtService", jwtService);
        ReflectionTestUtils.setField(interceptor, "loginTokenValidator", loginTokenValidator);
        ReflectionTestUtils.setField(interceptor, "objectMapper", new ObjectMapper());
        return interceptor;
    }

    /**
     * 测试用桩模型：直接分片返回文本（本模块没有工具，不走工具调用轮）。
     *
     * @author wxy
     * @date 2026-09-28
     */
    private static final class StubChatModel implements Model {

        /**
         * 返回固定文本分片。
         *
         * @param messages 上下文消息
         * @param tools 可用工具
         * @param options 生成参数
         * @return 模型响应流
         */
        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.fromArray(new String[]{"这是", "桩模型回复"})
                    .map(chunk -> ChatResponse.builder()
                            .id("stub-text")
                            .content(List.of(TextBlock.builder().text(chunk).build()))
                            .build());
        }

        /**
         * 模型名。
         *
         * @return 模型名
         */
        @Override
        public String getModelName() {
            return "stub-model";
        }
    }
}
