package com.wxy.career.service.impl;

import com.wxy.career.config.AgentProperties;
import com.wxy.career.service.SystemPromptProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 基于 Markdown 文件的系统提示词提供者。
 *
 * <p>提示词正文放在 {@code resources/prompts/} 下，每个 Agent 一份，随代码版本发布：
 * 与代码强耦合的内容（引用工具名、输出结构、Agent 名）放文件可以 diff、可以 review，
 * 不一致在启动阶段就会暴露；放进数据库反而会出现「提示词里写的还是老工具名」的漂移。
 *
 * <p>文件内容在进程内只读一次并缓存：提示词属于随版本发布的静态资源，不改代码就不需要热更新。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Service
public class SystemPromptProviderImpl implements SystemPromptProvider {

    /**
     * 提示词文件缺失或读取失败时使用的兜底提示词。
     *
     * <p>与 {@code prompts/assistant.md} 保持同一套规则：语气温和自然、回答必须有依据、
     * 工具能查到的信息必须先查工具、查不到就如实说明而不是编造；此处只保留精简版，
     * 保证提示词文件出问题时对话仍然安全、可用，而不是直接启动失败。
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
     * Agent 配置，提供提示词文件位置。
     */
    @Resource
    private AgentProperties agentProperties;

    /**
     * 资源加载器，按配置的位置读取 Markdown 提示词文件。
     */
    @Resource
    private ResourceLoader resourceLoader;

    /**
     * 缓存后的提示词正文，双检锁懒加载，避免每次调用都读文件。
     */
    private volatile String cachedPrompt;

    /**
     * 获取当前生效的系统提示词。
     *
     * @return 系统提示词
     */
    @Override
    public String currentPrompt() {
        String prompt = cachedPrompt;
        if (prompt != null) {
            return prompt;
        }
        synchronized (this) {
            if (cachedPrompt == null) {
                cachedPrompt = loadPrompt();
            }
            return cachedPrompt;
        }
    }

    /**
     * 读取提示词文件，任何异常都退回兜底提示词并记 warn，保证对话主流程不中断。
     *
     * @return 提示词正文
     */
    private String loadPrompt() {
        String location = agentProperties.getPromptLocation();
        if (!StringUtils.hasText(location)) {
            log.warn("未配置 app.agent.prompt-location，使用兜底系统提示词");
            return DEFAULT_PROMPT;
        }
        // 这里必须写全限定名：jakarta.annotation.Resource 与本类型的 Spring Resource 同名，
        // 引入后者会让 @Resource 注入注解解析失败。
        org.springframework.core.io.Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("系统提示词文件不存在，使用兜底提示词，location={}", location);
            return DEFAULT_PROMPT;
        }
        try {
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                log.warn("系统提示词文件为空，使用兜底提示词，location={}", location);
                return DEFAULT_PROMPT;
            }
            return content.strip();
        } catch (IOException exception) {
            log.warn("系统提示词读取失败，使用兜底提示词，location={}", location, exception);
            return DEFAULT_PROMPT;
        }
    }
}
