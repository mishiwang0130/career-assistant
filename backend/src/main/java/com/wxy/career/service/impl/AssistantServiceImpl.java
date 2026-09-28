package com.wxy.career.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.redis.RedisUtil;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.common.sse.SseEmitterSupport;
import com.wxy.career.common.sse.SseEvent;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.AssistantService;
import com.wxy.career.util.AgentEventMapper;
import com.wxy.career.util.AgentScopeStateKeyUtil;
import com.wxy.career.vo.AssistantChatReqVO;
import com.wxy.career.vo.AssistantMessageRespVO;
import com.wxy.career.vo.PageRespVO;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
     * 会话锁租约在流超时时间之上的冗余，单位为秒。
     *
     * <p>流在异常路径下可能走不到释放逻辑，留出冗余保证锁最终一定自动过期。
     */
    private static final long SESSION_LOCK_LEASE_EXTRA_SECONDS = 60L;

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
     * Redis 操作工具，用于会话并发锁。
     */
    @Resource
    private RedisUtil redisUtil;

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
        String content = reqVO.getContent().trim();

        // 同一会话必须串行：并发请求会交错读写同一份 Agent 状态，抢不到锁直接拒绝而不是排队等待。
        String lockKey = AgentScopeStateKeyUtil.sessionLockKey(String.valueOf(userId), sessionId);
        String lockToken = acquireSessionLock(lockKey);
        try {
            // 用户消息先落库，保证即使流式中断历史记录也完整。
            assistantMessageService.saveMessage(userId, sessionId, MessageRoleEnum.USER, content);

            SseEmitterSupport support = new SseEmitterSupport(
                    objectMapper, sseTaskScheduler, agentProperties.getStreamTimeoutSeconds());
            support.sendMeta(
                    SCENE_ASSISTANT, sessionId, agentProperties.getProvider(), UUID.randomUUID().toString());

            StreamState state = new StreamState(support, userId, sessionId, lockKey, lockToken);
            ReActAgent agent = agentFactory.getAgent(AgentFactory.MAIN_AGENT_NAME);
            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .userId(String.valueOf(userId))
                    .sessionId(sessionId)
                    .build();
            Msg userMessage = Msg.builder()
                    .name(USER_MESSAGE_NAME)
                    .role(MsgRole.USER)
                    .textContent(content)
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
            // 启动阶段失败时必须立刻释放，否则该会话会被锁到租约结束。
            releaseSessionLock(lockKey, lockToken);
            throw exception;
        }
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
        return assistantMessageService.listMessages(userId, requireSessionId(sessionId), pageNum, pageSize);
    }

    /**
     * 清空当前用户指定会话的历史消息与 Agent 上下文。
     *
     * @param sessionId 会话 ID
     */
    @Override
    public void clearSession(String sessionId) {
        Long userId = currentUserId();
        String normalizedSessionId = requireSessionId(sessionId);
        assistantMessageService.clearSession(userId, normalizedSessionId);
        // 只清消息表不够：Agent 仍会记得上下文，必须同时清理会话状态。
        agentFactory.clearSession(userId, normalizedSessionId);
    }

    /**
     * 处理单条 AgentScope 事件。
     *
     * @param state 流式会话状态
     * @param event AgentScope 事件
     */
    private void onNext(StreamState state, AgentEvent event) {
        if (state.terminated.get()) {
            return;
        }
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
        if (!state.support.send(mapped)) {
            // 连接已断开，停止上游推理并保留已生成内容。
            finish(state, null, false);
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
        releaseSessionLock(state.lockKey, state.lockToken);
        if (!sendTerminalEvent) {
            return;
        }
        if (StringUtils.hasText(errorMessage)) {
            state.support.sendError(errorMessage);
        } else {
            state.support.sendDone();
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
                    state.userId, state.sessionId, MessageRoleEnum.ASSISTANT, state.reply.toString());
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
            throw new BizException(ErrorConstant.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
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
            throw new BizException(ErrorConstant.PARAM_ERROR, HttpStatus.BAD_REQUEST);
        }
        return sessionId.trim();
    }

    /**
     * 获取会话并发锁。
     *
     * <p>锁在请求线程获取、在流式线程释放，线程绑定的分布式锁无法跨线程解锁，
     * 因此用「setIfAbsent + 持有者令牌」实现与线程无关的互斥锁，租约到期后自动释放。
     *
     * @param lockKey 锁键
     * @return 本次持有的随机令牌
     */
    private String acquireSessionLock(String lockKey) {
        String lockToken = UUID.randomUUID().toString();
        long leaseSeconds = agentProperties.getStreamTimeoutSeconds() + SESSION_LOCK_LEASE_EXTRA_SECONDS;
        Boolean acquired = redisUtil.setIfAbsent(lockKey, lockToken, leaseSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(ErrorConstant.SESSION_BUSY, HttpStatus.TOO_MANY_REQUESTS);
        }
        return lockToken;
    }

    /**
     * 释放会话并发锁。
     *
     * <p>只有令牌仍然匹配才删除，避免租约到期后误删其它请求刚拿到的锁；
     * 释放失败由租约到期兜底，不影响主流程。
     *
     * @param lockKey 锁键
     * @param lockToken 本次持有的随机令牌
     */
    private void releaseSessionLock(String lockKey, String lockToken) {
        if (lockKey == null || lockToken == null) {
            return;
        }
        try {
            if (lockToken.equals(redisUtil.get(lockKey, String.class))) {
                redisUtil.delete(lockKey);
            }
        } catch (Exception exception) {
            log.warn("释放会话并发锁失败，将由租约到期后自动释放，lockKey={}", lockKey, exception);
        }
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
         * 会话并发锁键。
         */
        private final String lockKey;

        /**
         * 会话并发锁持有者令牌。
         */
        private final String lockToken;

        /**
         * 已生成的回复文本。
         */
        private final StringBuilder reply = new StringBuilder();

        /**
         * 是否已结束，保证结束逻辑只执行一次。
         */
        private final AtomicBoolean terminated = new AtomicBoolean(false);

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
         * @param lockKey 会话并发锁键
         * @param lockToken 会话并发锁持有者令牌
         */
        private StreamState(
                SseEmitterSupport support, Long userId, String sessionId, String lockKey, String lockToken) {
            this.support = support;
            this.userId = userId;
            this.sessionId = sessionId;
            this.lockKey = lockKey;
            this.lockToken = lockToken;
        }
    }
}
