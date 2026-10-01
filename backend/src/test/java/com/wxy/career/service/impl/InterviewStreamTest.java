package com.wxy.career.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.AuthenticationInterceptor;
import com.wxy.career.common.auth.JwtService;
import com.wxy.career.common.auth.LoginTokenValidator;
import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.exception.GlobalExceptionHandler;
import com.wxy.career.common.redis.RedisUtil;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.config.InterviewProperties;
import com.wxy.career.config.MemoryProperties;
import com.wxy.career.controller.AssistantController;
import com.wxy.career.mapper.SysUserMapper;
import com.wxy.career.middleware.MetricsMiddleware;
import com.wxy.career.middleware.SystemPromptMiddleware;
import com.wxy.career.middleware.UserLongTermMemoryAdapter;
import com.wxy.career.po.SysUser;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.ChatSessionService;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.InterviewEvaluationService;
import com.wxy.career.service.InterviewReviewService;
import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.service.SystemPromptProvider;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.tool.GetInterviewStateTool;
import com.wxy.career.tool.ReadResumeTool;
import com.wxy.career.tool.RecordInterviewAnswerTool;
import com.wxy.career.tool.SubmitAnswerEvaluationTool;
import com.wxy.career.tool.SubmitInterviewReportTool;
import com.wxy.career.tool.SubmitResumeDiagnosisTool;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.InterviewReportRespVO;
import com.wxy.career.vo.InterviewResultRespVO;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 面试场景的对话通道测试。
 *
 * <p>面试复用同一条 SSE 通道，但必须做三件事：进流前按场景做准入校验（求职目标 1101、已结束 1501）、
 * meta 的 scene 下发 interview、流结束前用 result 事件下发面试进度与难度。模型用桩实现，
 * 会话状态用内存存储，因此不依赖 MySQL、Redis 与模型密钥。
 *
 * @author wxy
 * @date 2026-09-29
 */
class InterviewStreamTest {

    /**
     * SSE 等待超时，单位毫秒。
     */
    private static final long STREAM_WAIT_MILLIS = 10_000L;

    /**
     * 轮询间隔，单位毫秒。
     */
    private static final long POLL_INTERVAL_MILLIS = 20L;

    /**
     * 面试会话 ID。
     */
    private static final String SESSION_ID = "12";

    /**
     * MockMvc 测试入口。
     */
    private MockMvc mockMvc;

    /**
     * 面试流程服务 mock。
     */
    private InterviewFlowService interviewFlowService;

    /**
     * 面试复盘服务 mock：逐题点评与报告状态由它提供（F6）。
     */
    private InterviewReviewService interviewReviewService;

    /**
     * 记录模型收到的消息，用于校验走的是面试 Agent 的提示词。
     */
    private CapturingModel capturingModel;

    /**
     * 消息服务 mock。
     */
    private AssistantMessageService assistantMessageService;

    /**
     * JWT 服务。
     */
    private JwtService jwtService;

    /**
     * 初始化 MockMvc、Agent 装配与拦截器。
     */
    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setProvider("dashscope");
        agentProperties.setApiKey("test-key");
        agentProperties.setModel("stub-model");
        agentProperties.setMaxIters(6);
        agentProperties.setStreamTimeoutSeconds(60L);

        SysUserMapper sysUserMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setNickname("Alice");
        when(sysUserMapper.selectById(1L)).thenReturn(user);

        SystemPromptProvider systemPromptProvider = agentId -> "测试系统提示词:" + agentId;
        SystemPromptMiddleware systemPromptMiddleware = new SystemPromptMiddleware();
        ReflectionTestUtils.setField(systemPromptMiddleware, "systemPromptProvider", systemPromptProvider);
        ReflectionTestUtils.setField(systemPromptMiddleware, "sysUserMapper", sysUserMapper);
        UserProfileService userProfileService = mock(UserProfileService.class);
        ReflectionTestUtils.setField(systemPromptMiddleware, "userProfileService", userProfileService);

        RedisUtil redisUtil = mock(RedisUtil.class);
        when(redisUtil.getHash(anyString(), anyString(), eq(Long.class))).thenReturn(null);
        MetricsMiddleware metricsMiddleware = new MetricsMiddleware();
        ReflectionTestUtils.setField(metricsMiddleware, "redisUtil", redisUtil);

        // 会话锁可获取：Redisson 锁桩返回抢占成功，释放时按持有者标识解锁。
        RLock sessionLock = acquiredLock();
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getLock(anyString())).thenReturn(sessionLock);

        capturingModel = new CapturingModel();
        AgentFactoryImpl agentFactory = new AgentFactoryImpl();
        ReflectionTestUtils.setField(agentFactory, "agentModel", capturingModel);
        ReflectionTestUtils.setField(agentFactory, "agentStateStore", new InMemoryAgentStateStore());
        ReflectionTestUtils.setField(agentFactory, "agentProperties", agentProperties);
        ReflectionTestUtils.setField(agentFactory, "systemPromptProvider", systemPromptProvider);
        ReflectionTestUtils.setField(agentFactory, "systemPromptMiddleware", systemPromptMiddleware);
        ReflectionTestUtils.setField(agentFactory, "metricsMiddleware", metricsMiddleware);
        ReflectionTestUtils.setField(agentFactory, "agentSkillRepository", mock(AgentSkillRepository.class));
        ReflectionTestUtils.setField(agentFactory, "readResumeTool", new ReadResumeTool());
        ReflectionTestUtils.setField(
                agentFactory, "submitResumeDiagnosisTool", new SubmitResumeDiagnosisTool());
        ReflectionTestUtils.setField(agentFactory, "getInterviewStateTool", new GetInterviewStateTool());
        ReflectionTestUtils.setField(
                agentFactory, "recordInterviewAnswerTool", new RecordInterviewAnswerTool());
        ReflectionTestUtils.setField(
                agentFactory, "submitAnswerEvaluationTool", new SubmitAnswerEvaluationTool());
        ReflectionTestUtils.setField(agentFactory, "interviewProperties", new InterviewProperties());
        // F6：长期记忆适配层与报告提交工具同样要装配；测试里关掉记忆读写，避免依赖 Mem0 服务。
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setEnabled(false);
        UserLongTermMemoryAdapter userLongTermMemoryAdapter = new UserLongTermMemoryAdapter();
        ReflectionTestUtils.setField(userLongTermMemoryAdapter, "memoryProperties", memoryProperties);
        ReflectionTestUtils.setField(agentFactory, "userLongTermMemoryAdapter", userLongTermMemoryAdapter);
        ReflectionTestUtils.setField(agentFactory, "submitInterviewReportTool", new SubmitInterviewReportTool());

        assistantMessageService = mock(AssistantMessageService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();

        interviewFlowService = mock(InterviewFlowService.class);
        interviewReviewService = mock(InterviewReviewService.class);

        AssistantServiceImpl assistantService = new AssistantServiceImpl();
        ReflectionTestUtils.setField(assistantService, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(assistantService, "agentProperties", agentProperties);
        ReflectionTestUtils.setField(assistantService, "assistantMessageService", assistantMessageService);
        ReflectionTestUtils.setField(assistantService, "chatSessionService", chatSessionService);
        ReflectionTestUtils.setField(assistantService, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(assistantService, "sseTaskScheduler", scheduler);
        ReflectionTestUtils.setField(assistantService, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(
                assistantService, "resumeDiagnosisService", mock(ResumeDiagnosisService.class));
        ReflectionTestUtils.setField(assistantService, "interviewFlowService", interviewFlowService);
        ReflectionTestUtils.setField(
                assistantService, "interviewEvaluationService", mock(InterviewEvaluationService.class));
        ReflectionTestUtils.setField(assistantService, "interviewReviewService", interviewReviewService);

        AssistantController assistantController = new AssistantController();
        ReflectionTestUtils.setField(assistantController, "assistantService", assistantService);

        jwtService = buildJwtService();
        mockMvc = MockMvcBuilders.standaloneSetup(assistantController)
                .addInterceptors(buildAuthenticationInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 面试回合：meta 下发 interview、走面试 Agent 的提示词、流结束前下发进度与难度。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldStreamInterviewMetaAndProgress() throws Exception {
        when(interviewFlowService.prepareTurn(1L, SESSION_ID, "开始面试")).thenReturn(buildState(1, 3, 1, false));
        // 落库后的最新状态：同一道题追问一层，难度从 L3 上调到 L4。
        when(interviewFlowService.commitTurn(1L, SESSION_ID)).thenReturn(buildState(1, 4, 2, false));

        MvcResult result = mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"content\":\"开始面试\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitStreamBody(result.getResponse());

        assertThat(body).startsWith("event:meta");
        assertThat(body).contains("\"scene\":\"interview\"");
        assertThat(body).contains("\"sessionId\":\"12\"");
        // 走的是面试 Agent（按场景选 Agent），不是通用助手。
        assertThat(capturingModel.systemPrompt()).contains("测试系统提示词:interviewer");
        // 进度与难度在 done 之前下发，用既有 result 事件承载。
        assertThat(body).contains("event:result");
        assertThat(body).contains("\"type\":\"interview_progress\"");
        assertThat(body).contains("\"difficulty\":4");
        assertThat(body.indexOf("event:result")).isLessThan(body.indexOf("event:done"));
        verify(interviewFlowService).commitTurn(1L, SESSION_ID);
        verify(assistantMessageService).saveMessage(1L, 12L, MessageRoleEnum.USER, "开始面试");
    }

    /**
     * 面试结束：同一轮里先下发进度、再下发逐题结果（哪里答得不好 + 标准答案），界面据此渲染结果卡片。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldSendInterviewResultWhenFinished() throws Exception {
        when(interviewFlowService.prepareTurn(1L, SESSION_ID, "最后一题的回答"))
                .thenReturn(buildState(8, 4, 1, false));
        when(interviewFlowService.commitTurn(1L, SESSION_ID)).thenReturn(buildState(8, 4, 1, true));
        InterviewResultRespVO result = new InterviewResultRespVO();
        result.setSessionId(SESSION_ID);
        result.setQuestionCount(8);
        result.setAnsweredCount(8);
        result.setFinished(true);
        result.setItems(List.of());
        when(interviewFlowService.getResult(1L, SESSION_ID)).thenReturn(result);

        MvcResult mvcResult = mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"content\":\"最后一题的回答\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitStreamBody(mvcResult.getResponse());

        assertThat(body).contains("\"type\":\"interview_progress\"");
        assertThat(body).contains("\"type\":\"interview_result\"");
        // 先进度后结果，都在 done 之前。
        assertThat(body.indexOf("interview_progress")).isLessThan(body.indexOf("interview_result"));
        assertThat(body.indexOf("interview_result")).isLessThan(body.indexOf("event:done"));
    }

    /**
     * 面试结束那一轮：进度 → 逐题结果（结束时一次性给逐题点评）→ 报告状态（生成中）依次在 done 之前下发。
     *
     * <p>答题过程中不插点评，用户不会被点评打断；报告由后台任务生成，面板先拿到「生成中」再轮询。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldSendResultAndReportStateWhenFinished() throws Exception {
        when(interviewFlowService.prepareTurn(1L, SESSION_ID, "最后一题的回答"))
                .thenReturn(buildState(8, 4, 1, false));
        when(interviewFlowService.commitTurn(1L, SESSION_ID)).thenReturn(buildState(8, 4, 1, true));
        InterviewResultRespVO result = new InterviewResultRespVO();
        result.setSessionId(SESSION_ID);
        result.setQuestionCount(8);
        result.setAnsweredCount(8);
        result.setFinished(true);
        result.setItems(List.of());
        when(interviewFlowService.getResult(1L, SESSION_ID)).thenReturn(result);

        InterviewReportRespVO reportState = new InterviewReportRespVO();
        reportState.setSessionId(SESSION_ID);
        reportState.setStatus("GENERATING");
        reportState.setStatusLabel("报告生成中");
        reportState.setCanRetry(false);
        when(interviewReviewService.afterTurnCommitted(1L, SESSION_ID, true)).thenReturn(reportState);

        MvcResult mvcResult = mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"content\":\"最后一题的回答\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = awaitStreamBody(mvcResult.getResponse());

        assertThat(body).contains("\"type\":\"interview_report\"");
        assertThat(body).contains("\"status\":\"GENERATING\"");
        // 逐题点评不再单独下发：答题过程中没有 result 事件，点评随结束时的逐题结果一起给
        assertThat(body).doesNotContain("interview_evaluation");
        assertThat(body.indexOf("interview_progress")).isLessThan(body.indexOf("interview_result"));
        assertThat(body.indexOf("interview_result")).isLessThan(body.indexOf("interview_report"));
        assertThat(body.indexOf("interview_report")).isLessThan(body.indexOf("event:done"));
        // 掌握度沉淀与报告派发都发生在同一轮里，顺序由复盘服务保证
        verify(interviewReviewService).afterTurnCommitted(1L, SESSION_ID, true);
    }

    /**
     * 面试已结束：进流前就被拦住，返回业务码 1501，不进入流。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRejectFinishedInterviewBeforeStream() throws Exception {
        when(interviewFlowService.prepareTurn(1L, SESSION_ID, "还能再问一题吗"))
                .thenThrow(new BizException(ErrorConstant.INTERVIEW_FINISHED));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"content\":\"还能再问一题吗\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1501))
                .andExpect(jsonPath("$.msg").value("本场面试已结束"));
    }

    /**
     * 求职目标未填写：进面试前按 F4 的流程拦住，返回业务码 1101。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldRejectInterviewWithoutProfile() throws Exception {
        when(interviewFlowService.prepareTurn(1L, SESSION_ID, "开始面试"))
                .thenThrow(new BizException(ErrorConstant.USER_PROFILE_REQUIRED));

        mockMvc.perform(post("/api/assistant/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION_ID + "\",\"content\":\"开始面试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1101));
    }

    /**
     * 构造面试状态。
     *
     * @param questionIndex 题序
     * @param difficulty 难度
     * @param roundNo 轮次
     * @param finished 是否已结束
     * @return 面试状态
     */
    private InterviewStateRespVO buildState(int questionIndex, int difficulty, int roundNo, boolean finished) {
        InterviewStateRespVO state = new InterviewStateRespVO();
        state.setSessionId(SESSION_ID);
        state.setQuestionIndex(questionIndex);
        state.setQuestionCount(8);
        state.setDifficulty(difficulty);
        state.setRoundNo(roundNo);
        state.setFinished(finished);
        state.setStartDifficulty(3);
        return state;
    }

    /**
     * 构造一把「抢占成功」的 Redisson 锁桩。
     *
     * @return 锁桩
     */
    private static RLock acquiredLock() {
        RLock lock = mock(RLock.class);
        when(lock.tryLockAsync(ArgumentMatchers.anyLong())).thenReturn(completedFuture(Boolean.TRUE));
        when(lock.unlockAsync(ArgumentMatchers.anyLong())).thenReturn(completedFuture(null));
        return lock;
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
     * 测试用桩模型：记录收到的系统提示词并返回固定文本，不访问模型服务。
     *
     * @author wxy
     * @date 2026-09-29
     */
    private static final class CapturingModel implements Model {

        /**
         * 最近一次调用收到的上下文消息。
         */
        private final List<Msg> received = new ArrayList<>();

        /**
         * 取出记录到的系统提示词。
         *
         * @return 系统提示词，未记录到时返回空串
         */
        String systemPrompt() {
            for (Msg message : received) {
                if (message.getRole() == MsgRole.SYSTEM && message.getTextContent() != null) {
                    return message.getTextContent();
                }
            }
            return "";
        }

        /**
         * 记录消息并返回固定文本。
         *
         * @param messages 上下文消息
         * @param tools 可用工具
         * @param options 生成参数
         * @return 固定文本响应流
         */
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            received.clear();
            received.addAll(messages);
            return Flux.fromArray(new String[]{"正在", "提问"})
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
