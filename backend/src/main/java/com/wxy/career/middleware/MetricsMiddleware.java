package com.wxy.career.middleware;

import com.wxy.career.common.redis.RedisKeyConstants;
import com.wxy.career.common.redis.RedisUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 埋点中间件。
 *
 * <p>记录 Agent 名、step、Tool 名、耗时与结果状态，并按 Agent 维度在 Redis 中累加计数。
 * 埋点属于旁路能力：Redis 不可用或埋点异常时只打日志，绝不阻断主流程。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Component
public class MetricsMiddleware implements MiddlewareBase {

    /**
     * 中间件顺序，位于提示词中间件之后。
     */
    private static final int MIDDLEWARE_ORDER = 20;

    /**
     * 埋点计数键的过期时间，单位为小时。
     */
    private static final long METRICS_TTL_HOURS = 24L;

    /**
     * 字段缺失时的占位值。
     */
    private static final String UNKNOWN = "-";

    /**
     * Redis 操作工具。
     */
    @Resource
    private RedisUtil redisUtil;

    /**
     * 中间件顺序。
     *
     * @return 顺序值，越小越先执行
     */
    @Override
    public int order() {
        return MIDDLEWARE_ORDER;
    }

    /**
     * 记录一次 Agent 执行的耗时与结果状态。
     *
     * @param agent 当前 Agent
     * @param runtimeContext 运行时上下文
     * @param input Agent 输入
     * @param next 后续处理链
     * @return 事件流
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext runtimeContext, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        long startedAt = System.nanoTime();
        return next.apply(input)
                .doFinally(signalType -> record(agent, runtimeContext, "agent", null, startedAt, signalType.name()));
    }

    /**
     * 记录一次工具调用的耗时与结果状态。
     *
     * @param agent 当前 Agent
     * @param runtimeContext 运行时上下文
     * @param input 工具调用输入
     * @param next 后续处理链
     * @return 事件流
     */
    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext runtimeContext, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        long startedAt = System.nanoTime();
        String toolNames = resolveToolNames(input);
        return next.apply(input)
                .doFinally(signalType -> record(agent, runtimeContext, "acting", toolNames, startedAt, signalType.name()));
    }

    /**
     * 解析本次调用的工具名列表。
     *
     * @param input 工具调用输入
     * @return 以逗号分隔的工具名，解析失败时返回占位值
     */
    private String resolveToolNames(ActingInput input) {
        try {
            if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
                return UNKNOWN;
            }
            return input.toolCalls().stream()
                    .map(ToolUseBlock::getName)
                    .collect(Collectors.joining(","));
        } catch (Exception exception) {
            return UNKNOWN;
        }
    }

    /**
     * 记录埋点，任何异常都降级为日志。
     *
     * @param agent 当前 Agent
     * @param runtimeContext 运行时上下文
     * @param step 执行阶段
     * @param toolName 工具名
     * @param startedAt 开始时间，纳秒
     * @param status 结果状态
     */
    private void record(
            Agent agent, RuntimeContext runtimeContext, String step, String toolName, long startedAt, String status) {
        try {
            String agentName = agent == null ? UNKNOWN : agent.getName();
            String sessionId = runtimeContext == null ? UNKNOWN : runtimeContext.getSessionId();
            String normalizedTool = StringUtils.hasText(toolName) ? toolName : UNKNOWN;
            long costMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("agent metrics agentName={} sessionId={} step={} tool={} costMs={} status={}",
                    agentName, sessionId, step, normalizedTool, costMillis, status);
            String key = RedisKeyConstants.AGENT + "metrics:" + agentName;
            String field = step + ":" + normalizedTool + ":" + status;
            Long current = redisUtil.getHash(key, field, Long.class);
            redisUtil.setHash(key, field, current == null ? 1L : current + 1L);
            redisUtil.expire(key, METRICS_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception exception) {
            log.warn("Agent 埋点记录失败，已降级为日志输出", exception);
        }
    }
}
