package com.wxy.career.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wxy.career.common.auth.LoginUser;
import com.wxy.career.common.auth.LoginUserHolder;
import com.wxy.career.common.enums.MessageRoleEnum;
import com.wxy.career.common.sse.SseEmitterSupport;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.AssistantMessageService;
import com.wxy.career.service.ChatSessionService;
import com.wxy.career.service.InterviewFlowService;
import com.wxy.career.service.ResumeDiagnosisService;
import com.wxy.career.vo.AssistantChatReqVO;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import reactor.core.publisher.Flux;

/**
 * 对话流在客户端断开后的行为测试。
 *
 * <p>用户切走会话、关页面、刷新都会中断 SSE 连接（前端 abort + 服务端推送失败）。此时这一轮**不能**被掐掉：
 * 必须继续消费模型输出直到本轮结束，再把完整回复落库，用户切回来才能看到完整结果。
 *
 * <p>这里不搭真实 HTTP 连接，而是直接替换推送封装，让推送在第二段回复前失败——正是客户端断开时
 * {@code SseEmitterSupport.send} 返回 false 的情形。模型用事件流桩注入，不访问网络。
 *
 * @author wxy
 * @date 2026-09-29
 */
class AssistantServiceImplStreamTest {

    /**
     * 被注入登录态的用户 ID。
     */
    private static final long USER_ID = 1L;

    /**
     * 会话 ID。
     */
    private static final String SESSION_ID = "1";

    /**
     * 事件之间的间隔，单位毫秒；留出时间窗让推送失败真的能取消上游（若实现错误地中断本轮）。
     */
    private static final long CHUNK_INTERVAL_MILLIS = 150L;

    /**
     * 消息服务 mock。
     */
    private AssistantMessageService assistantMessageService;

    /**
     * Agent 工厂 mock。
     */
    private AgentFactory agentFactory;

    /**
     * 被测服务。
     */
    private AssistantServiceImpl assistantService;

    /**
     * 初始化被测服务与依赖桩。
     */
    @BeforeEach
    void setUp() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setProvider("dashscope");
        agentProperties.setApiKey("test-key");
        agentProperties.setStreamTimeoutSeconds(60L);

        // 会话锁可获取：Redisson 锁桩返回抢占成功，释放时按持有者标识解锁。
        RLock sessionLock = acquiredLock();
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getLock(anyString())).thenReturn(sessionLock);

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();

        agentFactory = mock(AgentFactory.class);
        assistantMessageService = mock(AssistantMessageService.class);
        assistantService = new AssistantServiceImpl();
        ReflectionTestUtils.setField(assistantService, "agentFactory", agentFactory);
        ReflectionTestUtils.setField(assistantService, "agentProperties", agentProperties);
        ReflectionTestUtils.setField(assistantService, "assistantMessageService", assistantMessageService);
        ReflectionTestUtils.setField(assistantService, "chatSessionService", mock(ChatSessionService.class));
        ReflectionTestUtils.setField(assistantService, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(assistantService, "sseTaskScheduler", scheduler);
        ReflectionTestUtils.setField(assistantService, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(assistantService, "resumeDiagnosisService", mock(ResumeDiagnosisService.class));
        // F5：助手会话不是面试会话，面试流程服务返回 null，本轮仍走原对话链路。
        ReflectionTestUtils.setField(assistantService, "interviewFlowService", mock(InterviewFlowService.class));

        LoginUserHolder.set(new LoginUser(USER_ID, "alice", "jti-1"));
    }

    /**
     * 清理线程绑定的登录态。
     */
    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    /**
     * 验证推送在流中途失败后，本轮继续跑完并把完整回复落库。
     */
    @Test
    void shouldFinishRoundAndPersistFullReplyWhenPushFails() {
        // 推送封装：meta 正常，第一次事件推送成功后开始失败，模拟客户端断开的连接。
        SseEmitterSupport support = mock(SseEmitterSupport.class);
        when(support.sendMeta(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        when(support.send(any())).thenReturn(true, false);
        AssistantServiceImpl spiedService = spy(assistantService);
        org.mockito.Mockito.doReturn(support).when(spiedService).createEmitterSupport();

        HarnessAgent agent = mock(HarnessAgent.class);
        when(agentFactory.getAgent(anyString())).thenReturn(agent);
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(twoChunkStream());

        AssistantChatReqVO reqVO = new AssistantChatReqVO();
        reqVO.setSessionId(SESSION_ID);
        reqVO.setContent("帮我诊断简历");

        spiedService.chat(reqVO);

        // 断开后模型继续跑完：落库的是完整回复，而不是断开时的半截内容。
        verify(assistantMessageService, org.mockito.Mockito.timeout(TimeUnit.SECONDS.toMillis(5)))
                .saveMessage(eq(USER_ID), eq(Long.valueOf(SESSION_ID)),
                        eq(MessageRoleEnum.ASSISTANT), eq("第一段第二段第三段"));
        // 连接已断：不再向前端推送结束事件。
        verify(support, never()).sendDone();
    }

    /**
     * 构造一把「抢占成功」的 Redisson 锁桩。
     *
     * @return 锁桩
     */
    private static RLock acquiredLock() {
        RLock lock = mock(RLock.class);
        when(lock.tryLockAsync(anyLong())).thenReturn(completedFuture(Boolean.TRUE));
        when(lock.unlockAsync(anyLong())).thenReturn(completedFuture(null));
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
     * 构造三段文本的事件流（有间隔，便于验证推送失败后上游不会被取消）。
     *
     * @return 事件流
     */
    private Flux<AgentEvent> twoChunkStream() {
        return Flux.<AgentEvent>just(
                new TextBlockDeltaEvent("reply-1", "block-1", "第一段"),
                new TextBlockDeltaEvent("reply-1", "block-2", "第二段"),
                new TextBlockDeltaEvent("reply-1", "block-3", "第三段"))
                .delayElements(java.time.Duration.ofMillis(CHUNK_INTERVAL_MILLIS));
    }
}
