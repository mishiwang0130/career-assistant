package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.middleware.MetricsMiddleware;
import com.wxy.career.middleware.SystemPromptMiddleware;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.SystemPromptProvider;
import com.wxy.career.util.GetCurrentUserTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 构建工厂实现。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Service
public class AgentFactoryImpl implements AgentFactory {

    /**
     * 主 Agent 描述。
     */
    private static final String MAIN_AGENT_DESCRIPTION = "求职智能助手主 Agent";

    /**
     * Agent 实例缓存，按 Agent 名缓存，会话隔离由运行时上下文与共享会话存储负责。
     */
    private final Map<String, ReActAgent> agentCache = new ConcurrentHashMap<>();

    /**
     * 对话模型。
     */
    @Resource
    private Model agentModel;

    /**
     * 会话状态存储。
     */
    @Resource
    private AgentStateStore agentStateStore;

    /**
     * Agent 配置。
     */
    @Resource
    private AgentProperties agentProperties;

    /**
     * 系统提示词提供者。
     */
    @Resource
    private SystemPromptProvider systemPromptProvider;

    /**
     * 系统提示词中间件。
     */
    @Resource
    private SystemPromptMiddleware systemPromptMiddleware;

    /**
     * 埋点中间件。
     */
    @Resource
    private MetricsMiddleware metricsMiddleware;

    /**
     * 当前用户查询工具。
     */
    @Resource
    private GetCurrentUserTool getCurrentUserTool;

    /**
     * 按名字获取 Agent。
     *
     * @param agentName Agent 名
     * @return Agent 实例
     */
    @Override
    public ReActAgent getAgent(String agentName) {
        if (!StringUtils.hasText(agentName)) {
            throw new BizException(ErrorConstant.PARAM_ERROR, HttpStatus.BAD_REQUEST);
        }
        if (!MAIN_AGENT_NAME.equals(agentName)) {
            throw new BizException(ErrorConstant.NOT_FOUND, HttpStatus.NOT_FOUND);
        }
        return agentCache.computeIfAbsent(agentName, this::buildAgent);
    }

    /**
     * 清空指定会话的 Agent 上下文与持久化状态。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    @Override
    public void clearSession(Long userId, String sessionId) {
        if (userId == null || !StringUtils.hasText(sessionId)) {
            return;
        }
        String userKey = String.valueOf(userId);
        try {
            ReActAgent agent = agentCache.get(MAIN_AGENT_NAME);
            if (agent != null) {
                agent.clearContext(userKey, sessionId);
            }
            // 即使 Agent 尚未创建，也要清掉可能残留的会话状态。
            agentStateStore.delete(userKey, sessionId);
        } catch (Exception exception) {
            // 会话状态清理失败不影响消息表清空，记录日志便于排查。
            log.warn("清理 Agent 会话状态失败，userId={}，sessionId={}", userId, sessionId, exception);
        }
    }

    /**
     * 构建 Agent。
     *
     * @param agentName Agent 名
     * @return Agent 实例
     */
    private ReActAgent buildAgent(String agentName) {
        Toolkit toolkit = new Toolkit();
        // 每个 Agent 注册自己的工具白名单，不做全局共享。
        toolkit.registerAgentTool(getCurrentUserTool);
        return ReActAgent.builder()
                .name(agentName)
                .description(MAIN_AGENT_DESCRIPTION)
                .sysPrompt(systemPromptProvider.currentPrompt())
                .model(agentModel)
                .toolkit(toolkit)
                .maxIters(agentProperties.getMaxIters())
                .middlewares(List.of(systemPromptMiddleware, metricsMiddleware))
                .stateStore(agentStateStore)
                .build();
    }
}
