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
import com.wxy.career.vo.TrainingPlanRespVO;
import com.wxy.career.vo.UserProfileRespVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.wxy.career.common.sse.SseEmitterSupport;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
     * 每次生成都从干净的规划态开始：上一次停在「等待确认」时留下的状态必须先清掉。
     *
     * <p>复现过的故障：上一次生成停在覆盖确认提示，用户关掉页面或刷新后，待确认状态仍在
     * {@code (planner, training-plan-{userId})} 槽位上；此时再点一次「生成」，框架会在调用开始就抛
     * 「Agent is paused for human-in-the-loop confirmation ... This call supplied no confirmation」，
     * 用户看到的是「计划生成失败」。
     */
    @Test
    void shouldResetPreviousStateBeforeGenerating() {
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(buildProfile());
        HarnessAgent planner = mock(HarnessAgent.class);
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        when(planConfirmStore.markGenerating(1L)).thenReturn(true);
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        when(planner.streamEvents(any(Msg.class), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.empty());
        TrainingPlanGenerateReqVO reqVO = new TrainingPlanGenerateReqVO();
        reqVO.setDays(7);
        reqVO.setDailyMinutes(60);

        assertThat(generationService.generate(reqVO)).isNotNull();

        // 生成前先清掉上一次的待确认快照与规划态，避免框架因为「挂着的待确认调用」直接拒绝本次生成。
        verify(planConfirmStore).clearPending(1L);
        verify(planner).clearContext("1", "training-plan-1");
    }

    /**
     * 首次生成自动确认时，回填给框架的消息必须带上确认结论与对应的工具调用。
     *
     * <p>这是 HITL 停机—恢复链路的契约：少带 metadata 或工具调用 id 对不上，框架都会拒绝恢复
     * （就是线上报的「This call supplied no confirmation」那一类错误）。
     */
    @Test
    void shouldCarryConfirmResultsWhenAutoConfirmingFirstPlan() {
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(buildProfile());
        HarnessAgent planner = mock(HarnessAgent.class);
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        when(planConfirmStore.markGenerating(1L)).thenReturn(true);
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        when(planner.streamEvents(any(Msg.class), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.just(buildConfirmEvent()));
        when(planner.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.<AgentEvent>empty());
        TrainingPlanGenerateReqVO reqVO = new TrainingPlanGenerateReqVO();
        reqVO.setDays(7);
        reqVO.setDailyMinutes(60);

        assertThat(generationService.generate(reqVO)).isNotNull();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(planner, org.mockito.Mockito.timeout(3000))
                .streamEvents(captor.capture(), any(io.agentscope.core.agent.RuntimeContext.class));
        Object rawResults = captor.getValue().get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(rawResults).isInstanceOf(List.class);
        List<?> confirmResults = (List<?>) rawResults;
        assertThat(confirmResults).hasSize(1);
        assertThat(confirmResults.get(0)).isInstanceOf(ConfirmResult.class);
        ConfirmResult confirmResult = (ConfirmResult) confirmResults.get(0);
        assertThat(confirmResult.isConfirmed()).isTrue();
        assertThat(confirmResult.getToolCall().getId()).isEqualTo("call-1");
        assertThat(confirmResult.getToolCall().getName()).isEqualTo("submit_training_plan");
    }

    /**
     * 首次生成自动确认后，**上一轮暂停时的流结束不能把重订阅的那次运行掐掉**。
     *
     * <p>线上踩过：写工具触发确认时框架会结束当前这轮流，服务端随即用同一状态重订阅继续跑；
     * 上一轮流的 onComplete 会走 finish(...) 把刚发起的重订阅 dispose 掉，写工具根本没执行，
     * 用户看到「本次没有生成出可用的计划」，而且按钮一直转圈。这里用「第一次消费产物返回 null、
     * 第二次返回计划」把这条时序固定下来：只要最终下发了 training_plan 结果、没有下发 error，就说明
     * 上一轮流的结束被正确忽略。
     */
    @Test
    void shouldIgnorePausedRunCompletionWhenAutoConfirming() {
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(buildProfile());
        when(planConfirmStore.markGenerating(1L)).thenReturn(true);
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        HarnessAgent planner = mock(HarnessAgent.class);
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        // 第一轮流：发出确认请求后正常结束（这就是暂停）。
        when(planner.streamEvents(any(Msg.class), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.just(buildConfirmEvent()));
        // 重订阅那轮流：仍在进行中（真实场景里它会继续跑工具与模型），用来验证它没有被上一轮流掐掉。
        when(planner.streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class)))
                .thenReturn(Flux.<AgentEvent>never());

        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.getEmitter()).thenReturn(mock(SseEmitter.class));
        TrainingPlanGenerationServiceImpl spiedService =
                org.mockito.Mockito.spy(generationService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();
        TrainingPlanGenerateReqVO reqVO = new TrainingPlanGenerateReqVO();
        reqVO.setDays(7);
        reqVO.setDailyMinutes(60);

        assertThat(spiedService.generate(reqVO)).isNotNull();

        // 自动确认的重订阅必须已经发出（否则说明确认链路没走通）。
        org.mockito.Mockito.verify(planner, org.mockito.Mockito.timeout(3000))
                .streamEvents(anyList(), any(io.agentscope.core.agent.RuntimeContext.class));
        // 关键断言：上一轮暂停的流结束之后，本轮（重订阅后）仍在进行中，绝不能提前下发失败。
        org.mockito.Mockito.verify(support, org.mockito.Mockito.after(800).never())
                .sendError(anyString());
        org.mockito.Mockito.verify(trainingPlanService, org.mockito.Mockito.never())
                .consumeSubmittedPlan(1L, "training-plan-1");
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
        // 过期时顺带清掉 Agent 里可能残留的待确认状态，否则用户重新生成会被框架拒绝。
        verify(planConfirmStore).clearPending(1L);
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

    /**
     * 构造框架抛出的「写工具待确认」事件。
     *
     * @return 确认事件
     */
    private RequireUserConfirmEvent buildConfirmEvent() {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("call-1")
                .name("submit_training_plan")
                .input(Map.of("plan", Map.of("days", 3)))
                .build();
        return new RequireUserConfirmEvent("reply-1", List.of(toolUse));
    }

    /**
     * 构造求职目标（生成任务文本需要目标岗位）。
     *
     * @return 求职目标
     */
    private UserProfileRespVO buildProfile() {
        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("Java 后端开发");
        profile.setWorkYears(3);
        return profile;
    }
}
