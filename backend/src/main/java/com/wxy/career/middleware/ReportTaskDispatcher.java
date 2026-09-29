package com.wxy.career.middleware;

import com.wxy.career.service.AgentFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台子 Agent 任务派发器。
 *
 * <p>面试报告由后台子 Agent 生成，但**不让模型自己派**：F5 已经实测过子 Agent 正文被上级转述进用户可见
 * 回答的问题，因此面试官的工具白名单里没有 {@code agent_spawn} 系工具。平台在这里直接调用框架的
 * {@link AgentSpawnTool}，用 {@code timeout_seconds = 0} 的**后台模式**派发，不自己起线程池、不自己造任务表。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Slf4j
@Component
public class ReportTaskDispatcher {

    /**
     * 父 Agent 的派发深度，平台派发固定从 0 开始（与模型调用 agent_spawn 的语义一致）。
     */
    private static final int PARENT_SPAWN_DEPTH = 0;

    /**
     * 后台模式的超时参数：0 表示不等待，立即返回任务句柄。
     */
    private static final int BACKGROUND_TIMEOUT_SECONDS = 0;

    /**
     * Agent 工厂提供者，用于取面试官 Agent 的子 Agent 管理器与任务仓库。
     *
     * <p>用 {@link ObjectProvider} 延迟到真正派发时再取实例：报告工具 → 报告服务 → 派发器 → Agent 工厂
     * 是一条环状依赖，字段直接注入工厂会让 Spring 在启动阶段判定循环依赖（Spring Boot 默认禁止）。
     */
    @Resource
    private ObjectProvider<AgentFactory> agentFactoryProvider;

    /**
     * 按 Agent 名缓存框架的派发工具实例，保持框架内部的已派发代理登记一致。
     */
    private final Map<String, AgentSpawnTool> spawnTools = new ConcurrentHashMap<>();

    /**
     * 后台派发一次报告生成任务。
     *
     * @param userId 用户 ID
     * @param sessionId 面试会话 ID
     * @param taskText 派发文本，写明会话 ID 与本次面试的判定、错题与薄弱点材料
     */
    public Mono<String> dispatch(Long userId, String sessionId, String taskText) {
        HarnessAgent interviewer =
                agentFactoryProvider.getObject().getAgent(AgentFactory.INTERVIEWER_AGENT_NAME);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(sessionId)
                .build();
        AgentSpawnTool spawnTool = spawnTools.computeIfAbsent(
                interviewer.getName(),
                name -> new AgentSpawnTool(
                        interviewer.getSubagentAgentManager(),
                        interviewer.getTaskRepository(),
                        PARENT_SPAWN_DEPTH));
        // 返回的是框架的受理结果（timeout_seconds=0 时后台执行）：由调用方决定怎么订阅与记录，
        // 这里不阻塞等待，避免占着对话线程。
        return spawnTool.agentSpawn(
                runtimeContext,
                interviewer.getAgentState(),
                AgentFactory.REPORT_WRITER_AGENT_NAME,
                taskText,
                null,
                BACKGROUND_TIMEOUT_SECONDS,
                Boolean.FALSE);
    }
}
