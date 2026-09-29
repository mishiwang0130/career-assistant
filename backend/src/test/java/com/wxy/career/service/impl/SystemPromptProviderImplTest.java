package com.wxy.career.service.impl;

import com.wxy.career.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示词的契约测试。
 *
 * <p>提示词是 Markdown 文本，改错内容或删掉关键约束都不会导致编译失败，因此这里用测试把三类要求固化下来：
 * 一是 {@code prompts/assistant.md} 能被读取并原样生效（防止路径写错导致线上静默走兜底提示词），
 * 二是提示词始终包含语气、依据、工具与边界四组核心规则（防止后续调整把约束改回去），
 * 三是提示词文件缺失时不至于让对话失去约束（兜底提示词必须守住同一套规则）。
 *
 * @author wxy
 * @date 2026-09-28
 */
class SystemPromptProviderImplTest {

    /**
     * 两个提示词版本都必须包含的核心规则片段。
     */
    private static final List<String> REQUIRED_RULE_SNIPPETS = List.of(
            "「理由：」",
            "必须先调用工具",
            "系统会在本提示词末尾给出当前用户的昵称与求职目标",
            "不编造",
            "如实说明没有查到",
            "超出求职范围");

    /**
     * 提示词文件位置，与 {@code application.yml} 中 {@code app.agent.prompt-location} 保持一致。
     */
    private static final String PROMPT_LOCATION = "classpath:prompts/assistant.md";

    /**
     * 校验提示词文件可读取、内容完整，且能原样返回给 Agent。
     *
     * @throws Exception 读取配置文件失败
     */
    @Test
    @DisplayName("提示词文件可读取并包含核心规则")
    void shouldLoadSystemPromptFromFile() throws Exception {
        String filePrompt = readPromptFile();

        assertTrue(filePrompt.contains("不好意思"), "工具查不到时应给出「不好意思，暂时没查到」这类温和回应");
        assertTrue(filePrompt.contains("工具返回为空"), "应覆盖工具无结果、报错、无匹配三种情况");
        REQUIRED_RULE_SNIPPETS.forEach(snippet ->
                assertTrue(filePrompt.contains(snippet), "系统提示词缺少规则片段：" + snippet));

        SystemPromptProviderImpl provider = newProvider(PROMPT_LOCATION);
        // 已配置时不能再走兜底分支，否则线上看到的就是另一套规则。
        assertEquals(filePrompt.strip(), provider.currentPrompt(), "已配置提示词时应原样返回文件内容");
    }

    /**
     * 校验提示词文件缺失或位置未配置时，兜底提示词与文件版遵循同一套规则。
     */
    @Test
    @DisplayName("提示词文件缺失时兜底提示词包含同样的核心规则")
    void shouldFallbackWhenPromptFileMissing() {
        assertFallbackContainsRequiredRules(newProvider("classpath:prompts/not-exists.md"));
        assertFallbackContainsRequiredRules(newProvider(" "));
    }

    /**
     * 断言兜底提示词包含全部核心规则片段。
     *
     * @param provider 提示词提供者
     */
    private void assertFallbackContainsRequiredRules(SystemPromptProviderImpl provider) {
        String prompt = provider.currentPrompt();
        assertNotNull(prompt, "兜底提示词不能为空");
        REQUIRED_RULE_SNIPPETS.forEach(snippet ->
                assertTrue(prompt.contains(snippet), "兜底提示词缺少规则片段：" + snippet));
    }

    /**
     * 构造只注入必要依赖的提示词提供者。
     *
     * @param location 提示词文件位置
     * @return 提示词提供者
     */
    private SystemPromptProviderImpl newProvider(String location) {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setPromptLocation(location);
        SystemPromptProviderImpl provider = new SystemPromptProviderImpl();
        ReflectionTestUtils.setField(provider, "agentProperties", agentProperties);
        ReflectionTestUtils.setField(provider, "resourceLoader", new DefaultResourceLoader());
        return provider;
    }

    /**
     * 从 classpath 读取提示词文件。
     *
     * @return 系统提示词
     * @throws Exception 配置文件读取失败
     */
    private String readPromptFile() throws Exception {
        return new String(
                new ClassPathResource("prompts/assistant.md").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
