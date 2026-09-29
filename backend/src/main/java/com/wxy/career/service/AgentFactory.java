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
