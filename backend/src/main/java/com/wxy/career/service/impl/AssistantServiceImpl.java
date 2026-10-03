package com.wxy.career.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.sse.SseEmitterSupport;
import com.wxy.career.common.sse.SseEvent;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.middleware.UserLongTermMemoryAdapter;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.AssistantService;
import com.wxy.career.service.ChatSessionService;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.InterviewEvaluationService;
import com.wxy.career.service.InterviewReviewService;
import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.util.AgentEventMapper;
import com.wxy.career.util.AgentScopeStateKeyUtil;
import com.wxy.career.vo.AssistantChatReqVO;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.InterviewProgressResultVO;
import com.wxy.career.vo.InterviewReportRespVO;
import com.wxy.career.vo.InterviewResultRespVO;
import com.wxy.career.vo.InterviewStateRespVO;
import com.wxy.career.vo.PageRespVO;
import com.wxy.career.vo.ResumeDiagnosisResultVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用助手服务实现。
 *
 * <p>对话链路：在请求线程解析登录用户并落库用户消息，随后把 AgentScope 事件流转到弹性线程池订阅，
 * 逐条映射成 SSE 事件推送；流结束时落库助手回复。SSE 一旦开始，失败只通过 error 事件表达。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Service
public class AssistantServiceImpl implements AssistantService {

    /**
     * 对话场景标识，写入 meta 事件与后续埋点。
     */
    private static final String SCENE_ASSISTANT = "assistant";

    /**
     * 用户消息在 AgentScope 中的发送者名。
     */
    private static final String USER_MESSAGE_NAME = "user";

    /**
     * 流内异常对外暴露的统一提示，避免把内部堆栈泄漏给前端。
     */
    private static final String STREAM_ERROR_MESSAGE = "对话服务暂时不可用，请稍后重试";

    /**
     * 会话并发锁持有者标识序号。
     *
     * <p>锁的持有者标识由每次请求自增生成，与线程无关：Redisson 用「客户端 ID:持有者标识」记录锁
     * 归属并据此判断重入，如果用线程 ID，请求线程被复用后同一会话的下一个请求会被判成「同线程
     * 重入」而直接拿到锁，互斥形同虚设。
     */
    private static final AtomicLong LOCK_OWNER_SEQUENCE = new AtomicLong();

    /**
     * Agent 工厂。
     */
    @Resource
    private AgentFactory agentFactory;

    /**
     * Agent 配置。
     */
    @Resource
    private AgentProperties agentProperties;

    /**
     * 消息服务。
     */
    @Resource
    private AssistantMessageService assistantMessageService;

    /**
     * 会话中心服务，用于回写标题、活跃时间以及校验会话归属。
     */
    @Resource
    private ChatSessionService chatSessionService;

    /**
     * JSON 序列化组件。
     */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * SSE 心跳调度器。
     */
    @Resource
    private ThreadPoolTaskScheduler sseTaskScheduler;

    /**
     * Redisson 客户端，用于会话并发锁（与 AgentScope 会话状态存储共用同一个客户端）。
     */
    @Resource
    private RedissonClient redissonClient;

    /**
     * 简历诊断服务，用于在流结束时下发结构化诊断结论。
     */
    @Resource
    private ResumeDiagnosisService resumeDiagnosisService;

    /**
     * 发送一条消息并流式返回 Agent 回复。
     *
     * @param reqVO 对话请求
     * @return SSE 响应对象
     */
    @Override
    public SseEmitter chat(AssistantChatReqVO reqVO) {
        // 用户身份只取登录态，绝不接受前端传入的 userId，避免越权访问他人会话。
        Long userId = currentUserId();
        String sessionId = reqVO.getSessionId().trim();
        Long sessionIdValue = parseSessionId(sessionId);
        String content = reqVO.getContent().trim();

        // 同一会话必须串行：并发请求会交错读写同一份 Agent 状态，抢不到锁直接拒绝而不是排队等待。
        String lockKey = AgentScopeStateKeyUtil.sessionLockKey(String.valueOf(userId), sessionId);
        SessionLock sessionLock = acquireSessionLock(lockKey);
        try {
            // F5 模拟面试：面试会话在进流前完成准入校验（求职目标必填、未结束）并记下本回合的回答；
            // 助手会话返回 null，后面按原链路走。
            InterviewStateRespVO interviewState = interviewFlowService.prepareTurn(userId, sessionId, content);
            // 用户消息先落库，保证即使流式中断历史记录也完整。
            assistantMessageService.saveMessage(userId, sessionIdValue, MessageRoleEnum.USER, content);
            // 消息落库后回写会话标题与活跃时间；会话元数据缺失时该方法只记日志，不会影响对话。
            chatSessionService.recordUserMessage(userId, sessionId, content);

            SseEmitterSupport support = createEmitterSupport();
            support.sendMeta(
                    interviewState == null ? SCENE_ASSISTANT : SCENE_INTERVIEW,
                    sessionId, agentProperties.getProvider(), UUID.randomUUID().toString());

            StreamState state = new StreamState(support, userId, sessionId, sessionIdValue, sessionLock);
            // 面试会话走专属 Agent：自己的提示词、工具白名单与评分子 Agent。
            state.interview = interviewState != null;
            if (state.interview) {
                // 评分由平台编排：先让评分子 Agent 完成本题评分（结论只进运行态缓冲，正文丢弃），
                // 面试官随后按 record_interview_answer 的指令出题，既看不到也抄不到评分内容。
                interviewEvaluationService.evaluate(userId, sessionId, content);
            }
            HarnessAgent agent = agentFactory.getAgent(interviewState == null
                    ? AgentFactory.MAIN_AGENT_NAME : AgentFactory.INTERVIEWER_AGENT_NAME);
            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .userId(String.valueOf(userId))
                    .sessionId(sessionId)
                    .build();
            Msg userMessage = Msg.builder()
                    .name(USER_MESSAGE_NAME)
                    .role(MsgRole.USER)
                    .textContent(content)
                    // 长期记忆适配层按 userId 元数据做用户隔离（Mem0 实例只绑定 userId，会话维度只用于日志），
                    // 因此必须在用户消息上带上它；模型看不到元数据，也不参与身份判定。
                    .metadata(Map.of(
                            UserLongTermMemoryAdapter.METADATA_USER_ID, String.valueOf(userId),
                            UserLongTermMemoryAdapter.METADATA_SESSION_ID, sessionId))
                    .build();

            // 流式推理是阻塞型 IO，放到弹性线程池执行，避免占用 MVC 请求线程。
            Disposable subscription = agent.streamEvents(userMessage, runtimeContext)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            event -> onNext(state, event),
                            error -> onError(state, error),
                            () -> onComplete(state));
            state.subscription.set(subscription);
            return support.getEmitter();
        } catch (RuntimeException exception) {
            // 启动阶段失败时必须立刻释放，否则该会话要等到看门狗停摆后才会自动解锁。
            releaseSessionLock(sessionLock);
            throw exception;
        }
    }

    /**
     * 创建本次流的 SSE 推送封装。
     *
     * <p>抽成方法是为了让单测能注入「推送必然失败」的实现，验证客户端断开后本轮仍会跑完并整体落库；
     * 生产路径固定使用 {@link SseEmitterSupport}。
     *
     * @return SSE 推送封装
     */
    SseEmitterSupport createEmitterSupport() {
        return new SseEmitterSupport(
                objectMapper, sseTaskScheduler, agentProperties.getStreamTimeoutSeconds());
    }

    /**
     * 分页查询当前用户指定会话的历史消息。
     *
     * @param sessionId 会话 ID
     * @param pageNum 页码，从 1 开始
     * @param pageSize 每页条数
     * @return 分页消息
     */
    @Override
    public PageRespVO<AssistantMessageRespVO> listMessages(String sessionId, long pageNum, long pageSize) {
        Long userId = currentUserId();
        String normalizedSessionId = requireSessionId(sessionId);
        // 会话不存在、已删除或跨账号时统一抛「会话不存在」，前端据此回到新建会话草稿态。
        chatSessionService.requireOwnedSession(userId, normalizedSessionId);
        return assistantMessageService.listMessages(
                userId, parseSessionId(normalizedSessionId), pageNum, pageSize);
    }

    /**
     * 处理单条 AgentScope 事件。
     *
     * <p>连接断开（用户切走会话、关闭页面、刷新）时只停止推送，**不中断本轮推理**：模型继续把这一轮跑完，
     * 结束时整体落库，用户回到该会话能看到完整回答。推送失败由 {@code SseEmitterSupport} 兜住并返回 false。
     *
     * @param state 流式会话状态
     * @param event AgentScope 事件
     */
    private void onNext(StreamState state, AgentEvent event) {
        if (state.terminated.get()) {
            return;
        }
        // 子 Agent 的事件（含它输出的评分 JSON）会被框架转发到父 Agent 的同一事件流里，
        // 这类内部过程即不推送也不落库，避免出现在用户看到的回答里。
        if (AgentEventMapper.isSubagentEvent(event)) {
            log.debug("跳过子 Agent 转发事件，eventType={}，source={}", event.getType(), event.getSource());
            return;
        }
        // 先累计文本：连接断了这一轮也要有完整回复可落库。
        appendReply(state, event);
        SseEvent mapped = AgentEventMapper.map(event);
        if (mapped == null) {
            return;
        }
        if (AgentEventMapper.isError(mapped)) {
            // 超出最大步数等终态错误：推送 error 后立即结束，避免继续空转。
            finish(state, extractMessage(mapped), true);
            return;
        }
        if (state.detached) {
            return;
        }
        if (!state.support.send(mapped)) {
            // 客户端已断开：只标记不再推送，继续消费上游直到本轮结束，避免半截回答落库。
            state.detached = true;
            log.info("SSE 连接已断开，本轮继续执行并在结束后落库，userId={}，sessionId={}",
                    state.userId, state.sessionId);
        }
    }

    /**
     * 处理流内异常。
     *
     * @param state 流式会话状态
     * @param error 异常
     */
    private void onError(StreamState state, Throwable error) {
        log.error("Agent 流式对话异常，userId={}，sessionId={}", state.userId, state.sessionId, error);
        finish(state, STREAM_ERROR_MESSAGE, true);
    }

    /**
     * 处理流正常结束。
     *
     * @param state 流式会话状态
     */
    private void onComplete(StreamState state) {
        finish(state, null, true);
    }

    /**
     * 结束一次流式对话：落库已生成内容并按需发送结束事件。
     *
     * <p>正常结束时先下发结构化产物（当前是简历诊断结论，没有就不发），再发 done：结构化结果属于
     * 本次回答的一部分，必须在流结束前到达前端。异常结束时只发 error，不给半成品结果。
     *
     * @param state 流式会话状态
     * @param errorMessage 错误提示，为空表示正常结束
     * @param sendTerminalEvent 是否向前端发送结束事件，连接已断开时为 false
     */
    private void finish(StreamState state, String errorMessage, boolean sendTerminalEvent) {
        if (!state.terminated.compareAndSet(false, true)) {
            return;
        }
        saveAssistantMessage(state);
        dispose(state);
        releaseSessionLock(state.sessionLock);
        // 连接已断开的会话不再推送任何事件（包括结果与 done），但上面的落库照常执行。
        if (!sendTerminalEvent || state.detached) {
            return;
        }
        if (StringUtils.hasText(errorMessage)) {
            // 异常结束时面试回合不落库：进度停在出错前那一步，用户重新作答即可，避免半截推进。
            if (state.interview) {
                interviewFlowService.discardTurn(state.userId, state.sessionId);
            }
            state.support.sendError(errorMessage);
        } else {
            if (state.interview) {
                sendInterviewProgress(state);
            } else {
                sendStructuredResult(state);
            }
            state.support.sendDone();
        }
    }

    /**
     * 下发本次流的结构化产物。
     *
     * <p>简历诊断结论由子 Agent 通过提交工具暂存在运行态缓冲里，这里取走并作为 {@code result} 事件
     * 下发；没有结构化产物（普通问答）时不发 result。取用失败只记日志，不能影响正常结束。
     *
     * @param state 流式会话状态
     */
    private void sendStructuredResult(StreamState state) {
        ResumeDiagnosisResultVO diagnosis;
        try {
            diagnosis = resumeDiagnosisService.consumeDiagnosis(state.userId, state.sessionId);
        } catch (Exception exception) {
            log.warn("读取结构化诊断结论失败，userId={}，sessionId={}", state.userId, state.sessionId, exception);
            return;
        }
        if (diagnosis != null && !state.support.send(SseEvent.result(diagnosis))) {
            log.warn("结构化诊断结论下发失败，连接可能已断开，sessionId={}", state.sessionId);
        }
    }

    /**
     * 累计回复文本，供流结束后落库。
     *
     * @param state 流式会话状态
     * @param event AgentScope 事件
     */
    private void appendReply(StreamState state, AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent deltaEvent) {
            state.reply.append(deltaEvent.getDelta());
            return;
        }
        // 模型未返回增量文本时，用最终结果兜底，保证历史记录不为空。
        if (state.reply.length() == 0 && event instanceof AgentResultEvent resultEvent) {
            Msg result = resultEvent.getResult();
            if (result != null && result.getTextContent() != null) {
                state.reply.append(result.getTextContent());
            }
        }
    }

    /**
     * 落库助手回复，内容为空时不写库。
     *
     * @param state 流式会话状态
     */
    private void saveAssistantMessage(StreamState state) {
        if (state.reply.length() == 0) {
            return;
        }
        try {
            assistantMessageService.saveMessage(
                    state.userId, state.sessionIdValue, MessageRoleEnum.ASSISTANT, state.reply.toString());
        } catch (Exception exception) {
            // 落库失败不能影响已经推送给用户的内容。
            log.error("助手回复落库失败，userId={}，sessionId={}", state.userId, state.sessionId, exception);
        }
    }

    /**
     * 释放上游订阅，停止后续推理。
     *
     * @param state 流式会话状态
     */
    private void dispose(StreamState state) {
        Disposable subscription = state.subscription.get();
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }

    /**
     * 从错误事件中取出错误提示。
     *
     * @param event SSE 错误事件
     * @return 错误提示
     */
    private String extractMessage(SseEvent event) {
        Object data = event.getData();
        if (data instanceof java.util.Map<?, ?> dataMap) {
            Object message = dataMap.get("message");
            if (message != null) {
                return String.valueOf(message);
            }
        }
        return STREAM_ERROR_MESSAGE;
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
     * 校验会话 ID。
     *
     * @param sessionId 会话 ID
     * @return 去空格后的会话 ID
     */
    private String requireSessionId(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            // 业务异常统一返回 HTTP 200，失败语义由 code 表达。
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        return sessionId.trim();
    }

    /**
     * 解析会话 ID。
     *
     * <p>会话 ID 已被参数校验限制为纯数字，这里再兜底一次，避免内部调用绕过校验后把异常抛到流里。
     *
     * @param sessionId 会话 ID 字符串
     * @return 会话 ID
     */
    private Long parseSessionId(String sessionId) {
        try {
            return Long.valueOf(sessionId);
        } catch (NumberFormatException exception) {
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
    }

    /**
     * 获取会话并发锁。
     *
     * <p>锁交给 Redisson 的 {@link RLock} 托管：只尝试一次，抢不到立刻失败而不排队；不设固定租约，
     * 拿到锁后由看门狗按锁存活自动续期——流跑多久就续多久，不会出现「流还没结束、租约已经到期」
     * 而被第二个请求插队的情况。
     *
     * <p>持有者标识由本次请求生成，不取线程 ID：锁在请求线程获取、在流式线程释放，而 Tomcat 线程
     * 会被复用，同一个线程跑同一会话的下一个请求时会被 Redisson 判成「同线程重入」直接拿到锁。
     *
     * @param lockKey 锁键
     * @return 锁句柄，释放时必须原样传回
     */
    private SessionLock acquireSessionLock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        long ownerId = LOCK_OWNER_SEQUENCE.incrementAndGet();
        // 带持有者标识的异步抢占：只尝试一次，不等待也不订阅锁通道，抢不到直接返回 false。
        if (!Boolean.TRUE.equals(awaitLockResult(lock.tryLockAsync(ownerId)))) {
            // 会话占用属于业务异常，按统一约定返回 HTTP 200，由 code 1050 表达失败语义。
            throw new BizException(ErrorConstant.SESSION_BUSY);
        }
        return new SessionLock(lock, ownerId);
    }

    /**
     * 释放会话并发锁。
     *
     * <p>释放发生在流式线程而不是获取锁的请求线程，所以必须带上获取时的持有者标识：只有标识仍然
     * 匹配，Redisson 才真正删除锁，不会误删看门狗超时后由别的请求重新拿到的锁。释放失败只记日志，
     * 持有者一旦退出，看门狗停止续期，锁会在看门狗超时后自动过期，不会永久占住会话。
     *
     * @param sessionLock 锁句柄，为空表示本次没拿到锁
     */
    private void releaseSessionLock(SessionLock sessionLock) {
        if (sessionLock == null) {
            return;
        }
        try {
            awaitLockResult(sessionLock.lock().unlockAsync(sessionLock.ownerId()));
        } catch (RuntimeException exception) {
            log.warn("释放会话并发锁失败，将由看门狗停摆后自动过期，lockName={}",
                    sessionLock.lock().getName(), exception);
        }
    }

    /**
     * 阻塞等待 Redisson 锁命令结果。
     *
     * <p>抢占与释放都要拿到结果才能判断是否成功，这里把异步结果同步取回；中断异常按约定恢复中断
     * 标记后再抛出，不静默吞掉。
     *
     * @param future Redisson 异步结果
     * @param <T> 结果类型
     * @return 命令结果
     */
    private static <T> T awaitLockResult(RFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RedisException("等待 Redis 锁命令被中断", exception);
        } catch (ExecutionException exception) {
            throw new RedisException("Redis 锁命令执行失败", exception.getCause());
        }
    }

    /**
     * 会话并发锁句柄。
     *
     * <p>锁对象与持有者标识必须成对传递：持有者标识是释放锁的唯一凭据，且只在本次请求内有效。
     *
     * @param lock Redisson 锁对象
     * @param ownerId 锁持有者标识
     * @author wxy
     * @date 2026-10-01
     */
    private record SessionLock(RLock lock, long ownerId) {
    }

    /**
     * 单次流式对话的状态。
     *
     * @author wxy
     * @date 2026-09-28
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
         * 会话 ID。
         */
        private final String sessionId;

        /**
         * 会话 ID 的数值形式，用于消息表读写。
         */
        private final Long sessionIdValue;

        /**
         * 会话并发锁句柄，本轮结束时释放。
         */
        private final SessionLock sessionLock;

        /**
         * 已生成的回复文本。
         */
        private final StringBuilder reply = new StringBuilder();

        /**
         * 是否已结束，保证结束逻辑只执行一次。
         */
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        /**
         * 客户端是否已断开：断开后不再推送事件，但本轮继续跑完并落库。
         */
        private volatile boolean detached;

        /**
         * 本次流是否属于模拟面试会话：结束时走面试的落库与进度下发，不走简历诊断结果。
         */
        private boolean interview;

        /**
         * 上游订阅句柄，结束时可主动释放。
         */
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();

        /**
         * 构造流式会话状态。
         *
         * @param support SSE 推送封装
         * @param userId 用户 ID
         * @param sessionId 会话 ID
         * @param sessionIdValue 会话 ID 的数值形式
         * @param sessionLock 会话并发锁句柄
         */
        private StreamState(
                SseEmitterSupport support,
                Long userId,
                String sessionId,
                Long sessionIdValue,
                SessionLock sessionLock) {
            this.support = support;
            this.userId = userId;
            this.sessionId = sessionId;
            this.sessionIdValue = sessionIdValue;
            this.sessionLock = sessionLock;
        }
    }

    // ==================== F5 模拟面试 ====================

    /**
     * 面试场景标识，写入 meta 事件，与前端 {@code ChatScene} 的取值口径一致。
     */
    private static final String SCENE_INTERVIEW = "interview";

    /**
     * 面试流程服务，负责回合落库与进度下发。
     */
    @Resource
    private InterviewFlowService interviewFlowService;

    /**
     * 面试评分服务：面试官开流前先把本题评分交给评分子 Agent 完成。
     */
    @Resource
    private InterviewEvaluationService interviewEvaluationService;

    /**
     * 面试复盘服务（F6）：每回合下发逐题点评、沉淀掌握度，结束时启动报告生成。
     */
    @Resource
    private InterviewReviewService interviewReviewService;

    /**
     * 落库本回合的面试问答并下发最新进度与难度。
     *
     * <p>下发时机与简历诊断结论一致：流正常结束前、{@code done} 之前。开场那一轮只提问、没有待落库
     * 的问答，此时不下发进度事件（界面进度由面试状态接口给出）。落库失败只记日志，不影响已推送的正文。
     *
     * @param state 流式会话状态
     */
    private void sendInterviewProgress(StreamState state) {
        InterviewStateRespVO progress;
        try {
            progress = interviewFlowService.commitTurn(state.userId, state.sessionId);
        } catch (Exception exception) {
            log.error("面试回合落库失败，userId={}，sessionId={}", state.userId, state.sessionId, exception);
            return;
        }
        if (progress == null) {
            return;
        }
        // F6：每回合沉淀掌握度与薄弱点；面试结束的那一轮同时用后台子 Agent 启动报告生成。
        InterviewReportRespVO reportState = interviewReviewService.afterTurnCommitted(
                state.userId, state.sessionId, Boolean.TRUE.equals(progress.getFinished()));
        if (!state.support.send(SseEvent.result(InterviewProgressResultVO.from(progress)))) {
            log.warn("面试进度下发失败，连接可能已断开，sessionId={}", state.sessionId);
        }
        if (Boolean.TRUE.equals(progress.getFinished())) {
            // 面试结束：同一轮里再下发逐题结果（哪里答得不好 + 标准答案），界面据此渲染结果卡片。
            try {
                InterviewResultRespVO interviewResult =
                        interviewFlowService.getResult(state.userId, state.sessionId);
                if (!state.support.send(SseEvent.result(interviewResult))) {
                    log.warn("面试结果下发失败，连接可能已断开，sessionId={}", state.sessionId);
                }
            } catch (Exception exception) {
                log.error("面试结果组装失败，userId={}，sessionId={}",
                        state.userId, state.sessionId, exception);
            }
            // F6：再下发一次报告状态（生成中或派发失败），界面据此显示「报告生成中」或失败重试入口。
            if (reportState != null && !state.support.send(SseEvent.result(reportState))) {
                log.warn("面试报告状态下发失败，连接可能已断开，sessionId={}", state.sessionId);
            }
        }
    }

}
