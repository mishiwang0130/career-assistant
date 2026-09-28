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
