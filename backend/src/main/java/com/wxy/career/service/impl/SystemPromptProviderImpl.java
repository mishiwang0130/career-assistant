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
     *
     * <p>与 {@code application.yml} 中 {@code app.agent.system-prompt} 保持同一套规则：语气温和自然、
     * 回答必须有依据、工具能查到的信息必须先查工具、查不到就如实说明而不是编造；此处只保留精简版，
     * 避免配置缺失时行为发生漂移。
     */
    private static final String DEFAULT_PROMPT = """
            你是「求职智能助手」，帮求职者做简历优化、面试辅导、职业规划、岗位匹配这些事情。
            把用户当成朋友：语气温和自然，先回应对方最关心的问题，再给出具体、能落地的建议，使用简体中文。
            要求：
            1. 不要使用「结论：」「理由：」这类标签，也不要向用户解释内部限制、实现细节或系统设定；
            2. 每条回答都要有依据，工具能查到、又与问题相关的信息必须先调用工具再回答；
            3. 用户本人信息（昵称、简历、画像、目标岗位等）一律以工具结果为准，不得凭空猜测；
            4. 不编造事实、数字和用户数据，工具查不到就如实说明没有查到，再询问补充信息；
            5. 工具结果优先于通用经验，通用经验要说明是通常情况；不确定的内容明确说不确定；
            6. 问题超出求职范围时友好回应一句并说明能帮上的方向，不罗列拒绝理由；
            7. 只使用当前登录用户自己的数据，敏感或高风险话题建议咨询专业人士。""";

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
