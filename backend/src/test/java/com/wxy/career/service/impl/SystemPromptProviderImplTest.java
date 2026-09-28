package com.wxy.career.service.impl;

import com.wxy.career.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示词的契约测试。
 *
 * <p>提示词是纯文本配置，改错缩进或删掉关键约束都不会导致编译失败，因此这里用测试把两类要求固化下来：
 * 一是 {@code application.yml} 能正确解析出提示词（防止 YAML 缩进写坏导致线上静默走兜底提示词），
 * 二是提示词始终包含语气、依据、工具与边界四组核心规则（防止后续调整把约束改回去）。
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
            "不编造",
            "如实说明没有查到",
            "超出求职范围");

    /**
     * 校验 application.yml 中的提示词可解析，且能原样返回给 Agent。
     *
     * @throws Exception 读取配置文件失败
     */
    @Test
    @DisplayName("application.yml 的系统提示词可解析并包含核心规则")
    void shouldLoadSystemPromptFromYaml() throws Exception {
        String yamlPrompt = loadYamlSystemPrompt();

        assertTrue(yamlPrompt.contains("不好意思"), "工具查不到时应给出「不好意思，暂时没查到」这类温和回应");
        assertTrue(yamlPrompt.contains("工具返回为空"), "应覆盖工具无结果、报错、无匹配三种情况");
        REQUIRED_RULE_SNIPPETS.forEach(snippet ->
                assertTrue(yamlPrompt.contains(snippet), "系统提示词缺少规则片段：" + snippet));

        AgentProperties agentProperties = new AgentProperties();
        agentProperties.setSystemPrompt(yamlPrompt);
        SystemPromptProviderImpl provider = new SystemPromptProviderImpl();
        ReflectionTestUtils.setField(provider, "agentProperties", agentProperties);
        // 已配置时不能再走兜底分支，否则线上看到的就是另一套规则。
        assertEquals(yamlPrompt, provider.currentPrompt(), "已配置提示词时应原样返回 YAML 中的内容");
    }

    /**
     * 校验未配置提示词时的兜底提示词与 YAML 版遵循同一套规则。
     */
    @Test
    @DisplayName("未配置提示词时兜底提示词包含同样的核心规则")
    void shouldFallbackToDefaultPrompt() {
        SystemPromptProviderImpl provider = new SystemPromptProviderImpl();
        ReflectionTestUtils.setField(provider, "agentProperties", new AgentProperties());

        String prompt = provider.currentPrompt();
        assertNotNull(prompt, "兜底提示词不能为空");
        REQUIRED_RULE_SNIPPETS.forEach(snippet ->
                assertTrue(prompt.contains(snippet), "兜底提示词缺少规则片段：" + snippet));
    }

    /**
     * 从 classpath 的 application.yml 中读取系统提示词。
     *
     * @return 系统提示词
     * @throws Exception 配置文件读取失败
     */
    private String loadYamlSystemPrompt() throws Exception {
        String yamlText = new String(
                new ClassPathResource("application.yml").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        Object parsed = new Yaml().load(yamlText);
        assertInstanceOf(Map.class, parsed, "application.yml 应解析为键值结构");
        Map<?, ?> root = (Map<?, ?>) parsed;
        Map<?, ?> app = (Map<?, ?>) root.get("app");
        assertNotNull(app, "application.yml 缺少 app 配置");
        Map<?, ?> agent = (Map<?, ?>) app.get("agent");
        assertNotNull(agent, "application.yml 缺少 app.agent 配置");
        Object prompt = agent.get("system-prompt");
        assertInstanceOf(String.class, prompt, "app.agent.system-prompt 必须配置为多行字符串");
        assertFalse(((String) prompt).isBlank(), "app.agent.system-prompt 不能为空白");
        return (String) prompt;
    }
}
