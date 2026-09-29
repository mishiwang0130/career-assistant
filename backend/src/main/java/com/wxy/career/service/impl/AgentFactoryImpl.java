package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.common.result.ErrorConstant;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.middleware.MetricsMiddleware;
import com.wxy.career.middleware.SystemPromptMiddleware;
import com.wxy.career.service.AgentFactory;
import com.wxy.career.service.SystemPromptProvider;
import com.wxy.career.tool.ReadResumeTool;
import com.wxy.career.tool.SubmitResumeDiagnosisTool;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.tools.ToolsConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
     * 简历分析子 Agent 描述，决定助手在什么场景下把它派出去。
     */
    private static final String RESUME_ANALYST_DESCRIPTION =
            "简历分析专家：读取用户简历正文并给出综合得分、多维度评分、问题清单、亮点、优化建议、"
                    + "优化后的简历正文与可能被追问的项目点。用户要诊断简历时派给它。";

    /**
     * 简历分析子 Agent 的步数上限：读分段正文 + 诊断 + 提交结构化结论，比主对话需要更多步。
     */
    private static final int RESUME_ANALYST_MAX_ITERS = 16;

    /**
     * 读简历工具名。
     */
    private static final String READ_RESUME_TOOL_NAME = "read_resume";

    /**
     * 提交诊断结论工具名。只给子 Agent 用，助手侧靠 allow 白名单挡住。
     */
    private static final String SUBMIT_DIAGNOSIS_TOOL_NAME = "submit_resume_diagnosis";

    /**
     * 框架的「派发子 Agent」工具名：助手靠它把一次性分析任务交给子 Agent。
     */
    private static final String SUBAGENT_SPAWN_TOOL_NAME = "agent_spawn";

    /**
     * 框架的「给子 Agent 追加消息」工具名。
     */
    private static final String SUBAGENT_SEND_TOOL_NAME = "agent_send";

    /**
     * 框架的「列出子 Agent」工具名。
     */
    private static final String SUBAGENT_LIST_TOOL_NAME = "agent_list";

    /**
     * 简历分析子 Agent 的工具白名单：只读简历 + 提交结构化诊断结论。
     *
     * <p>两个工具都是只读语义：不写库、不改简历、不创建新简历。提交工具存在的唯一原因是：框架只把
     * 子 Agent 的最终文本回流给助手，文本无法稳定地转成前端渲染评分卡所需的结构化对象，因此让子 Agent
     * 在给出文字结论的同时用带 schema 的工具参数再填一份结构化副本。
     */
    private static final List<String> RESUME_ANALYST_TOOL_NAMES =
            List.of(READ_RESUME_TOOL_NAME, SUBMIT_DIAGNOSIS_TOOL_NAME);

    /**
     * 框架的技能加载工具名。子 Agent 声明了 resume-analysis，模型要靠它取技能正文，因此必须保留。
     */
    private static final String SKILL_LOAD_TOOL_NAME = "load_skill_through_path";

    /**
     * 子 Agent 最终允许保留的工具集合：两个只读业务工具 + 技能加载工具。
     *
     * <p>框架给声明式子 Agent 也自动注册了平台工具（联网检索、抓取、异步等待），而子 Agent 不走
     * 父 Agent 的 {@code ToolsConfig}，因此必须在子 Agent 实例创建后按本白名单逐个移除。
     */
    private static final List<String> RESUME_ANALYST_ALLOWED_TOOL_NAMES =
            List.of(READ_RESUME_TOOL_NAME, SUBMIT_DIAGNOSIS_TOOL_NAME, SKILL_LOAD_TOOL_NAME);

    /**
     * 简历分析子 Agent 加载的技能名，对应 MySQL 技能仓库里的 resume-analysis。
     */
    private static final String RESUME_ANALYSIS_SKILL_NAME = "resume-analysis";

    /**
     * 助手 Agent 可见的工具白名单。
     *
     * <p>助手自己只会用到读简历（转发给子 Agent 之前的定位），派发子 Agent 的三个工具由框架注入；
     * 提交诊断结论只属于子 Agent，不在这里，模型侧看不到它。
     */
    private static final List<String> ASSISTANT_ALLOWED_TOOL_NAMES = List.of(
            READ_RESUME_TOOL_NAME,
            SUBAGENT_SPAWN_TOOL_NAME,
            SUBAGENT_SEND_TOOL_NAME,
            SUBAGENT_LIST_TOOL_NAME);

    /**
     * 需要显式 deny 的框架平台工具。
     *
     * <p>HarnessAgent 默认会注册若干平台工具（异步结果等待、联网检索与抓取、后台任务查询），
     * 而 ToolsConfig 的 allow 白名单对平台工具不生效——ToolFilter 只会通过 deny 移除它们，
     * 因此这里逐个显式禁用；后续启用新的框架能力时，若该能力会注册平台工具，需要把工具名同步补进本列表。
     * 本批只需要子 Agent 的同步派发，后台任务与异步等待一概不开。
     */
    private static final List<String> DENIED_PLATFORM_TOOL_NAMES = List.of(
            "wait_async_results", "web_search", "web_fetch", "task_output", "task_list", "task_cancel");

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
     * Skill（业务规则）仓库，F2 起用于给子 Agent 加载 resume-analysis。
     */
    @Resource
    private AgentSkillRepository agentSkillRepository;

    /**
     * 读简历工具，助手与子 Agent 共用的只读工具。
     */
    @Resource
    private ReadResumeTool readResumeTool;

    /**
     * 提交诊断结论工具，只给简历分析子 Agent 使用。
     */
    @Resource
    private SubmitResumeDiagnosisTool submitResumeDiagnosisTool;

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
     * <p>本批打开两项框架能力：子 Agent 声明与派发（助手的「简历分析」）、Skill 存 MySQL。
     * 文件读写、Shell、工作区上下文、会话转录、记忆工具与记忆钩子仍然关闭：向模型暴露本机文件系统
     * 属于纯风险，记忆走 Mem0（第 4 批）、Plan Mode 与定时任务走第 5 批。
     *
     * @param agentName Agent 名
     * @return Agent 实例
     */
    private HarnessAgent buildAgent(String agentName) {
        // 业务工具在工厂里集中注册：读简历助手与子 Agent 共用，提交诊断结论只给子 Agent 用。
        // 用户背景（昵称、求职目标）仍由 SystemPromptMiddleware 注入提示词，不注册业务工具。
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(readResumeTool);
        toolkit.registerTool(submitResumeDiagnosisTool);
        HarnessAgent agent = HarnessAgent.builder()
                .name(agentName)
                .description(MAIN_AGENT_DESCRIPTION)
                .sysPrompt(systemPromptProvider.prompt(agentName))
                .model(agentModel)
                .generateOptions(GenerateOptions.builder().temperature(0.6).build())
                .toolkit(toolkit)
                .maxIters(agentProperties.getMaxIters())
                .middlewares(List.of(systemPromptMiddleware, metricsMiddleware))
                .stateStore(agentStateStore)
                // 技能正文存 MySQL；子 Agent 用声明（下面一行）而不是工作区里的 subagents/*.md。
                .skillRepository(agentSkillRepository)
                .subagent(buildResumeAnalystDeclaration())
                .toolsConfig(buildToolsConfig())
                .disableFilesystemTools()
                .disableShellTool()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .disableTranscript()
                .disableMemoryTools()
                .disableMemoryHooks()
                .build();
        // 子 Agent 不走父 Agent 的工具白名单，这里按本模块的白名单再收紧一次。
        narrowSubagentTools(agent);
        // 构建后打印实际工具集：ToolFilter 会按 allow/deny 直接改写 Toolkit，因此这里就是模型真正看到的集合。
        log.info("构建 Agent 完成，agentName={}，tools={}，skills={}",
                agentName, agent.getToolkit().getToolNames(), agent.getSkillRepositories().size());
        return agent;
    }

    /**
     * 收紧子 Agent 的工具集。
     *
     * <p>框架给声明式子 Agent 自动注册了平台工具（联网检索、抓取、异步等待），而子 Agent 不套用父 Agent
     * 的 {@code ToolsConfig}，所以要包一层工厂：子 Agent 实例创建出来后按白名单移除多余工具。
     * 包装的是原工厂，因此子 Agent 的会话隔离、状态存储仍然由框架负责。
     *
     * @param agent 已构建的助手 Agent
     */
    private void narrowSubagentTools(HarnessAgent agent) {
        DefaultAgentManager agentManager = agent.getSubagentAgentManager();
        if (agentManager == null) {
            log.warn("当前 Agent 没有子 Agent 管理器，跳过子 Agent 工具白名单收紧");
            return;
        }
        Map<String, SubagentFactory> factories = agentManager.getAgentFactories();
        if (!factories.containsKey(AgentFactory.RESUME_ANALYST_AGENT_NAME)) {
            log.warn("未找到简历分析子 Agent 工厂，子 Agent 工具白名单未收紧，factories={}", factories.keySet());
            return;
        }
        List<SubagentEntry> entries = new ArrayList<>(factories.size());
        for (Map.Entry<String, SubagentFactory> factoryEntry : factories.entrySet()) {
            String subagentName = factoryEntry.getKey();
            SubagentFactory factory = factoryEntry.getValue();
            SubagentDeclaration declaration = agentManager.getDeclaration(subagentName).orElse(null);
            if (AgentFactory.RESUME_ANALYST_AGENT_NAME.equals(subagentName)) {
                SubagentFactory narrowed = runtimeContext -> keepAllowedTools(factory.create(runtimeContext));
                entries.add(new SubagentEntry(
                        subagentName, RESUME_ANALYST_DESCRIPTION, narrowed, declaration));
            } else {
                entries.add(new SubagentEntry(
                        subagentName,
                        declaration == null ? subagentName : declaration.getDescription(),
                        factory,
                        declaration));
            }
        }
        agentManager.replaceAgents(entries);
    }

    /**
     * 按白名单移除子 Agent 上多余的框架默认工具。
     *
     * @param subagent 框架创建出来的子 Agent
     * @return 原样返回收紧后的子 Agent
     */
    private Agent keepAllowedTools(Agent subagent) {
        Toolkit toolkit = subagent.getToolkit();
        for (String toolName : List.copyOf(toolkit.getToolNames())) {
            if (!RESUME_ANALYST_ALLOWED_TOOL_NAMES.contains(toolName)) {
                toolkit.removeTool(toolName);
                log.debug("移除子 Agent 的框架默认工具，agentName={}，tool={}", subagent.getName(), toolName);
            }
        }
        return subagent;
    }

    /**
     * 构建「简历分析」子 Agent 声明。
     *
     * <p>子 Agent 由代码声明，不用工作区 {@code subagents/*.md}：本项目是多用户网页应用，不启用
     * 工作区文件；子 Agent 自己不再往下派，用户身份只从 RuntimeContext 取。
     *
     * @return 子 Agent 声明
     */
    SubagentDeclaration buildResumeAnalystDeclaration() {
        return SubagentDeclaration.builder()
                .name(AgentFactory.RESUME_ANALYST_AGENT_NAME)
                .description(RESUME_ANALYST_DESCRIPTION)
                .tools(RESUME_ANALYST_TOOL_NAMES)
                .skills(List.of(RESUME_ANALYSIS_SKILL_NAME))
                .maxIters(RESUME_ANALYST_MAX_ITERS)
                // 子 Agent 不是入口：结论回到当前对话继续用，不暴露给用户直接对话。
                .exposeToUser(false)
                .build();
    }

    /**
     * 构建工具白名单配置。
     *
     * <p>allow 显式列出模型可见的工具：业务工具与框架注入的子 Agent 派发工具都在这里，未列出的
     * （例如只属于子 Agent 的提交诊断结论工具）不会被暴露；deny 用于剔除框架自动注册、且不受 allow
     * 约束的平台工具。
     *
     * @return 工具白名单配置
     */
    private ToolsConfig buildToolsConfig() {
        ToolsConfig toolsConfig = new ToolsConfig();
        toolsConfig.setAllow(ASSISTANT_ALLOWED_TOOL_NAMES);
        toolsConfig.setDeny(DENIED_PLATFORM_TOOL_NAMES);
        return toolsConfig;
    }
}
