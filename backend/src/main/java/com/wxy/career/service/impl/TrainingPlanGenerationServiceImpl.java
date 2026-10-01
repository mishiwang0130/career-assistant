package com.wxy.career.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.sse.SseEmitterSupport;
import com.wxy.career.common.sse.SseEvent;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.config.TrainingProperties;
import com.wxy.career.middleware.PlanConfirmStore;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.TrainingPlanGenerationService;
import com.wxy.career.service.TrainingPlanService;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.util.AgentEventMapper;
import com.wxy.career.util.TrainingPlanAllocator;
import com.wxy.career.vo.PendingPlanConfirmVO;
import com.wxy.career.vo.PendingPlanToolCallVO;
import com.wxy.career.vo.TrainingDayRespVO;
import com.wxy.career.vo.TrainingPlanConfirmReqVO;
import com.wxy.career.vo.TrainingPlanGenerateReqVO;
import com.wxy.career.vo.TrainingPlanRespVO;
import com.wxy.career.vo.TrainingTaskRespVO;
import com.wxy.career.vo.UserProfileRespVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 训练计划生成服务实现（两阶段 HITL）。
 *
 * <p>阶段一跑计划 Agent：模型先只读规划（Plan Mode），最后调用写工具 {@code submit_training_plan}。写工具受框架
 * 权限管控，会触发确认事件：没有生效计划时服务端自动确认（不存在覆盖风险），已有生效计划时把确认请求下发给前端
 * 并结束本次流；阶段二把确认结论回填给同一次运行，写入成功后下发结构化计划。**未确认时不会调用写工具，因此
 * 已有计划不会被覆盖。**
 *
 * <p>计划正文只落 MySQL：模型通过提交工具写 {@code training_plan} / {@code training_task}，本类不往工作区写文件。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Slf4j
@Service
public class TrainingPlanGenerationServiceImpl implements TrainingPlanGenerationService {

    /**
     * 场景标识，写入 meta 事件；训练计划不是会话场景，这里只做链路标识。
     */
    private static final String SCENE_TRAINING_PLAN = "training-plan";

    /**
     * 计划 Agent 的运行标识前缀：非会话 Agent 也需要一个稳定的状态槽位，形如 {@code training-plan:{userId}}。
     */
    private static final String SESSION_PREFIX = "training-plan-";

    /**
     * 用户消息发送者名。
     */
    private static final String USER_MESSAGE_NAME = "user";

    /**
     * 流内异常对外暴露的统一提示。
     */
    private static final String STREAM_ERROR_MESSAGE = "计划生成暂时不可用，请稍后重试";

    /**
     * 模型没有提交计划时的提示。
     */
    private static final String NO_PLAN_MESSAGE = "本次没有生成出可用的计划，请重试";

    /**
     * 自动确认的最大次数：正常路径只会自动确认一次，超过说明模型在反复请求写权限，直接结束避免死循环。
     */
    private static final int MAX_AUTO_CONFIRM = 2;

    /**
     * 日期格式。
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Agent 工厂。
     */
    @Resource
    private AgentFactory agentFactory;

    /**
     * 计划服务：读取当前计划、取走本次生成落库的计划。
     */
    @Resource
    private TrainingPlanService trainingPlanService;

    /**
     * 求职目标服务：生成前必须已填写目标岗位。
     */
    @Resource
    private UserProfileService userProfileService;

    /**
     * 待确认状态存储。
     */
    @Resource
    private PlanConfirmStore planConfirmStore;

    /**
     * Agent 配置，提供模型提供方与流超时。
     */
    @Resource
    private AgentProperties agentProperties;

    /**
     * 训练计划配置，提供天数与时长边界。
     */
    @Resource
    private TrainingProperties trainingProperties;

    /**
     * JSON 组件，用于待确认工具调用入参的序列化与反序列化。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * SSE 心跳调度器。
     */
    @Resource
    private ThreadPoolTaskScheduler sseTaskScheduler;

    /**
     * 生成（或重新规划）训练计划。
     *
     * @param reqVO 生成参数
     * @return SSE 响应对象
     */
    @Override
    public SseEmitter generate(TrainingPlanGenerateReqVO reqVO) {
        Long userId = currentUserId();
        // 求职目标必填：未填写直接按 1101 拒绝，前端据此跳 /profile。
        UserProfileRespVO profile = userProfileService.getRequiredUserProfile(userId);
        int days = reqVO.getDays();
        int dailyMinutes = reqVO.getDailyMinutes();
        validateBounds(days, dailyMinutes);
        String sessionId = sessionId(userId);
        // 每次生成都从干净的规划态开始：上一次生成可能停在「等待覆盖确认」，那份待确认状态留在 Agent 会话里，
        // 会让本次调用直接被框架拒绝（Agent is paused for human-in-the-loop confirmation: this call supplied
        // no confirmation）。这里先清掉待确认快照与规划态，保证「再点一次生成」永远能正常工作。
        resetPreviousGeneration(userId, sessionId);
        // 同一用户同一时刻只允许一次生成在跑，避免两次生成互相覆盖。
        if (!planConfirmStore.markGenerating(userId)) {
            throw new BizException(ErrorConstant.TRAINING_PLAN_GENERATING);
        }
        try {
            HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);
            // 天数与每日时长以用户本次请求为准：模型在提交里填错也不会改变用户输入的周期。
            trainingPlanService.recordGenerationInput(userId, sessionId, days, dailyMinutes);
            boolean hasActivePlan = trainingPlanService.hasActivePlan(userId);
            SseEmitterSupport support = createEmitterSupport();
            support.sendMeta(SCENE_TRAINING_PLAN, sessionId, agentProperties.getProvider(),
                    UUID.randomUUID().toString());
            StreamState state = new StreamState(
                    support, userId, sessionId, days, dailyMinutes, hasActivePlan, agent);
            state.requestMessages = List.of(Msg.builder()
                    .name(USER_MESSAGE_NAME)
                    .role(MsgRole.USER)
                    .textContent(buildTaskText(profile, days, dailyMinutes, hasActivePlan, userId))
                    .build());
            subscribe(state, agent.streamEvents(state.requestMessages, runtimeContext(state)));
            return support.getEmitter();
        } catch (RuntimeException exception) {
            // 进流前失败必须释放占位，否则该用户要等标记过期才能再次生成。
            planConfirmStore.releaseGenerating(userId);
            throw exception;
        }
    }

    /**
     * 回填确认结论并继续生成。
     *
     * @param reqVO 确认结果
     * @return SSE 响应对象
     */
    @Override
    public SseEmitter confirm(TrainingPlanConfirmReqVO reqVO) {
        Long userId = currentUserId();
        PendingPlanConfirmVO pending = planConfirmStore.takePending(userId);
        boolean approved = Boolean.TRUE.equals(reqVO.getApproved());
        String sessionId = sessionId(userId);
        if (pending == null || pending.getToolCalls() == null || pending.getToolCalls().isEmpty()) {
            // 待确认状态已过期（或已被处理）：先清掉 Agent 里可能残留的待确认状态，再让用户重新生成，
            // 否则下一次生成会被框架以「有待确认的工具调用」拒绝；同时避免拿旧计划做覆盖。
            resetPreviousGeneration(userId, sessionId);
            throw new BizException(ErrorConstant.TRAINING_PLAN_CONFIRM_EXPIRED);
        }
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);
        SseEmitterSupport support = createEmitterSupport();
        support.sendMeta(SCENE_TRAINING_PLAN, sessionId, agentProperties.getProvider(),
                UUID.randomUUID().toString());
        if (!approved) {
            // 用户放弃覆盖：不恢复 Agent，不调用写工具，清掉规划态，当前计划一行不改。
            planConfirmStore.clearPending(userId);
            planConfirmStore.releaseGenerating(userId);
            safeClearPlanState(userId, sessionId);
            support.sendResult(resultPayload("plan_confirm_rejected", "已保留当前计划，未做任何修改"));
            support.sendDone();
            return support.getEmitter();
        }
        boolean hasActivePlan = trainingPlanService.hasActivePlan(userId);
        StreamState state = new StreamState(
                support, userId, sessionId, null, null, hasActivePlan, agent);
        state.requestMessages = List.of(confirmMessage(pending));
        subscribe(state, agent.streamEvents(state.requestMessages, runtimeContext(state)));
        return support.getEmitter();
    }

    /**
     * 订阅一次 Agent 事件流。
     *
     * <p>HITL 重订阅时必须忽略「上一次订阅的后续事件」：写入工具触发确认时，框架会先结束当前这轮流
     * （抛 RequestStopEvent 并 complete），而服务端紧接着用同一份状态重订阅去继续这次运行。如果不做隔离，
     * 上一轮流结束时的 onComplete 会走 {@code finish(...)}：它会把刚发起的那次重订阅 dispose 掉
     * （框架侧表现为 status=CANCEL），写工具根本没执行，用户看到的就是「本次没有生成出可用的计划」。
     * 这里用自增的 epoch 给每次订阅编号，只有编号最新的事件回调才允许处理。
     *
     * @param state 流式状态
     * @param events 事件流
     */
    private void subscribe(StreamState state, reactor.core.publisher.Flux<AgentEvent> events) {
        int epoch = state.epoch.incrementAndGet();
        Disposable subscription = events
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> {
                            if (state.epoch.get() == epoch) {
                                onNext(state, event);
                            }
                        },
                        error -> {
                            if (state.epoch.get() == epoch) {
                                onError(state, error);
                            }
                        },
                        () -> {
                            if (state.epoch.get() == epoch) {
                                onComplete(state);
                            }
                        });
        state.subscription.set(subscription);
    }

    /**
     * 处理单条 Agent 事件。
     *
     * @param state 流式状态
     * @param event AgentScope 事件
     */
    private void onNext(StreamState state, AgentEvent event) {
        if (state.terminated.get()) {
            return;
        }
        // 写工具触发的权限确认：这就是 HITL 的入口。
        if (event instanceof RequireUserConfirmEvent confirmEvent) {
            onRequireConfirm(state, confirmEvent);
            return;
        }
        if (AgentEventMapper.isSubagentEvent(event)) {
            log.debug("跳过子 Agent 转发事件，eventType={}", event.getType());
            return;
        }
        SseEvent mapped = AgentEventMapper.map(event);
        if (mapped == null) {
            return;
        }
        if (AgentEventMapper.isError(mapped)) {
            finish(state, STREAM_ERROR_MESSAGE);
            return;
        }
        if (state.detached) {
            return;
        }
        if (!state.support.send(mapped)) {
            // 客户端断开：只停止推送，本轮继续跑完并落库。
            state.detached = true;
            log.info("计划生成连接已断开，本轮继续执行，userId={}", state.userId);
        }
    }

    /**
     * 处理写工具的权限确认请求。
     *
     * @param state 流式状态
     * @param confirmEvent 确认事件
     */
    private void onRequireConfirm(StreamState state, RequireUserConfirmEvent confirmEvent) {
        PendingPlanConfirmVO pending = toPendingConfirm(confirmEvent);
        planConfirmStore.savePending(state.userId, pending);
        if (!Boolean.TRUE.equals(state.hasActivePlan)) {
            // 首次生成：没有可覆盖的计划，服务端直接确认，不需要打扰用户。
            state.autoConfirmCount.incrementAndGet();
            if (state.autoConfirmCount.get() > MAX_AUTO_CONFIRM) {
                log.warn("计划生成自动确认次数异常，userId={}，count={}", state.userId, state.autoConfirmCount.get());
                finish(state, STREAM_ERROR_MESSAGE);
                return;
            }
            state.requestMessages = List.of(confirmMessage(pending, true));
            subscribe(state, state.agent.streamEvents(state.requestMessages, runtimeContext(state)));
            return;
        }
        // 已有生效计划：把确认请求下发给前端，本次流到此结束；用户确认后走 confirm 阶段继续。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "plan_confirm_required");
        payload.put("message", "当前已有生效中的计划，确认后会生成新计划覆盖它（原计划保留为已结束状态）");
        payload.put("existingPlan", currentPlanSummary(state.userId));
        state.terminated.set(true);
        dispose(state);
        planConfirmStore.releaseGenerating(state.userId);
        state.support.sendResult(payload);
        state.support.sendDone();
    }

    /**
     * 处理流内异常。
     *
     * @param state 流式状态
     * @param error 异常
     */
    private void onError(StreamState state, Throwable error) {
        log.error("计划生成流异常，userId={}，sessionId={}，agent={}",
                state.userId, state.sessionId, state.agent.getName(), error);
        // 脏规划态类错误（挂起的待确认调用、框架的 SYSTEM 注入守卫等）发生在任何业务动作之前：
        // 清掉规划态后重试一次，用户不必自己再点一次，也不会把脏状态留在下一轮。
        if (isDirtyPlanStateError(error) && state.retryAttempted.compareAndSet(false, true)) {
            log.warn("检测到计划 Agent 的旧规划态不可用，清理后重试一次，userId={}", state.userId);
            resetPreviousGeneration(state.userId, state.sessionId);
            subscribe(state, state.agent.streamEvents(state.requestMessages, runtimeContext(state)));
            return;
        }
        // 这一轮失败同样清掉规划态：下次点击从干净状态开始（失败发生在业务动作之前，没有副作用）。
        safeClearPlanState(state.userId, state.sessionId);
        finish(state, STREAM_ERROR_MESSAGE);
    }

    /**
     * 判断异常是否属于「计划 Agent 的旧规划态不可用」。
     *
     * <p>两类都出现过：一是上一次生成停在覆盖确认（框架提示 paused for human-in-the-loop confirmation），
     * 二是框架钩子在旧状态上注入了 SYSTEM 消息（Hooks must not inject SYSTEM messages）。它们在业务动作之前
     * 就失败，清理状态后重试是安全的。
     *
     * @param error 异常
     * @return true 表示可以清理状态后重试
     */
    private boolean isDirtyPlanStateError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return message.contains("human-in-the-loop confirmation")
                || message.contains("Hooks must not inject SYSTEM messages");
    }

    /**
     * 处理流正常结束。
     *
     * @param state 流式状态
     */
    private void onComplete(StreamState state) {
        finish(state, null);
    }

    /**
     * 结束一次生成流：取走本次落库的计划并下发结果。
     *
     * @param state 流式状态
     * @param errorMessage 错误提示，为空表示正常结束
     */
    private void finish(StreamState state, String errorMessage) {
        if (!state.terminated.compareAndSet(false, true)) {
            return;
        }
        dispose(state);
        planConfirmStore.releaseGenerating(state.userId);
        if (StringUtils.hasText(errorMessage)) {
            if (!state.detached) {
                state.support.sendError(errorMessage);
            }
            return;
        }
        TrainingPlanRespVO plan = trainingPlanService.consumeSubmittedPlan(state.userId, state.sessionId);
        if (plan == null) {
            // 模型没提交出可用计划：把服务端记录的失败原因带上，避免只给一句笼统提示。
            String reason = trainingPlanService.consumeSubmitFailure(state.userId, state.sessionId);
            if (!state.detached) {
                state.support.sendError(StringUtils.hasText(reason)
                        ? "计划没有生成成功：" + reason + "。请重试。"
                        : NO_PLAN_MESSAGE);
            }
            return;
        }
        if (!state.detached) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "training_plan");
            payload.put("plan", plan);
            state.support.sendResult(payload);
            state.support.sendDone();
        }
    }

    /**
     * 组装计划 Agent 的任务文本。
     *
     * <p>只给确定性的输入（目标岗位、天数、每日时长、按天任务骨架、是否重新规划），薄弱点由模型按提示词调用
     * 只读工具读取 MySQL 得到，不在这条链路里查记忆库。
     *
     * @param profile 求职目标
     * @param days 天数
     * @param dailyMinutes 每日时长
     * @param hasActivePlan 是否已有生效计划
     * @param userId 用户 ID
     * @return 任务文本
     */
    private String buildTaskText(UserProfileRespVO profile, int days, int dailyMinutes,
            boolean hasActivePlan, Long userId) {
        StringBuilder text = new StringBuilder();
        text.append("请为用户生成一份训练计划，最后用 submit_training_plan 提交一次。")
                .append(System.lineSeparator());
        text.append("【目标岗位】").append(profile.getTargetPosition())
                .append("（工作年限 ").append(profile.getWorkYears() == null ? 0 : profile.getWorkYears())
                .append(" 年）").append(System.lineSeparator());
        text.append("【天数】").append(days).append(" 天（从今天算起，第 1 天就是今天）")
                .append(System.lineSeparator());
        text.append("【每天可练时长】").append(dailyMinutes).append(" 分钟").append(System.lineSeparator());
        text.append("【每日任务时长骨架】").append(describeSlots(days, dailyMinutes))
                .append(System.lineSeparator());
        text.append("【薄弱点】请调用只读工具读取该用户当前的薄弱点与掌握度，主题优先安排薄弱点；")
                .append("没有记录就按目标岗位与简历安排，不要编造薄弱点。").append(System.lineSeparator());
        if (hasActivePlan) {
            text.append("【当前计划】该用户已有生效中的计划，本次是重新规划：")
                    .append(describeCurrentProgress(userId))
                    .append("，提交时必须填写 adjustment_reason 说明这次为什么调整。")
                    .append(System.lineSeparator());
        } else {
            text.append("【当前计划】该用户还没有计划，本次是首次生成，adjustment_reason 留空。")
                    .append(System.lineSeparator());
        }
        text.append("【提交要求】字段名照 schema 的 camelCase 写（tasks 里是 dayIndex、topic、questionType、")
                .append("difficulty、durationMinutes、knowledgePoint）；days 填 ").append(days)
                .append("、dailyMinutes 填 ").append(dailyMinutes)
                .append("（这两个值以用户本次输入为准，服务端会覆盖）；每天至少要有一条任务；")
                .append("当天任务时长合计不要超过每日时长；难度按天递进；")
                .append("提交成功后只简要说明取舍，不要重复整份计划。");
        return text.toString();
    }

    /**
     * 把分配器给出的骨架描述成一行文本。
     *
     * @param days 天数
     * @param dailyMinutes 每日时长
     * @return 骨架描述
     */
    private String describeSlots(int days, int dailyMinutes) {
        List<TrainingPlanAllocator.DaySlot> slots = TrainingPlanAllocator.allocate(
                days, dailyMinutes, trainingProperties.getPlan().getMaxTasks());
        StringBuilder text = new StringBuilder();
        for (TrainingPlanAllocator.DaySlot slot : slots) {
            text.append("第 ").append(slot.getDayIndex()).append(" 天 ")
                    .append(slot.getTaskCount()).append(" 个任务");
            text.append("（");
            for (int index = 0; index < slot.getTaskMinutes().size(); index++) {
                if (index > 0) {
                    text.append(" / ");
                }
                text.append(slot.getTaskMinutes().get(index)).append(" 分钟");
            }
            text.append("）；");
        }
        if (slots.size() < days) {
            text.append("任务总数已到上限 ").append(trainingProperties.getPlan().getMaxTasks())
                    .append("，第 ").append(slots.size() + 1).append(" 天及之后不再排任务。");
        }
        return text.toString();
    }

    /**
     * 描述当前计划的进行情况，用于重新规划时说明依据。
     *
     * @param userId 用户 ID
     * @return 进度描述
     */
    private String describeCurrentProgress(Long userId) {
        TrainingPlanRespVO current = trainingPlanService.getCurrentPlan(userId);
        if (current == null || !Boolean.TRUE.equals(current.getHasPlan())) {
            return "暂未读到当前计划进度";
        }
        int total = 0;
        int finished = 0;
        for (TrainingDayRespVO day : current.getDays()) {
            total += day.getTasks().size();
            finished += day.getFinishedCount() == null ? 0 : day.getFinishedCount();
        }
        return "目标岗位 " + current.getTargetPosition()
                + "，剩余 " + current.getRemainingDays() + " 天"
                + "，已完成 " + finished + "/" + total + " 个任务";
    }

    /**
     * 组装已有计划摘要，随确认请求下发给前端。
     *
     * @param userId 用户 ID
     * @return 摘要
     */
    private Map<String, Object> currentPlanSummary(Long userId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        TrainingPlanRespVO current = trainingPlanService.getCurrentPlan(userId);
        if (current == null || !Boolean.TRUE.equals(current.getHasPlan())) {
            return summary;
        }
        int total = 0;
        int finished = 0;
        List<TrainingTaskRespVO> tasks = new ArrayList<>();
        for (TrainingDayRespVO day : current.getDays()) {
            tasks.addAll(day.getTasks());
        }
        for (TrainingTaskRespVO task : tasks) {
            total++;
            if (Boolean.TRUE.equals(task.getFinished())) {
                finished++;
            }
        }
        summary.put("planId", current.getPlanId());
        summary.put("targetPosition", current.getTargetPosition());
        summary.put("endDate", current.getEndDate());
        summary.put("remainingDays", current.getRemainingDays());
        summary.put("totalTasks", total);
        summary.put("finishedTasks", finished);
        return summary;
    }

    /**
     * 组装确认回填消息。
     *
     * @param pending 待确认快照
     * @return 回填消息
     */
    private Msg confirmMessage(PendingPlanConfirmVO pending) {
        return confirmMessage(pending, false);
    }

    /**
     * 组装确认回填消息。
     *
     * <p>回填走框架约定的消息元数据 {@code agentscope_confirm_results}：框架据此校验确认结果与待确认调用是否
     * 匹配，匹配后继续执行被挂起的工具调用。
     *
     * @param pending 待确认快照
     * @param auto 是否为服务端自动确认（首次生成）
     * @return 回填消息
     */
    private Msg confirmMessage(PendingPlanConfirmVO pending, boolean auto) {
        List<ConfirmResult> results = new ArrayList<>(pending.getToolCalls().size());
        for (PendingPlanToolCallVO toolCall : pending.getToolCalls()) {
            ToolUseBlock block = ToolUseBlock.builder()
                    .id(toolCall.getId())
                    .name(toolCall.getName())
                    .input(readInput(toolCall.getInputJson()))
                    .build();
            results.add(new ConfirmResult(true, block));
        }
        return Msg.builder()
                .name(auto ? "system" : USER_MESSAGE_NAME)
                .role(auto ? MsgRole.SYSTEM : MsgRole.USER)
                .textContent(auto ? "已确认写入计划" : "用户已确认覆盖当前计划")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }

    /**
     * 把确认事件里的工具调用存成快照。
     *
     * @param confirmEvent 确认事件
     * @return 待确认快照
     */
    private PendingPlanConfirmVO toPendingConfirm(RequireUserConfirmEvent confirmEvent) {
        PendingPlanConfirmVO pending = new PendingPlanConfirmVO();
        pending.setReplyId(confirmEvent.getReplyId());
        for (ToolUseBlock block : confirmEvent.getToolCalls()) {
            PendingPlanToolCallVO snapshot = new PendingPlanToolCallVO();
            snapshot.setId(block.getId());
            snapshot.setName(block.getName());
            snapshot.setInputJson(writeInput(block.getInput()));
            pending.getToolCalls().add(snapshot);
        }
        return pending;
    }

    /**
     * 序列化工具入参。
     *
     * @param input 入参
     * @return JSON 字符串
     */
    private String writeInput(Map<String, Object> input) {
        try {
            return objectMapper.writeValueAsString(input == null ? Map.of() : input);
        } catch (Exception exception) {
            log.warn("待确认工具入参序列化失败，按空入参处理", exception);
            return "{}";
        }
    }

    /**
     * 反序列化工具入参。
     *
     * @param inputJson 入参 JSON
     * @return 入参 Map
     */
    private Map<String, Object> readInput(String inputJson) {
        if (!StringUtils.hasText(inputJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(inputJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            log.warn("待确认工具入参解析失败，按空入参处理", exception);
            return Map.of();
        }
    }

    /**
     * 组装 result 事件载荷。
     *
     * @param type 结果类型
     * @param message 说明文字
     * @return 载荷
     */
    private Map<String, Object> resultPayload(String type, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("message", message);
        return payload;
    }

    /**
     * 校验天数与每日时长是否落在配置边界内。
     *
     * @param days 天数
     * @param dailyMinutes 每日时长
     */
    private void validateBounds(int days, int dailyMinutes) {
        TrainingProperties.Plan planConfig = trainingProperties.getPlan();
        if (days < 1 || days > planConfig.getMaxDays()
                || dailyMinutes < planConfig.getMinDailyMinutes()
                || dailyMinutes > planConfig.getMaxDailyMinutes()) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
    }

    /**
     * 构造运行上下文：非会话 Agent 也要有稳定的状态槽位。
     *
     * @param state 流式状态
     * @return 运行上下文
     */
    private RuntimeContext runtimeContext(StreamState state) {
        return RuntimeContext.builder()
                .userId(String.valueOf(state.userId))
                .sessionId(state.sessionId)
                .build();
    }

    /**
     * 释放上游订阅。
     *
     * @param state 流式状态
     */
    private void dispose(StreamState state) {
        Disposable subscription = state.subscription.get();
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    /**
     * 清理计划 Agent 的规划态（拒绝覆盖时使用）。
     *
     * @param userId 用户 ID
     * @param sessionId 运行标识
     */
    private void safeClearPlanState(Long userId, String sessionId) {
        try {
            agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME)
                    .clearContext(String.valueOf(userId), sessionId);
        } catch (Exception exception) {
            log.warn("清理计划规划态失败，userId={}", userId, exception);
        }
    }

    /**
     * 清掉上一次生成留下的待确认快照与规划态。
     *
     * <p>计划 Agent 的待确认状态（`agentscope_confirm_request_reply_id` 等）挂在
     * {@code (planner, training-plan-{userId})} 这个状态槽位上：上一次生成停在确认提示、用户关掉页面或刷新后，
     * 状态仍然在。此时直接开一次新的生成，框架会在调用开始就抛「有待确认的工具调用且本次没带确认结论」，
     * 用户看到的就是「计划生成失败」。因此每次生成前先把它清干净。
     *
     * @param userId 用户 ID
     * @param sessionId 运行标识
     */
    private void resetPreviousGeneration(Long userId, String sessionId) {
        planConfirmStore.clearPending(userId);
        safeClearPlanState(userId, sessionId);
    }

    /**
     * 创建本次流的 SSE 推送封装。
     *
     * @return SSE 推送封装
     */
    SseEmitterSupport createEmitterSupport() {
        return new SseEmitterSupport(
                objectMapper, sseTaskScheduler, agentProperties.getStreamTimeoutSeconds());
    }

    /**
     * 计划 Agent 的运行标识。
     *
     * @param userId 用户 ID
     * @return 运行标识
     */
    private String sessionId(Long userId) {
        return SESSION_PREFIX + userId;
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 用户 ID
     */
    private Long currentUserId() {
        Long userId = LoginUserHolder.getUserId();
        if (userId == null) {
            throw new BizException(ErrorConstant.UNAUTHORIZED);
        }
        return userId;
    }

    /**
     * 一次生成流的运行状态。
     *
     * @author wxy
     * @date 2026-10-01
     */
    private static final class StreamState {

        /**
         * SSE 推送封装。
         */
        private final SseEmitterSupport support;

        /**
         * 用户 ID。
         */
        private final Long userId;

        /**
         * 计划 Agent 的运行标识。
         */
        private final String sessionId;

        /**
         * 本次请求的天数，确认阶段为空。
         */
        private final Integer days;

        /**
         * 本次请求的每日时长，确认阶段为空。
         */
        private final Integer dailyMinutes;

        /**
         * 发起生成时是否已有生效计划。
         */
        private final Boolean hasActivePlan;

        /**
         * 计划 Agent 实例。
         */
        private final HarnessAgent agent;

        /**
         * 上游订阅句柄。
         */
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();

        /**
         * 自动确认次数。
         */
        private final AtomicInteger autoConfirmCount = new AtomicInteger();

        /**
         * 本次运行是否已经因「旧规划态不可用」重试过一次。
         */
        private final java.util.concurrent.atomic.AtomicBoolean retryAttempted =
                new java.util.concurrent.atomic.AtomicBoolean();

        /**
         * 本次调用的输入消息，重试时原样重发。
         */
        private List<Msg> requestMessages = List.of();

        /**
         * 订阅代次：每次（重）订阅自增，只有最新一代的事件会改变流的状态。
         */
        private final AtomicInteger epoch = new AtomicInteger();

        /**
         * 本轮是否已结束（终态事件已下发）；用原子标记保证「确认请求」与「流结束」不会重复下发终态。
         */
        private final java.util.concurrent.atomic.AtomicBoolean terminated =
                new java.util.concurrent.atomic.AtomicBoolean();

        /**
         * 客户端是否已断开（断开后仍继续跑完，只是不再推送）。
         */
        private volatile boolean detached;

        /**
         * 构造流式状态。
         *
         * @param support SSE 推送封装
         * @param userId 用户 ID
         * @param sessionId 运行标识
         * @param days 天数
         * @param dailyMinutes 每日时长
         * @param hasActivePlan 是否已有生效计划
         * @param agent 计划 Agent
         */
        StreamState(SseEmitterSupport support, Long userId, String sessionId, Integer days,
                Integer dailyMinutes, Boolean hasActivePlan, HarnessAgent agent) {
            this.support = support;
            this.userId = userId;
            this.sessionId = sessionId;
            this.days = days;
            this.dailyMinutes = dailyMinutes;
            this.hasActivePlan = hasActivePlan;
            this.agent = agent;
        }
    }
}
