package com.wxy.career.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.sse.SseEmitterSupport;
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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划生成（确认之后才落库）测试。
 *
 * <p>固定四条口径：**生成的计划先给用户确认、确认之前一个字都不落库**；确认通过才由写工具落库；
 * 放弃或过期都不落库、不留脏状态；未填求职目标按 1101 拒绝。同时断言生成链路没有记忆库依赖。
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
     * 生成阶段只下发确认请求与草稿：**确认之前不落库**（首次生成也一样，用户要先看到计划）。
     */
    @Test
    void shouldAskForConfirmationWithoutSaving() {
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(buildProfile());
        when(planConfirmStore.markGenerating(1L)).thenReturn(true);
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        HarnessAgent planner = mock(HarnessAgent.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planner.getName()).thenReturn("planner");
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        when(planner.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(buildConfirmEvent()));
        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.getEmitter()).thenReturn(mock(SseEmitter.class));
        TrainingPlanGenerationServiceImpl spiedService = org.mockito.Mockito.spy(generationService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();

        assertThat(spiedService.generate(buildRequest())).isNotNull();

        // 确认请求带上了草稿正文与「没有旧计划」的摘要。
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(support, org.mockito.Mockito.timeout(3000)).sendResult(payloadCaptor.capture());
        Map<?, ?> payload = (Map<?, ?>) payloadCaptor.getValue();
        assertThat(payload.get("type")).isEqualTo("plan_confirm_required");
        assertThat(String.valueOf(payload.get("draftContent"))).contains("Redis 分布式锁");
        verify(planConfirmStore).savePending(eq(1L), any(PendingPlanConfirmVO.class));
        // 关键：确认之前没有任何落库动作。
        verify(trainingPlanService, never()).consumeSubmittedPlan(anyLong(), anyString());
    }

    /**
     * 收到确认请求时**不能取消框架的事件流**（回归保护）。
     *
     * <p>框架把「挂起的待确认调用」写进 AgentState（Redis）发生在这一轮流自然收尾之后；服务端如果在收到
     * `RequireUserConfirmEvent` 时就把订阅 dispose 掉，状态永远不落库，下一阶段的 confirm 会被框架当成**全新一轮**，
     * 模型重新规划 → 再弹确认 —— 表现就是「点完确认又弹出确认」的死循环。
     */
    @Test
    void shouldKeepFrameworkStreamAliveWhenAskingForConfirmation() throws Exception {
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(buildProfile());
        when(planConfirmStore.markGenerating(1L)).thenReturn(true);
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        HarnessAgent planner = mock(HarnessAgent.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planner.getName()).thenReturn("planner");
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        // 模拟框架时序：先抛确认事件，随后由框架自己收尾（收尾这一步才是落状态的地方）。
        AtomicBoolean frameworkFinished = new AtomicBoolean(false);
        when(planner.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.concat(
                        Flux.just(buildConfirmEvent()),
                        Flux.defer(() -> {
                            frameworkFinished.set(true);
                            return Flux.empty();
                        })));
        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.getEmitter()).thenReturn(mock(SseEmitter.class));
        TrainingPlanGenerationServiceImpl spiedService = org.mockito.Mockito.spy(generationService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();

        assertThat(spiedService.generate(buildRequest())).isNotNull();

        verify(support, org.mockito.Mockito.timeout(3000)).sendDone();
        long deadline = System.currentTimeMillis() + 3000;
        while (!frameworkFinished.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(frameworkFinished).isTrue();
    }

    /**
     * 用户确认之后：回填确认结论继续同一次运行，落库的计划随 result 下发。
     */
    @Test
    void shouldSavePlanAfterApproval() {
        when(planConfirmStore.takePending(1L)).thenReturn(buildPending());
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        HarnessAgent planner = mock(HarnessAgent.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planner.getName()).thenReturn("planner");
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        when(planner.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>empty());
        TrainingPlanRespVO saved = new TrainingPlanRespVO();
        saved.setHasPlan(Boolean.TRUE);
        saved.setPlanId(66L);
        saved.setPlanContent("第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步");
        when(trainingPlanService.consumeSubmittedPlan(1L, "training-plan-1")).thenReturn(saved);
        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.getEmitter()).thenReturn(mock(SseEmitter.class));
        TrainingPlanGenerationServiceImpl spiedService = org.mockito.Mockito.spy(generationService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();

        assertThat(spiedService.confirm(buildConfirm(true))).isNotNull();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(support, org.mockito.Mockito.timeout(3000)).sendResult(payloadCaptor.capture());
        Map<?, ?> payload = (Map<?, ?>) payloadCaptor.getValue();
        assertThat(payload.get("type")).isEqualTo("training_plan");
        verify(support, never()).sendError(anyString());
    }

    /**
     * 回填的确认结论必须是 USER 角色且带 ConfirmResult（框架禁止 SYSTEM 消息进入本次输入）。
     *
     * <p>同时断言重建的工具调用带上了模型原始入参 JSON（{@code content}）：框架执行前拿它做入参 schema 校验，
     * 只带 {@code input} 时会在校验阶段报 {@code argument "content" is null}，工具永远进不来。
     */
    @Test
    void shouldCarryConfirmResultsOnResume() {
        when(planConfirmStore.takePending(1L)).thenReturn(buildPending());
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        HarnessAgent planner = mock(HarnessAgent.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planner.getName()).thenReturn("planner");
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        when(planner.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>empty());
        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.getEmitter()).thenReturn(mock(SseEmitter.class));
        TrainingPlanGenerationServiceImpl spiedService = org.mockito.Mockito.spy(generationService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();

        spiedService.confirm(buildConfirm(true));

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(planner, org.mockito.Mockito.timeout(3000))
                .streamEvents(captor.capture(), any(RuntimeContext.class));
        Msg confirmMessage = captor.getValue().get(0);
        assertThat(confirmMessage.getRole()).isEqualTo(MsgRole.USER);
        Object rawResults = confirmMessage.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(rawResults).isInstanceOf(List.class);
        assertThat(((List<?>) rawResults).get(0)).isInstanceOf(ConfirmResult.class);
        ToolUseBlock resumed = ((ConfirmResult) ((List<?>) rawResults).get(0)).getToolCall();
        assertThat(resumed.getContent()).isNotBlank();
        assertThat(resumed.getInput()).containsKey("planContent");
    }

    /**
     * 快照里没存到模型原始正文时，也要用结构化入参兜底出 {@code content}，否则框架的入参校验同样过不去。
     */
    @Test
    void shouldFallBackToInputJsonWhenSnapshotHasNoRawContent() {
        PendingPlanConfirmVO pending = buildPending();
        pending.getToolCalls().get(0).setContent(null);
        when(planConfirmStore.takePending(1L)).thenReturn(pending);
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        HarnessAgent planner = mock(HarnessAgent.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planner.getName()).thenReturn("planner");
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        when(planner.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>empty());
        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.getEmitter()).thenReturn(mock(SseEmitter.class));
        TrainingPlanGenerationServiceImpl spiedService = org.mockito.Mockito.spy(generationService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();

        spiedService.confirm(buildConfirm(true));

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(planner, org.mockito.Mockito.timeout(3000))
                .streamEvents(captor.capture(), any(RuntimeContext.class));
        Object rawResults = captor.getValue().get(0).getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        ToolUseBlock resumed = ((ConfirmResult) ((List<?>) rawResults).get(0)).getToolCall();
        assertThat(resumed.getContent()).isEqualTo(
                "{\"planContent\":\"第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步\"}");
    }

    /**
     * 用户确认之后再遇到写工具确认请求：直接放行继续跑，**不再弹第二次确认**。
     *
     * <p>这是「点确认后又弹确认」的第二层保护：模型在续跑阶段重提计划时，服务端不该再打扰用户。
     */
    @Test
    void shouldNotAskAgainAfterUserAlreadyApproved() {
        when(planConfirmStore.takePending(1L)).thenReturn(buildPending());
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        HarnessAgent planner = mock(HarnessAgent.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planner.getName()).thenReturn("planner");
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        // 续跑阶段模型又调了一次写工具：应被自动放行，随后这一轮流正常收尾。
        when(planner.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(buildConfirmEvent()), Flux.<AgentEvent>empty());
        TrainingPlanRespVO saved = new TrainingPlanRespVO();
        saved.setHasPlan(Boolean.TRUE);
        saved.setPlanId(66L);
        saved.setPlanContent("第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步");
        when(trainingPlanService.consumeSubmittedPlan(1L, "training-plan-1")).thenReturn(saved);
        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.getEmitter()).thenReturn(mock(SseEmitter.class));
        TrainingPlanGenerationServiceImpl spiedService = org.mockito.Mockito.spy(generationService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();

        assertThat(spiedService.confirm(buildConfirm(true))).isNotNull();

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(support, org.mockito.Mockito.timeout(3000)).sendResult(payloadCaptor.capture());
        Map<?, ?> payload = (Map<?, ?>) payloadCaptor.getValue();
        assertThat(payload.get("type")).isEqualTo("training_plan");
        // 自动放行路径不会再写一次待确认快照，也就不会再下发确认请求。
        verify(planConfirmStore, never()).savePending(anyLong(), any(PendingPlanConfirmVO.class));
    }

    /**
     * 用户放弃保存：不恢复 Agent、不落库，只清掉待确认状态与规划态。
     */
    @Test
    void shouldKeepNothingWhenUserRejects() {
        when(planConfirmStore.takePending(1L)).thenReturn(buildPending());
        HarnessAgent planner = mock(HarnessAgent.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planner.getName()).thenReturn("planner");
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);

        assertThat(generationService.confirm(buildConfirm(false))).isNotNull();

        verify(planConfirmStore).clearPending(1L);
        verify(planConfirmStore).releaseGenerating(1L);
        verify(planner).clearContext("1", "training-plan-1");
        // 没有恢复 Agent：一次调用都没有；也没有落库。
        verify(planner, never()).streamEvents(anyList(), any(RuntimeContext.class));
        verify(trainingPlanService, never()).submitPlan(anyLong(), anyString(), anyString(), any());
    }

    /**
     * 待确认状态过期：按 1704 拒绝并清掉残留规划态，要求重新生成。
     */
    @Test
    void shouldRejectConfirmWhenPendingExpired() {
        when(planConfirmStore.takePending(1L)).thenReturn(null);

        assertThatThrownBy(() -> generationService.confirm(buildConfirm(true)))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1704));
        verify(planConfirmStore).clearPending(1L);
    }

    /**
     * 求职目标未填写：按 1101 拒绝，且不占用生成占位。
     */
    @Test
    void shouldRejectGenerateWhenProfileMissing() {
        when(userProfileService.getRequiredUserProfile(1L))
                .thenThrow(new BizException(com.wxy.career.common.result.ErrorConstant.USER_PROFILE_REQUIRED));

        assertThatThrownBy(() -> generationService.generate(buildRequest()))
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
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(buildProfile());
        TrainingPlanGenerateReqVO reqVO = buildRequest();
        reqVO.setDays(9999);

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
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(buildProfile());
        when(planConfirmStore.markGenerating(1L)).thenReturn(false);

        assertThatThrownBy(() -> generationService.generate(buildRequest()))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1703));
    }

    /**
     * 旧规划态不可用（框架 SYSTEM 注入守卫 / 挂起的待确认调用）时清理状态后自动重试一次。
     */
    @Test
    void shouldRetryOnceWhenPlanStateIsUnusable() {
        when(userProfileService.getRequiredUserProfile(1L)).thenReturn(buildProfile());
        when(planConfirmStore.markGenerating(1L)).thenReturn(true);
        when(trainingPlanService.hasActivePlan(1L)).thenReturn(false);
        HarnessAgent planner = mock(HarnessAgent.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(planner.getName()).thenReturn("planner");
        when(agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)).thenReturn(planner);
        when(planner.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(
                        Flux.error(new IllegalStateException(
                                "Hooks must not inject SYSTEM messages into PreCallEvent.inputMessages.")),
                        Flux.just(buildConfirmEvent()));
        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.getEmitter()).thenReturn(mock(SseEmitter.class));
        TrainingPlanGenerationServiceImpl spiedService = org.mockito.Mockito.spy(generationService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();

        assertThat(spiedService.generate(buildRequest())).isNotNull();

        // 先清理旧规划态，再重试；重试这轮走到确认请求（仍未落库）。
        verify(planner, org.mockito.Mockito.timeout(3000).atLeast(2))
                .clearContext("1", "training-plan-1");
        verify(support, org.mockito.Mockito.timeout(3000)).sendResult(any(Object.class));
        verify(support, never()).sendError(anyString());
    }

    /**
     * 计划生成链路没有记忆库依赖：薄弱点只从 MySQL 读（工具白名单里的 get_weak_points）。
     */
    @Test
    void shouldNotDependOnMemoryRepositoryInGenerationService() {
        List<Class<?>> dependencies = Arrays.stream(TrainingPlanGenerationServiceImpl.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertThat(dependencies).doesNotContain(UserMemoryService.class, UserLongTermMemoryAdapter.class);
    }

    /**
     * 构造生成请求。
     *
     * @return 生成请求
     */
    private TrainingPlanGenerateReqVO buildRequest() {
        TrainingPlanGenerateReqVO reqVO = new TrainingPlanGenerateReqVO();
        reqVO.setDays(7);
        reqVO.setDailyMinutes(60);
        return reqVO;
    }

    /**
     * 构造确认请求。
     *
     * @param approved 是否同意保存
     * @return 确认请求
     */
    private TrainingPlanConfirmReqVO buildConfirm(boolean approved) {
        TrainingPlanConfirmReqVO reqVO = new TrainingPlanConfirmReqVO();
        reqVO.setApproved(approved);
        return reqVO;
    }

    /**
     * 构造求职目标。
     *
     * @return 求职目标
     */
    private UserProfileRespVO buildProfile() {
        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("Java 后端开发");
        profile.setWorkYears(3);
        return profile;
    }

    /**
     * 构造框架抛出的「写工具待确认」事件，入参里带计划正文草稿。
     *
     * <p>按真实链路填上 {@code content}：模型返回的工具调用由 DashScope 解析器带上原始入参 JSON，
     * 框架暂停确认前不会做入参校验，所以这一份必须原样留到确认回填时用。
     *
     * @return 确认事件
     */
    private RequireUserConfirmEvent buildConfirmEvent() {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("call-1")
                .name("submit_training_plan")
                .input(Map.of("planContent", "第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步"))
                .content("{\"planContent\":\"第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步\"}")
                .build();
        return new RequireUserConfirmEvent("reply-1", List.of(toolUse));
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
        toolCall.setContent("{\"planContent\":\"第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步\"}");
        toolCall.setInputJson("{\"planContent\":\"第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步\"}");
        PendingPlanConfirmVO pending = new PendingPlanConfirmVO();
        pending.setReplyId("reply-1");
        pending.setToolCalls(List.of(toolCall));
        return pending;
    }
}
