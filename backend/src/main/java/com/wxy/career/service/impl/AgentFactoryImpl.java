package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.middleware.MetricsMiddleware;
import com.wxy.career.middleware.SystemPromptMiddleware;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.SystemPromptProvider;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
     * 需要显式 deny 的框架平台工具。
     *
     * <p>本 Agent 不向模型暴露任何工具：用户背景（昵称、求职目标）由系统提示词注入。但 HarnessAgent
     * 默认会注册若干平台工具（异步结果等待、联网检索与抓取），而 ToolsConfig 的 allow 白名单对平台工具
     * 不生效——ToolFilter 只会通过 deny 移除它们，因此这里逐个显式禁用；后续启用新的框架能力时，
     * 若该能力会注册平台工具，需要把工具名同步补进本列表。
     */
    private static final List<String> DENIED_PLATFORM_TOOL_NAMES =
            List.of("wait_async_results", "web_search", "web_fetch");

    /**
     * Agent 实例缓存，按 Agent 名缓存，会话隔离由运行时上下文与共享会话存储负责。
     */
    private final Map<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

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
     * 按名字获取 Agent。
     *
     * @param agentName Agent 名
     * @return Agent 实例
     */
    @Override
    public HarnessAgent getAgent(String agentName) {
        if (!StringUtils.hasText(agentName)) {
            // 业务异常统一返回 HTTP 200，失败语义由 code 表达。
            throw new BizException(ErrorConstant.PARAM_ERROR);
        }
        if (!MAIN_AGENT_NAME.equals(agentName)) {
            throw new BizException(ErrorConstant.NOT_FOUND);
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
            HarnessAgent agent = agentCache.get(MAIN_AGENT_NAME);
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
     * <p>迁移到 HarnessAgent 的目的不是一次性打开全部框架能力，而是站上框架扩展点：
     * 文件读写、Shell、工作区上下文、Skill、子智能体、会话转录、记忆工具与记忆钩子本模块一律关闭，
     * 向模型暴露本机文件系统属于纯风险；Skill、Memory、Plan Mode、SubAgent 分别由
     * M14、M11、M10、M8 认领后再打开并补测试。
     *
     * @param agentName Agent 名
     * @return Agent 实例
     */
    private HarnessAgent buildAgent(String agentName) {
        // 当前不注册任何业务工具：用户背景（昵称、求职目标）由 SystemPromptMiddleware 注入提示词，
        // 档案的修改只由用户在「求职目标」页完成。后续模块新增工具时必须显式写进 ToolsConfig.allow。
        Toolkit toolkit = new Toolkit();
        HarnessAgent agent = HarnessAgent.builder()
                .name(agentName)
                .description(MAIN_AGENT_DESCRIPTION)
                .sysPrompt(systemPromptProvider.currentPrompt())
                .model(agentModel)
                .toolkit(toolkit)
                .maxIters(agentProperties.getMaxIters())
                .middlewares(List.of(systemPromptMiddleware, metricsMiddleware))
                .stateStore(agentStateStore)
                // 空 Toolkit 下只靠 deny 兜底剔除框架平台工具，模型侧不会拿到任何工具。
                .toolsConfig(buildToolsConfig())
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableSubagents()
                .disableTranscript()
                .disableMemoryTools()
                .disableMemoryHooks()
                .build();
        // 构建后打印实际工具集，便于确认没有框架默认工具混入白名单。
        log.info("构建 Agent 完成，agentName={}，tools={}", agentName, agent.getToolkit().getToolNames());
        return agent;
    }

    /**
     * 构建工具白名单配置。
     *
     * <p>当前不注册业务工具，allow 留空（框架把空列表视同未设置）；deny 用于剔除框架自动注册、
     * 且不受 allow 约束的平台工具。后续模块注册工具时必须同时把工具名写进 allow，避免误注册即暴露。
     *
     * @return 工具白名单配置
     */
    private ToolsConfig buildToolsConfig() {
        ToolsConfig toolsConfig = new ToolsConfig();
        toolsConfig.setDeny(DENIED_PLATFORM_TOOL_NAMES);
        return toolsConfig;
    }
}
