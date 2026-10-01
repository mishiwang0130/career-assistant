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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 训练计划生成服务实现（确认之后才落库）。
 *
 * <p>两个阶段：
 *
 * <ol>
 *   <li>阶段一跑计划 Agent：模型读薄弱点、写一份「第 N 天：今天练什么知识点」的短正文，调用写工具
 *       {@code submit_training_plan(planContent)}。写工具受框架权限管控，会触发确认事件——服务端把**计划草稿**和
 *       确认请求一起下发给前端并结束本次流，此时**一个字都还没落库**；</li>
 *   <li>阶段二把用户的确认结论回填给同一次运行：同意才执行写工具、落库正文；不同意则清掉规划态，什么都不写。</li>
 * </ol>
 *
 * <p>起止日期与每天时长来自用户在计划页填写的表单（生成开始时登记），模型不需要、也不允许决定它们。
 * 计划正文只落 MySQL，不往工作区写文件。
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
     * 计划 Agent 的运行标识前缀：非会话 Agent 也需要一个稳定的状态槽位，形如 {@code training-plan-{userId}}。
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
     * 草稿预览的长度上限：确认弹窗里只展示这么多，避免整篇正文塞进确认事件。
     */
    private static final int DRAFT_PREVIEW_MAX_LENGTH = 1200;

    /**
     * Agent 工厂。
     */
    @Resource
    private AgentFactory agentFactory;

    /**
     * 计划服务：读取当前计划、登记表单输入、取走本次生成落库的计划。
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
     * @param reqVO 生成参数（还有几天、每天多长时间）
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
        // 每次生成都从干净的规划态开始：上一次生成可能停在确认提示，那份待确认状态留在 Agent 会话里，
        // 会让本次调用直接被框架拒绝（Agent is paused for human-in-the-loop confirmation）。
        resetPreviousGeneration(userId, sessionId);
        // 同一用户同一时刻只允许一次生成在跑。
        if (!planConfirmStore.markGenerating(userId)) {
            throw new BizException(ErrorConstant.TRAINING_PLAN_GENERATING);
        }
        try {
            HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);
            // 起止日期与每天时长以用户本次表单输入为准，模型不参与。
            trainingPlanService.recordGenerationInput(userId, sessionId, days, dailyMinutes);
            SseEmitterSupport support = createEmitterSupport();
            support.sendMeta(SCENE_TRAINING_PLAN, sessionId, agentProperties.getProvider(),
                    UUID.randomUUID().toString());
            StreamState state = new StreamState(
                    support, userId, sessionId, hasActivePlan(userId), agent);
            state.requestMessages = List.of(Msg.builder()
                    .name(USER_MESSAGE_NAME)
                    .role(MsgRole.USER)
                    .textContent(buildTaskText(profile, days, dailyMinutes))
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
            // 待确认状态已过期（或已被处理）：先清掉 Agent 里可能残留的待确认状态，再让用户重新生成。
            resetPreviousGeneration(userId, sessionId);
            throw new BizException(ErrorConstant.TRAINING_PLAN_CONFIRM_EXPIRED);
        }
        HarnessAgent agent = agentFactory.getAgent(AgentFactory.PLANNER_AGENT_NAME);
        SseEmitterSupport support = createEmitterSupport();
        support.sendMeta(SCENE_TRAINING_PLAN, sessionId, agentProperties.getProvider(),
                UUID.randomUUID().toString());
        if (!approved) {
            // 用户放弃保存：不恢复 Agent，不写库，清掉规划态，当前计划一行不改。
            planConfirmStore.clearPending(userId);
            planConfirmStore.releaseGenerating(userId);
            safeClearPlanState(userId, sessionId);
            support.sendResult(resultPayload("plan_confirm_rejected", "已放弃这份计划，当前计划未做任何修改"));
            support.sendDone();
            return support.getEmitter();
        }
        StreamState state = new StreamState(support, userId, sessionId, hasActivePlan(userId), agent);
        state.requestMessages = List.of(confirmMessage(pending));
        subscribe(state, agent.streamEvents(state.requestMessages, runtimeContext(state)));
        return support.getEmitter();
    }

    /**
     * 订阅一次 Agent 事件流。
     *
     * <p>HITL 重订阅时必须忽略「上一次订阅的后续事件」：写工具触发确认时，框架会先结束当前这轮流，
     * 服务端紧接着用同一份状态重订阅去继续这次运行。如果不做隔离，上一轮流结束时的 onComplete 会走
     * {@code finish(...)}，把刚发起的那次重订阅 dispose 掉，写工具根本没执行。这里用自增的 epoch 给每次订阅编号，
     * 只有编号最新的事件回调才允许处理。
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
        // 写工具触发的权限确认：这就是 HITL 的入口——先把草稿交给用户确认，确认前一个字都不落库。
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
            // 客户端断开：只停止推送，本轮继续跑完（确认阶段由 pending 状态兜住）。
            state.detached = true;
            log.info("计划生成连接已断开，本轮继续执行，userId={}", state.userId);
        }
    }

    /**
     * 处理写工具的权限确认请求：把计划草稿下发给前端并结束本次流，等用户确认。
     *
     * @param state 流式状态
     * @param confirmEvent 确认事件
     */
    private void onRequireConfirm(StreamState state, RequireUserConfirmEvent confirmEvent) {
        PendingPlanConfirmVO pending = toPendingConfirm(confirmEvent);
        planConfirmStore.savePending(state.userId, pending);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "plan_confirm_required");
        payload.put("message", state.hasActivePlan
                ? "确认后会保存这份新计划，原来的计划保留为已结束状态"
                : "确认后会把这份计划保存下来，之后每天读它给你提醒");
        payload.put("draftContent", draftContent(pending));
        payload.put("existingPlan", currentPlanSummary(state.userId));
        state.terminated.set(true);
        dispose(state);
        planConfirmStore.releaseGenerating(state.userId);
        state.support.sendResult(payload);
        state.support.sendDone();
    }

    /**
     * 从待确认的工具调用入参里取出计划草稿，供确认弹窗展示。
     *
     * @param pending 待确认快照
     * @return 计划草稿正文，取不到时返回空串
     */
    private String draftContent(PendingPlanConfirmVO pending) {
        for (PendingPlanToolCallVO toolCall : pending.getToolCalls()) {
            Map<String, Object> input = readInput(toolCall.getInputJson());
            Object content = input.get("planContent");
            if (content instanceof String text && StringUtils.hasText(text)) {
                String trimmed = text.strip();
                return trimmed.length() <= DRAFT_PREVIEW_MAX_LENGTH
                        ? trimmed : trimmed.substring(0, DRAFT_PREVIEW_MAX_LENGTH) + "…";
            }
        }
        return "";
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
            // 没有产出：把服务端记录的失败原因带上，避免只给一句笼统提示。
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
     * <p>只给确定性的输入（目标岗位、天数、每日时长）；薄弱点由模型按提示词调用只读工具读取。
     * 正文要求一天一行、一句话概括，起止日期与时长由服务端按表单落库。
     *
     * @param profile 求职目标
     * @param days 天数
     * @param dailyMinutes 每日时长
     * @return 任务文本
     */
    private String buildTaskText(UserProfileRespVO profile, int days, int dailyMinutes) {
        StringBuilder text = new StringBuilder();
        text.append("请为用户生成一份训练计划正文，最后用 submit_training_plan 提交一次。")
                .append(System.lineSeparator());
        text.append("【目标岗位】").append(profile.getTargetPosition())
                .append("（工作年限 ").append(profile.getWorkYears() == null ? 0 : profile.getWorkYears())
                .append(" 年）").append(System.lineSeparator());
        text.append("【天数】").append(days).append(" 天（从今天算起，第 1 天就是今天，截止日期与每天时长由系统按用户输入保存，"
                + "你不用填）").append(System.lineSeparator());
        text.append("【每天可练时长】").append(dailyMinutes).append(" 分钟").append(System.lineSeparator());
        text.append("【薄弱点】请调用只读工具读取该用户当前的薄弱点与掌握度，优先安排薄弱点；")
                .append("没有记录就按目标岗位安排，不要编造薄弱点。").append(System.lineSeparator());
        text.append("【正文要求】一天一行，写成「第 N 天：今天练什么知识点」，一句话概括即可，"
                + "共 ").append(days).append(" 行；不要写题型、难度、时长分钟数与表格，正文尽量短。")
                .append(System.lineSeparator());
        text.append("【提交要求】把这份正文原样通过 submit_training_plan 提交（只需要正文，重新规划时再补一句调整原因）；")
                .append("提交前会有一次人工确认，用户不同意就不会保存；提交成功后只简要说明取舍。");
        return text.toString();
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
        summary.put("planId", current.getPlanId());
        summary.put("targetPosition", current.getTargetPosition());
        summary.put("endDate", current.getEndDate());
        summary.put("remainingDays", current.getRemainingDays());
        return summary;
    }

    /**
     * 组装确认回填消息。
     *
     * <p>回填走框架约定的消息元数据 {@code agentscope_confirm_results}，且**消息角色必须是 USER**：
     * 框架在 {@code AgentBase.notifyPreCall} 里禁止本次输入里出现 SYSTEM 消息。
     *
     * @param pending 待确认快照
     * @return 回填消息
     */
    private Msg confirmMessage(PendingPlanConfirmVO pending) {
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
                .name(USER_MESSAGE_NAME)
                .role(MsgRole.USER)
                .textContent("用户已确认，请保存这份计划")
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
     * 清理计划 Agent 的规划态（放弃保存或失败时使用）。
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
     * <p>计划 Agent 的待确认状态挂在 {@code (planner, training-plan-{userId})} 这个状态槽位上：上一次生成停在
     * 确认提示、用户关掉页面或刷新后，状态仍然在。此时直接开一次新的生成，框架会在调用开始就抛
     * 「有待确认的工具调用且本次没带确认结论」，用户看到的就是「计划生成失败」。因此每次生成前先把它清干净。
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
     * 判断用户当前是否有生效计划（生成与确认阶段都会用到）。
     *
     * @param userId 用户 ID
     * @return true 表示有生效计划
     */
    private boolean hasActivePlan(Long userId) {
        return trainingPlanService.hasActivePlan(userId);
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
         * 发起生成时是否已有生效计划（决定确认文案）。
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
         * 本次运行是否已经因「旧规划态不可用」重试过一次。
         */
        private final AtomicBoolean retryAttempted = new AtomicBoolean();

        /**
         * 订阅代次：每次（重）订阅自增，只有最新一代的事件会改变流的状态。
         */
        private final AtomicInteger epoch = new AtomicInteger();

        /**
         * 本轮是否已结束（终态事件已下发）。
         */
        private final AtomicBoolean terminated = new AtomicBoolean();

        /**
         * 本次调用的输入消息，重试时原样重发。
         */
        private List<Msg> requestMessages = List.of();

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
         * @param hasActivePlan 是否已有生效计划
         * @param agent 计划 Agent
         */
        StreamState(SseEmitterSupport support, Long userId, String sessionId,
                Boolean hasActivePlan, HarnessAgent agent) {
            this.support = support;
            this.userId = userId;
            this.sessionId = sessionId;
            this.hasActivePlan = hasActivePlan;
            this.agent = agent;
        }
    }
}
