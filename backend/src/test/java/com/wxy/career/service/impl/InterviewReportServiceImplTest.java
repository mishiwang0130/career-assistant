package com.wxy.career.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.enums.ChatSceneEnum;
import com.wxy.career.common.enums.InterviewReportStatusEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.mapper.ChatSessionMapper;
import com.wxy.career.mapper.InterviewQaMapper;
import com.wxy.career.mapper.InterviewReportMapper;
import com.wxy.career.middleware.ReportTaskDispatcher;
import com.wxy.career.po.ChatSession;
import com.wxy.career.po.InterviewQa;
import com.wxy.career.po.InterviewReport;
import com.wxy.career.po.KnowledgeMastery;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.KnowledgeMasteryService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.InterviewReportRespVO;
import com.wxy.career.vo.InterviewReportSubmitVO;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.UserProfileRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试报告服务单测。
 *
 * <p>覆盖报告的状态机与失败口径：派发、回写、派发失败、生成中拒绝重试、生成超时兜底、
 * 面试未结束 1601、非面试会话 1502、跨账号 1051。报告子 Agent 与 Mem0 都不参与测试。
 *
 * @author wxy
 * @date 2026-09-29
 */
class InterviewReportServiceImplTest {

    /**
     * 面试会话 ID。
     */
    private static final String SESSION_ID = "12";

    /**
     * 用户 ID。
     */
    private static final long USER_ID = 1L;

    /**
     * 报告 Mapper 桩。
     */
    private InterviewReportMapper interviewReportMapper;

    /**
     * 会话 Mapper 桩。
     */
    private ChatSessionMapper chatSessionMapper;

    /**
     * 问答 Mapper 桩。
     */
    private InterviewQaMapper interviewQaMapper;

    /**
     * 后台派发器桩。
     */
    private ReportTaskDispatcher reportTaskDispatcher;

    /**
     * 面试流程服务桩。
     */
    private InterviewFlowService interviewFlowService;

    /**
     * 掌握度服务桩。
     */
    private KnowledgeMasteryService knowledgeMasteryService;

    /**
     * 被测服务。
     */
    private InterviewReportServiceImpl interviewReportService;

    /**
     * 初始化被测服务与全部桩依赖。
     */
    @BeforeEach
    void setUp() {
        interviewReportMapper = mock(InterviewReportMapper.class);
        chatSessionMapper = mock(ChatSessionMapper.class);
        interviewQaMapper = mock(InterviewQaMapper.class);
        reportTaskDispatcher = mock(ReportTaskDispatcher.class);
        interviewFlowService = mock(InterviewFlowService.class);
        knowledgeMasteryService = mock(KnowledgeMasteryService.class);
        UserProfileService userProfileService = mock(UserProfileService.class);

        ChatSession session = new ChatSession();
        session.setId(Long.valueOf(SESSION_ID));
        session.setUserId(USER_ID);
        session.setScene(ChatSceneEnum.INTERVIEW.getValue());
        when(chatSessionMapper.selectByIdAndUserId(anyLong(), anyLong())).thenReturn(session);

        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("后端开发");
        profile.setWorkYears(3);
        when(userProfileService.getUserProfileByUserId(USER_ID)).thenReturn(profile);

        when(knowledgeMasteryService.knowledgePointsOf(any())).thenReturn(List.of("Redis 分布式锁"));
        when(knowledgeMasteryService.listByUserAndPoints(anyLong(), any())).thenReturn(List.of());
        when(interviewQaMapper.selectBySession(anyLong(), anyLong())).thenReturn(List.of(wrongRow()));

        interviewReportService = new InterviewReportServiceImpl();
        ReflectionTestUtils.setField(interviewReportService, "interviewReportMapper", interviewReportMapper);
        ReflectionTestUtils.setField(interviewReportService, "chatSessionMapper", chatSessionMapper);
        ReflectionTestUtils.setField(interviewReportService, "interviewQaMapper", interviewQaMapper);
        ReflectionTestUtils.setField(interviewReportService, "interviewFlowService", interviewFlowService);
        ReflectionTestUtils.setField(interviewReportService, "knowledgeMasteryService", knowledgeMasteryService);
        ReflectionTestUtils.setField(interviewReportService, "userProfileService", userProfileService);
        ReflectionTestUtils.setField(interviewReportService, "reportTaskDispatcher", reportTaskDispatcher);
        ReflectionTestUtils.setField(interviewReportService, "objectMapper", new ObjectMapper());
    }

    /**
     * 面试结束后启动生成：置为生成中、递增尝试次数、派发后台任务，并返回生成中状态。
     */
    @Test
    void shouldStartGenerationWhenInterviewFinished() {
        finished(true);
        when(interviewReportMapper.selectByUserAndSession(USER_ID, Long.valueOf(SESSION_ID)))
                .thenReturn(generatingReport(LocalDateTime.now()));
        when(reportTaskDispatcher.dispatch(eq(USER_ID), eq(SESSION_ID), anyString()))
                .thenReturn(Mono.just("status: accepted"));

        InterviewReportRespVO response = interviewReportService.startGeneration(USER_ID, SESSION_ID);

        verify(interviewReportMapper).upsertGenerating(USER_ID, Long.valueOf(SESSION_ID));
        ArgumentCaptor<String> taskTextCaptor = ArgumentCaptor.forClass(String.class);
        verify(reportTaskDispatcher).dispatch(eq(USER_ID), eq(SESSION_ID), taskTextCaptor.capture());
        // 派发材料要带会话 ID（子 Agent 原样填回）与本次面试的判定材料
        assertThat(taskTextCaptor.getValue()).contains(SESSION_ID).contains("逐题判定");
        assertThat(response.getStatus()).isEqualTo(InterviewReportStatusEnum.GENERATING.getValue());
        assertThat(response.getCanRetry()).isFalse();
    }

    /**
     * 子 Agent 回写：状态置为已完成并保存总结与结构化结论。
     */
    @Test
    void shouldMarkSucceededWhenReportSubmitted() {
        when(interviewReportMapper.selectByUserAndSession(USER_ID, Long.valueOf(SESSION_ID)))
                .thenReturn(generatingReport(LocalDateTime.now()));
        InterviewReportSubmitVO submitVO = new InterviewReportSubmitVO();
        submitVO.setSessionId(SESSION_ID);
        submitVO.setSummary("整体答得稳，基础题深度不够。");
        submitVO.setHighlights(List.of("项目题讲得清楚"));
        submitVO.setSuggestions(List.of("把线程池参数捋一遍"));

        interviewReportService.submitReport(USER_ID, submitVO);

        verify(interviewReportMapper).markSucceeded(
                eq(USER_ID), eq(Long.valueOf(SESSION_ID)), eq("整体答得稳，基础题深度不够。"), anyString());
    }

    /**
     * 后台任务派发/执行失败时把报告置为失败，并给出重试入口。
     */
    @Test
    void shouldMarkFailedWhenDispatchFails() {
        finished(true);
        // 派发失败后报告已被置为失败：读取到的就是失败态（模拟写后读的一致状态）。
        when(interviewReportMapper.selectByUserAndSession(USER_ID, Long.valueOf(SESSION_ID)))
                .thenReturn(failedReport());
        when(reportTaskDispatcher.dispatch(eq(USER_ID), eq(SESSION_ID), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("派发失败")));

        InterviewReportRespVO response = interviewReportService.startGeneration(USER_ID, SESSION_ID);

        verify(interviewReportMapper).markFailed(eq(USER_ID), eq(Long.valueOf(SESSION_ID)), anyString());
        assertThat(response.getStatus()).isEqualTo(InterviewReportStatusEnum.FAILED.getValue());
        assertThat(response.getCanRetry()).isTrue();
    }

    /**
     * 生成中的报告：重试被拒绝并返回 1602，不会重复派发。
     */
    @Test
    void shouldRejectRetryWhileGenerating() {
        finished(true);
        when(interviewReportMapper.selectByUserAndSession(USER_ID, Long.valueOf(SESSION_ID)))
                .thenReturn(generatingReport(LocalDateTime.now()));

        assertThatThrownBy(() -> interviewReportService.retry(USER_ID, SESSION_ID))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode().getCode())
                .isEqualTo(ErrorConstant.INTERVIEW_REPORT_GENERATING.getCode());
        verify(reportTaskDispatcher, never()).dispatch(any(), any(), anyString());
    }

    /**
     * 生成中超过 5 分钟：按失败处理并写超时原因，用户不会卡在「报告生成中」。
     */
    @Test
    void shouldExpireStaleGeneratingReport() {
        finished(true);
        when(interviewReportMapper.selectByUserAndSession(USER_ID, Long.valueOf(SESSION_ID)))
                .thenReturn(generatingReport(LocalDateTime.now().minusMinutes(6)), failedReport());

        InterviewReportRespVO response = interviewReportService.getReport(USER_ID, SESSION_ID);

        verify(interviewReportMapper).markFailed(eq(USER_ID), eq(Long.valueOf(SESSION_ID)), anyString());
        assertThat(response.getStatus()).isEqualTo(InterviewReportStatusEnum.FAILED.getValue());
    }

    /**
     * 已完成的报告：重试幂等返回现有报告，不重复派发。
     */
    @Test
    void shouldReturnExistingReportOnRetryWhenSucceeded() {
        finished(true);
        when(interviewReportMapper.selectByUserAndSession(USER_ID, Long.valueOf(SESSION_ID)))
                .thenReturn(succeededReport());

        InterviewReportRespVO response = interviewReportService.retry(USER_ID, SESSION_ID);

        assertThat(response.getStatus()).isEqualTo(InterviewReportStatusEnum.SUCCEEDED.getValue());
        assertThat(response.getSummary()).isEqualTo("报告正文");
        verify(reportTaskDispatcher, never()).dispatch(any(), any(), anyString());
    }

    /**
     * 面试未结束：查询与重试都返回 1601。
     */
    @Test
    void shouldRejectWhenInterviewNotFinished() {
        finished(false);

        assertThatThrownBy(() -> interviewReportService.getReport(USER_ID, SESSION_ID))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode().getCode())
                .isEqualTo(ErrorConstant.INTERVIEW_REPORT_NOT_READY.getCode());
        assertThatThrownBy(() -> interviewReportService.retry(USER_ID, SESSION_ID))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode().getCode())
                .isEqualTo(ErrorConstant.INTERVIEW_REPORT_NOT_READY.getCode());
    }

    /**
     * 非面试会话：返回 1502。
     */
    @Test
    void shouldRejectNonInterviewSession() {
        finished(true);
        ChatSession assistantSession = new ChatSession();
        assistantSession.setId(Long.valueOf(SESSION_ID));
        assistantSession.setUserId(USER_ID);
        assistantSession.setScene(ChatSceneEnum.ASSISTANT.getValue());
        when(chatSessionMapper.selectByIdAndUserId(anyLong(), anyLong())).thenReturn(assistantSession);

        assertThatThrownBy(() -> interviewReportService.getReport(USER_ID, SESSION_ID))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode().getCode())
                .isEqualTo(ErrorConstant.INTERVIEW_SCENE_MISMATCH.getCode());
    }

    /**
     * 跨账号（会话查不到）：统一返回 1051，不暴露资源是否存在。
     */
    @Test
    void shouldRejectCrossAccountSession() {
        when(chatSessionMapper.selectByIdAndUserId(anyLong(), anyLong())).thenReturn(null);

        assertThatThrownBy(() -> interviewReportService.getReport(USER_ID, SESSION_ID))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode().getCode())
                .isEqualTo(ErrorConstant.CHAT_SESSION_NOT_FOUND.getCode());
        assertThatThrownBy(() -> interviewReportService.submitReport(USER_ID, submitOf("12")))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode().getCode())
                .isEqualTo(ErrorConstant.CHAT_SESSION_NOT_FOUND.getCode());
    }

    /**
     * 报告已完成时不覆盖：重复提交幂等忽略。
     */
    @Test
    void shouldIgnoreDuplicateSubmitWhenSucceeded() {
        when(interviewReportMapper.selectByUserAndSession(USER_ID, Long.valueOf(SESSION_ID)))
                .thenReturn(succeededReport());

        interviewReportService.submitReport(USER_ID, submitOf(SESSION_ID));

        verify(interviewReportMapper, never()).markSucceeded(anyLong(), anyLong(), any(), any());
    }

    /**
     * 报告里带本场知识点的掌握度与薄弱点清单（内容由 Java 确定性派生，不由模型编）。
     */
    @Test
    void shouldDeriveWrongItemsAndMasteryFromInterviewData() {
        finished(true);
        KnowledgeMastery mastery = new KnowledgeMastery();
        mastery.setKnowledgePoint("Redis 分布式锁");
        mastery.setMasteryScore(40);
        mastery.setMasteryLevel("NEEDS_WORK");
        mastery.setWeak(1);
        mastery.setEvidenceCount(1);
        mastery.setLastOutcome("WRONG");
        when(knowledgeMasteryService.listByUserAndPoints(anyLong(), any())).thenReturn(List.of(mastery));
        when(interviewReportMapper.selectByUserAndSession(USER_ID, Long.valueOf(SESSION_ID)))
                .thenReturn(succeededReport());

        InterviewReportRespVO response = interviewReportService.getReport(USER_ID, SESSION_ID);

        assertThat(response.getWrongItems()).hasSize(1);
        assertThat(response.getWrongItems().get(0).getKnowledgePoints()).containsExactly("Redis 分布式锁");
        assertThat(response.getMastery()).hasSize(1);
        assertThat(response.getMastery().get(0).getMasteryLevelLabel()).isEqualTo("待补强");
        assertThat(response.getWeaknesses()).hasSize(1);
        assertThat(response.getWeaknesses().get(0).getKnowledgePoint()).isEqualTo("Redis 分布式锁");
    }

    /**
     * 桩：面试是否已结束。
     *
     * @param finished 是否结束
     */
    private void finished(boolean finished) {
        InterviewStateRespVO state = new InterviewStateRespVO();
        state.setSessionId(SESSION_ID);
        state.setFinished(finished);
        when(interviewFlowService.getState(USER_ID, SESSION_ID)).thenReturn(state);
    }

    /**
     * 构造一条错题记录（带评分结论）。
     *
     * @return 问答记录
     */
    private InterviewQa wrongRow() {
        InterviewQa row = new InterviewQa();
        row.setId(9L);
        row.setUserId(USER_ID);
        row.setSessionId(Long.valueOf(SESSION_ID));
        row.setQuestionIndex(4);
        row.setRoundNo(1);
        row.setQuestionType("BASIC");
        row.setDifficulty(3);
        row.setQuestion("Redis 分布式锁怎么实现？");
        row.setAnswer("用 SETNX 就行");
        row.setOutcome("WRONG");
        row.setJudgement("关键结论说错");
        row.setNextAction("NEXT_QUESTION");
        row.setEvaluationJson("{\"outcome\":\"WRONG\",\"score\":20,\"comment\":\"关键结论说错\","
                + "\"knowledgePoints\":[\"Redis 分布式锁\"],\"missingPoints\":[\"锁续期\"],"
                + "\"referenceAnswer\":\"SET NX PX + 唯一值 + Lua 释放\"}");
        row.setCreateTime(LocalDateTime.now());
        return row;
    }

    /**
     * 构造生成中的报告记录。
     *
     * @param createTime 创建时间
     * @return 报告记录
     */
    private InterviewReport generatingReport(LocalDateTime createTime) {
        InterviewReport report = new InterviewReport();
        report.setUserId(USER_ID);
        report.setSessionId(Long.valueOf(SESSION_ID));
        report.setStatus(InterviewReportStatusEnum.GENERATING.getValue());
        report.setAttempt(1);
        report.setCreateTime(createTime);
        return report;
    }

    /**
     * 构造失败的报告记录。
     *
     * @return 报告记录
     */
    private InterviewReport failedReport() {
        InterviewReport report = new InterviewReport();
        report.setUserId(USER_ID);
        report.setSessionId(Long.valueOf(SESSION_ID));
        report.setStatus(InterviewReportStatusEnum.FAILED.getValue());
        report.setErrorMessage("报告生成超时，请重试");
        report.setCreateTime(LocalDateTime.now());
        return report;
    }

    /**
     * 构造已完成的报告记录。
     *
     * @return 报告记录
     */
    private InterviewReport succeededReport() {
        InterviewReport report = new InterviewReport();
        report.setUserId(USER_ID);
        report.setSessionId(Long.valueOf(SESSION_ID));
        report.setStatus(InterviewReportStatusEnum.SUCCEEDED.getValue());
        report.setSummary("报告正文");
        report.setReportJson("{\"highlights\":[\"项目题讲得清楚\"],\"suggestions\":[\"补基础\"]}");
        report.setFinishTime(LocalDateTime.now());
        report.setCreateTime(LocalDateTime.now());
        return report;
    }

    /**
     * 构造报告提交对象。
     *
     * @param sessionId 会话 ID
     * @return 报告提交对象
     */
    private InterviewReportSubmitVO submitOf(String sessionId) {
        InterviewReportSubmitVO submitVO = new InterviewReportSubmitVO();
        submitVO.setSessionId(sessionId);
        submitVO.setSummary("报告正文");
        return submitVO;
    }
}
