package com.wxy.career.service.impl;

import com.wxy.career.config.AgentProperties;
import com.wxy.career.service.SystemPromptProvider;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 基于 YAML 配置的系统提示词提供者。
 *
 * <p>每次调用都重新读取配置对象，配置项变更后无需重启即可生效（Spring 配置刷新场景）。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Service
public class SystemPromptProviderImpl implements SystemPromptProvider {

    /**
     * 未配置系统提示词时使用的兜底提示词。
     */
    private static final String DEFAULT_PROMPT = """
            你是「求职智能助手」的主助手，面向求职者提供简历、面试、职业规划相关的帮助。
            回答要求：使用简体中文；先给结论再给理由；不确定的信息必须说明不确定；
            需要用户资料时调用已注册的工具获取，不要凭空猜测用户信息。""";

    /**
     * Agent 配置。
     */
    @Resource
    private AgentProperties agentProperties;

    /**
     * 获取当前生效的系统提示词。
     *
     * @return 系统提示词
     */
    @Override
    public String currentPrompt() {
        String prompt = agentProperties.getSystemPrompt();
        return prompt == null || prompt.isBlank() ? DEFAULT_PROMPT : prompt;
    }
}
