package com.wxy.career.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.AuthenticationInterceptor;
import com.wxy.career.common.auth.JwtService;
import com.wxy.career.common.auth.LoginTokenValidator;
import com.wxy.career.common.config.JwtProperties;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.exception.GlobalExceptionHandler;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.InterviewReportService;
import com.wxy.career.vo.AssistantChatReqVO;
import com.wxy.career.vo.InterviewReportRespVO;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.InterviewResultItemVO;
import com.wxy.career.vo.InterviewResultRespVO;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 面试状态接口测试。
 *
 * <p>只看接口契约：进流前统一返回 Result（未登录 401、参数非法 400、业务异常 HTTP 200 + 业务码），
 * 会话不存在 1051、不是模拟面试 1502。不启动 Spring 上下文，也不连数据库。
 *
 * @author wxy
 * @date 2026-09-29
 */
class InterviewControllerTest {

    /**
     * MockMvc 测试入口。
     */
    private MockMvc mockMvc;

    /**
     * 面试流程服务 mock。
     */
    private InterviewFlowService interviewFlowService;

    /**
     * 面试报告服务 mock（F6）。
     */
    private InterviewReportService interviewReportService;

    /**
     * JWT 服务。
     */
    private JwtService jwtService;

    /**
     * 初始化被测控制器与拦截器。
     */
    @BeforeEach
    void setUp() {
        interviewFlowService = mock(InterviewFlowService.class);
        interviewReportService = mock(InterviewReportService.class);
        InterviewController controller = new InterviewController();
        ReflectionTestUtils.setField(controller, "interviewFlowService", interviewFlowService);
        ReflectionTestUtils.setField(controller, "interviewReportService", interviewReportService);

        jwtService = buildJwtService();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(buildAuthenticationInterceptor())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 正常返回进度快照：前端据此渲染「第 n 题 / 共 N 题、当前难度」。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnInterviewState() throws Exception {
        InterviewStateRespVO state = new InterviewStateRespVO();
        state.setSessionId("12");
        state.setQuestionIndex(3);
        state.setQuestionCount(8);
        state.setDifficulty(4);
        state.setRoundNo(2);
        state.setFinished(false);
        state.setStartDifficulty(3);
        state.setRecommendedQuestionType("BASIC");
        when(interviewFlowService.getCurrentUserState("12")).thenReturn(state);

        mockMvc.perform(get("/api/interviews/12")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sessionId").value("12"))
                .andExpect(jsonPath("$.data.questionIndex").value(3))
                .andExpect(jsonPath("$.data.questionCount").value(8))
                .andExpect(jsonPath("$.data.difficulty").value(4))
                .andExpect(jsonPath("$.data.finished").value(false));
    }

    /**
     * 读取面试结果：逐题明细（哪里答得不好、标准答案）与整体统计。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnInterviewResult() throws Exception {
        InterviewResultRespVO result = new InterviewResultRespVO();
        result.setSessionId("12");
        result.setQuestionCount(8);
        result.setAnsweredCount(1);
        result.setPartialCount(1);
        result.setAverageScore(80);
        result.setFinished(true);
        InterviewResultItemVO item = new InterviewResultItemVO();
        item.setQuestionIndex(1);
        item.setRoundNo(1);
        item.setOutcome("PARTIAL");
        item.setMissingPoints(List.of("漏了树化条件"));
        item.setReferenceAnswer("标准答案：数组+链表+红黑树；链表长度>8 且容量≥64 时树化");
        result.setItems(List.of(item));
        when(interviewFlowService.getCurrentUserResult("12")).thenReturn(result);

        mockMvc.perform(get("/api/interviews/12/result")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("interview_result"))
                .andExpect(jsonPath("$.data.items[0].missingPoints[0]").value("漏了树化条件"))
                .andExpect(jsonPath("$.data.items[0].referenceAnswer")
                        .value("标准答案：数组+链表+红黑树；链表长度>8 且容量≥64 时树化"));
    }

    /**
     * 未登录：拦截器直接返回 HTTP 401 与统一 Result。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/interviews/12"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /**
     * 会话 ID 的字符集约束与其它会话接口一致。
     *
     * <p>会话 ID 会参与 Redis key 拼接与 SCAN 模式匹配，必须只允许数字。参数级校验由 Spring 的方法校验
     * 在完整上下文里生效（standalone 的 MockMvc 不启用它），因此这里用注解断言把契约固定下来。
     *
     * @throws Exception 反射读取控制器方法失败
     */
    @Test
    void shouldDeclareSessionIdPattern() throws Exception {
        Pattern pattern = InterviewController.class
                .getMethod("state", String.class)
                .getParameters()[0]
                .getAnnotation(Pattern.class);

        assertThat(pattern).isNotNull();
        assertThat(pattern.regexp()).isEqualTo(AssistantChatReqVO.SESSION_ID_REGEXP);
    }

    /**
     * 读取面试报告：三态由 status 表达，前端据此显示「报告生成中」或渲染完整报告。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnInterviewReport() throws Exception {
        InterviewReportRespVO report = new InterviewReportRespVO();
        report.setSessionId("12");
        report.setStatus("SUCCEEDED");
        report.setStatusLabel("已完成");
        report.setSummary("整体答得稳");
        report.setWrongItems(List.of());
        report.setWeaknesses(List.of());
        report.setMastery(List.of());
        report.setHighlights(List.of());
        report.setSuggestions(List.of());
        report.setCanRetry(false);
        when(interviewReportService.getReport(1L, "12")).thenReturn(report);

        mockMvc.perform(get("/api/interviews/12/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("interview_report"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.summary").value("整体答得稳"));
    }

    /**
     * 面试未结束请求报告：返回 1601，报告只在面试走到结束条件后才有。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnReportNotReady() throws Exception {
        when(interviewReportService.getReport(1L, "12"))
                .thenThrow(new BizException(ErrorConstant.INTERVIEW_REPORT_NOT_READY));

        mockMvc.perform(get("/api/interviews/12/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1601))
                .andExpect(jsonPath("$.msg").value("面试尚未结束，报告暂不可用"));
    }

    /**
     * 生成中重复重试：返回 1602，提示稍后再试。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnReportGeneratingOnRetry() throws Exception {
        when(interviewReportService.retry(1L, "12"))
                .thenThrow(new BizException(ErrorConstant.INTERVIEW_REPORT_GENERATING));

        mockMvc.perform(post("/api/interviews/12/report/retry")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1602))
                .andExpect(jsonPath("$.msg").value("报告正在生成中，请稍后再试"));
    }

    /**
     * 跨账号取报告：统一返回 1051，不暴露资源是否存在。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnSessionNotFoundForReport() throws Exception {
        when(interviewReportService.getReport(1L, "99"))
                .thenThrow(new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/interviews/99/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1051));
    }

    /**
     * 会话不存在、已删除或跨账号：返回 1051。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnSessionNotFound() throws Exception {
        when(interviewFlowService.getCurrentUserState("99"))
                .thenThrow(new BizException(ErrorConstant.CHAT_SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/interviews/99")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1051));
    }

    /**
     * 助手会话不是模拟面试：返回 1502，不让前端把普通会话当成面试渲染。
     *
     * @throws Exception 请求执行异常
     */
    @Test
    void shouldReturnSceneMismatch() throws Exception {
        when(interviewFlowService.getCurrentUserState("7"))
                .thenThrow(new BizException(ErrorConstant.INTERVIEW_SCENE_MISMATCH));

        mockMvc.perform(get("/api/interviews/7")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1502));
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
}
