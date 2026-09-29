package com.wxy.career.service;

import io.agentscope.harness.agent.HarnessAgent;

/**
 * Agent 构建工厂。
 *
 * <p>按名字获取 Agent：每个 Agent 有自己的系统提示词、工具白名单与步数上限，工厂内部缓存实例，
 * 会话隔离由传入的 {@code RuntimeContext(userId, sessionId)} 与共享会话存储保证。
 *
 * @author wxy
 * @date 2026-09-28
 */
public interface AgentFactory {

    /**
     * 主 Agent 名，M2 冻结。
     */
    String MAIN_AGENT_NAME = "career-assistant";

    /**
     * 简历分析子 Agent 名，F2 冻结。
     *
     * <p>子 Agent 由助手按声明派发，不作为独立入口，也不通过 {@link #getAgent(String)} 获取。
     */
    String RESUME_ANALYST_AGENT_NAME = "resume-analyst";

    /**
     * 面试 Agent 名，F5 冻结。
     *
     * <p>会话场景 INTERVIEW 的专属 Agent：一场有状态的长流程面试，有自己的提示词、工具白名单、
     * 评分子 Agent 与上下文压缩配置，与助手 Agent 互不影响。
     */
    String INTERVIEWER_AGENT_NAME = "interviewer";

    /**
     * 评分子 Agent 名，F5 冻结。
     *
     * <p>由面试 Agent 按声明派发，不作为独立入口，也不通过 {@link #getAgent(String)} 获取：
     * 提问与评分必须是两个角色，评分结论回到面试流程里决定追问还是换题。
     */
    String ANSWER_EVALUATOR_AGENT_NAME = "answer-evaluator";

    /**
     * 岗位匹配子 Agent 名，F3 冻结。
     *
     * <p>与简历分析子 Agent 同源：由助手按声明派发，不作为独立入口，也不通过
     * {@link #getAgent(String)} 获取；两个分析子 Agent 互不调用，各自不再往下派。
     */
    String JOB_MATCH_AGENT_NAME = "job-match";

    /**
     * 按名字获取 Agent。
     *
     * @param agentName Agent 名
     * @return Agent 实例
     */
    HarnessAgent getAgent(String agentName);

    /**
     * 清空指定用户指定会话的 Agent 上下文与持久化会话状态。
     *
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     */
    void clearSession(Long userId, String sessionId);
}
