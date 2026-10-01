package com.wxy.career.service.impl;

import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.config.TrainingProperties;
import com.wxy.career.middleware.PlanConfirmStore;
import com.wxy.career.middleware.UserLongTermMemoryAdapter;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.service.UserMemoryService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.PendingPlanConfirmVO;
import com.wxy.career.vo.PendingPlanToolCallVO;
import com.wxy.career.vo.TrainingPlanConfirmReqVO;
import com.wxy.career.vo.TrainingPlanGenerateReqVO;
import com.wxy.career.vo.UserProfileRespVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划生成（两阶段 HITL）测试。
 *
 * <p>固定三条口径：未确认时不会调用写工具、待确认状态过期按 1704 拒绝、未填求职目标按 1101 拒绝；同时断言生成链路
 * 没有记忆库依赖——薄弱点只从 MySQL 读。
 *
 * @author wxy
 * @date 2026-10-01
 */
class TrainingPlanGenerationServiceImplTest {

    /**
     * 被测服务。
     */
    private TrainingPlanGenerationServiceImpl generationService;

    /**
     * 待确认状态存储桩。
     */
    private PlanConfirmStore planConfirmStore;

    /**
     * 计划服务桩。
     */
    private TrainingPlanService trainingPlanService;

    /**
     * Agent 工厂桩。
     */
    private AgentFactory agentFactory;

    /**
     * 求职目标服务桩。
     */
    private UserProfileService userProfileService;

    /**
     * 组装被测服务并模拟登录态。
     */
    @BeforeEach
    void setUp() {
        planConfirmStore = mock(PlanConfirmStore.class);
        trainingPlanService = mock(TrainingPlanService.class);
        agentFactory = mock(AgentFactory.class);
        userProfileService = mock(UserProfileService.class);

        generationService = new TrainingPlanGenerationServiceImpl();
        ReflectionTestUtils.setField(generationService, "planConfirmStore", planConfirmStore);
        ReflectionTestUtils.setField(generationService, "trainingPlanService", trainingPlanService);
        ReflectionTestUtils.setField(generationService, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(generationService, "userProfileService", userProfileService);
        ReflectionTestUtils.setField(generationService, "agentProperties", new AgentProperties());
        ReflectionTestUtils.setField(generationService, "trainingProperties", new TrainingProperties());
        ReflectionTestUtils.setField(generationService, "objectMapper", new ObjectMapper());
        // SSE 心跳用桩调度器：单测不真的起线程，也不依赖 Spring 生命周期初始化。
        ReflectionTestUtils.setField(generationService, "sseTaskScheduler", mock(ThreadPoolTaskScheduler.class));

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setUsername("alice");
        LoginUserHolder.set(loginUser);
    }

    /**
     * 清理请求级登录上下文，避免影响其它用例。
     */
    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    /**
     * 用户放弃覆盖：清掉待确认状态与规划态，不讲不问地保留当前计划。
     */
    @Test
    void shouldKeepCurrentPlanWhenOverwriteRejected() {
        when(planConfirmStore.takePending(1L)).thenReturn(buildPending());
        HarnessAgent planner = mock(HarnessAgent.class);
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);

        TrainingPlanConfirmReqVO reqVO = new TrainingPlanConfirmReqVO();
        reqVO.setApproved(false);

        assertThat(generationService.confirm(reqVO)).isNotNull();

        verify(planConfirmStore).clearPending(1L);
        verify(planConfirmStore).releaseGenerating(1L);
        verify(planner).clearContext("1", "training-plan-1");
        // 没有调用写工具的入口：未确认就不会覆盖已有计划。
        verify(trainingPlanService, never()).submitPlan(anyLong(), anyString(), any());
    }

    /**
     * 待确认状态过期：按 1704 拒绝，要求重新生成。
     */
    @Test
    void shouldRejectConfirmWhenPendingExpired() {
        when(planConfirmStore.takePending(1L)).thenReturn(null);
        TrainingPlanConfirmReqVO reqVO = new TrainingPlanConfirmReqVO();
        reqVO.setApproved(true);

        assertThatThrownBy(() -> generationService.confirm(reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1704));
        verify(trainingPlanService, never()).submitPlan(anyLong(), anyString(), any());
    }

    /**
     * 求职目标未填写的 1101 由服务层直接透出，且不会占用生成占位。
     */
    @Test
    void shouldRejectGenerateWhenProfileMissing() {
        when(userProfileService.getRequiredUserProfile(1L))
                .thenThrow(new BizException(com.wxy.career.common.result.ErrorConstant.USER_PROFILE_REQUIRED));
        TrainingPlanGenerateReqVO reqVO = new TrainingPlanGenerateReqVO();
        reqVO.setDays(7);
        reqVO.setDailyMinutes(60);

        assertThatThrownBy(() -> generationService.generate(reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1101));
        verify(planConfirmStore, never()).markGenerating(anyLong());
    }

    /**
     * 天数与每日时长超出配置边界时按参数错误拒绝。
     */
    @Test
    void shouldRejectGenerateWhenBoundsExceeded() {
        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("Java 后端开发");
        profile.setWorkYears(3);
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(profile);
        TrainingPlanGenerateReqVO reqVO = new TrainingPlanGenerateReqVO();
        reqVO.setDays(9999);
        reqVO.setDailyMinutes(600);

        assertThatThrownBy(() -> generationService.generate(reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(400));
        verify(planConfirmStore, never()).markGenerating(anyLong());
    }

    /**
     * 同一用户已有生成在跑时按 1703 拒绝。
     */
    @Test
    void shouldRejectConcurrentGeneration() {
        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("Java 开发");
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(profile);
        when(planConfirmStore.markGenerating(1L)).thenReturn(false);
        TrainingPlanGenerateReqVO reqVO = new TrainingPlanGenerateReqVO();
        reqVO.setDays(7);
        reqVO.setDailyMinutes(60);

        assertThatThrownBy(() -> generationService.generate(reqVO))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1703));
    }

    /**
     * 计划生成链路没有记忆库依赖：薄弱点只从 MySQL 读（工具白名单里的 get_weak_points）。
     */
    @Test
    void shouldNotDependOnMemoryRepository() {
        List<Class<?>> dependencies = Arrays.stream(TrainingPlanGenerationServiceImpl.class.getDeclaredFields())
                .map(Field::getType)
                .toList();
        List<Class<?>> planServiceDependencies = Arrays.stream(TrainingPlanServiceImpl.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertThat(dependencies).doesNotContain(UserMemoryService.class, UserLongTermMemoryAdapter.class);
        assertThat(planServiceDependencies).doesNotContain(UserMemoryService.class);
    }

    /**
     * 构造待确认快照。
     *
     * @return 待确认快照
     */
    private PendingPlanConfirmVO buildPending() {
        PendingPlanToolCallVO toolCall = new PendingPlanToolCallVO();
        toolCall.setId("call-1");
        toolCall.setName("submit_training_plan");
        toolCall.setInputJson("{}");
        PendingPlanConfirmVO pending = new PendingPlanConfirmVO();
        pending.setReplyId("reply-1");
        pending.setToolCalls(List.of(toolCall));
        return pending;
    }
}
