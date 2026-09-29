package com.wxy.career.service.impl;

import com.wxy.career.config.AgentProperties;
import com.wxy.career.service.AgentFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示词的契约测试。
 *
 * <p>提示词是 Markdown 文本，改错内容或删掉关键约束都不会导致编译失败，因此这里用测试把三类要求固化下来：
 * 一是每个 Agent 都能读到自己的文件并原样生效（防止路径写错导致线上静默走兜底提示词、或子 Agent 读到
 * 助手的提示词），二是提示词始终包含语气、依据、工具与边界四组核心规则，三是文件缺失时按 Agent
 * 退回各自的兜底提示词，对话不至于失去约束。
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
            "不要先反问",
            "不写「接下来我将」「已加载」「已读取」",
            "不提到技能名、工具名或子 Agent",
            "不编造",
            "如实说明没有查到",
            "超出求职范围");

    /**
     * 提示词文件位置，与 {@code application.yml} 中 {@code app.agent.prompt-location} 保持一致。
     */
    private static final String PROMPT_LOCATION = "classpath:prompts/assistant.md";

    /**
     * 简历分析子 Agent 提示词文件位置。
     */
    private static final String SUB_AGENT_PROMPT_LOCATION = "classpath:prompts/sub-resume-analyst.md";

    /**
     * 子 Agent 提示词必须包含的关键约定：真实工具名、分段读取、结构化提交。
     */
    private static final List<String> SUB_AGENT_REQUIRED_SNIPPETS = List.of(
            "read_resume",
            "submit_resume_diagnosis",
            "segment",
            "不要先反问",
            "不写「接下来我将」「已加载」「已读取」",
            "不提到技能名、工具名或子 Agent",
            "优化后的简历正文",
            "不写库、不改简历");

    /**
     * 校验提示词文件可读取、内容完整，且能原样返回给 Agent。
     *
     * @throws Exception 读取配置文件失败
     */
    @Test
    @DisplayName("提示词文件可读取并包含核心规则")
    void shouldLoadSystemPromptFromFile() throws Exception {
        String filePrompt = readPromptFile("prompts/assistant.md");

        assertTrue(filePrompt.contains("不好意思"), "工具查不到时应给出「不好意思，暂时没查到」这类温和回应");
        assertTrue(filePrompt.contains("工具返回为空"), "应覆盖工具无结果、报错、无匹配三种情况");
        REQUIRED_RULE_SNIPPETS.forEach(snippet ->
                assertTrue(filePrompt.contains(snippet), "系统提示词缺少规则片段：" + snippet));

        SystemPromptProviderImpl provider = newProvider(PROMPT_LOCATION);
        // 已配置时不能再走兜底分支，否则线上看到的就是另一套规则。
        assertEquals(filePrompt.strip(), provider.prompt(AgentFactory.MAIN_AGENT_NAME),
                "已配置提示词时应原样返回文件内容");
    }

    /**
     * 校验简历分析子 Agent 读到自己的提示词，且与助手的提示词互不串号。
     *
     * @throws Exception 读取配置文件失败
     */
    @Test
    @DisplayName("子 Agent 提示词各取各的且互不串号")
    void shouldLoadSubAgentPromptSeparately() throws Exception {
        String filePrompt = readPromptFile("prompts/sub-resume-analyst.md");
        SUB_AGENT_REQUIRED_SNIPPETS.forEach(snippet ->
                assertTrue(filePrompt.contains(snippet), "子 Agent 提示词缺少约定片段：" + snippet));

        SystemPromptProviderImpl provider = newProvider(PROMPT_LOCATION);
        String assistantPrompt = provider.prompt(AgentFactory.MAIN_AGENT_NAME);
        String subAgentPrompt = provider.prompt(AgentFactory.RESUME_ANALYST_AGENT_NAME);

        assertEquals(filePrompt.strip(), subAgentPrompt, "子 Agent 应原样读到自己的提示词文件");
        assertTrue(assistantPrompt.contains("你是「求职智能助手」"), "助手提示词不能被子 Agent 覆盖");
        assertTrue(subAgentPrompt.contains("简历分析专家"), "子 Agent 提示词应是简历分析角色");
        assertFalse(subAgentPrompt.contains("你是「求职智能助手」"), "子 Agent 不能读到助手的提示词");
    }

    /**
     * 校验提示词文件缺失或位置未配置时，兜底提示词与文件版遵循同一套规则。
     */
    @Test
    @DisplayName("提示词文件缺失时兜底提示词包含同样的核心规则")
    void shouldFallbackWhenPromptFileMissing() {
        assertFallbackContainsRequiredRules(newProvider("classpath:prompts/not-exists.md"),
                AgentFactory.MAIN_AGENT_NAME);
        assertFallbackContainsRequiredRules(newProvider(" "), AgentFactory.MAIN_AGENT_NAME);
        // 子 Agent 的兜底提示词也必须守住自己的底线（不许补经历、不许写库），不能用助手那套角色设定。
        SystemPromptProviderImpl provider = newProvider("classpath:prompts/not-exists.md");
        SUB_AGENT_REQUIRED_SNIPPETS.forEach(snippet ->
                assertTrue(provider.prompt(AgentFactory.RESUME_ANALYST_AGENT_NAME).contains(snippet),
                        "子 Agent 兜底提示词缺少片段：" + snippet));
    }

    /**
     * 断言兜底提示词包含全部核心规则片段。
     *
     * @param provider 提示词提供者
     * @param agentId Agent 标识
     */
    private void assertFallbackContainsRequiredRules(SystemPromptProviderImpl provider, String agentId) {
        String prompt = provider.prompt(agentId);
        assertNotNull(prompt, "兜底提示词不能为空");
        REQUIRED_RULE_SNIPPETS.forEach(snippet ->
                assertTrue(prompt.contains(snippet), "兜底提示词缺少规则片段：" + snippet));
    }

    /**
     * 校验面试 Agent 与评分子 Agent 各读各的提示词，缺文件时各自的兜底也不会串到别的角色。
     *
     * <p>面试提示词是本模块「一轮一题、追问最多一层、答错就换题」的行为契约；评分提示词必须写明以
     * {@code answer-evaluation} 技能为准。文件位置写错时，线上会静默走兜底，因此这两条一起固定。
     *
     * @throws Exception 读取配置文件失败
     */
    @Test
    @DisplayName("面试与评分提示词各取各的且兜底不串角色")
    void shouldLoadInterviewPromptsSeparately() throws Exception {
        SystemPromptProviderImpl provider = newProvider(PROMPT_LOCATION);
        String interviewerPrompt = provider.prompt(AgentFactory.INTERVIEWER_AGENT_NAME);
        String evaluatorPrompt = provider.prompt(AgentFactory.ANSWER_EVALUATOR_AGENT_NAME);

        assertEquals(readPromptFile("prompts/interviewer.md").strip(), interviewerPrompt,
                "面试 Agent 应原样读到自己的提示词文件");
        assertEquals(readPromptFile("prompts/sub-evaluator.md").strip(), evaluatorPrompt,
                "评分子 Agent 应原样读到自己的提示词文件");
        assertTrue(interviewerPrompt.contains("get_interview_state"), "面试提示词要写明读状态工具");
        assertTrue(interviewerPrompt.contains("record_interview_answer"), "面试提示词要写明记录工具");
        assertTrue(interviewerPrompt.contains("interview-questioning"), "出题规则必须走技能，不在提示词里重写");
        assertTrue(interviewerPrompt.contains("不要再围绕刚才的知识点"), "错题不纠缠要写进提示词");
        assertTrue(evaluatorPrompt.contains("answer-evaluation"), "评分口径以技能为准");
        assertTrue(evaluatorPrompt.contains("WRONG"), "评分提示词要写明三档判定");

        SystemPromptProviderImpl fallbackProvider = newProvider("classpath:prompts/not-exists.md");
        String fallbackInterviewer = fallbackProvider.prompt(AgentFactory.INTERVIEWER_AGENT_NAME);
        assertTrue(fallbackInterviewer.contains("面试官"), "缺文件时面试 Agent 应退回面试官兜底提示词");
        assertFalse(fallbackInterviewer.contains("求职智能助手"), "面试兜底不能变成助手角色");
        assertFalse(fallbackInterviewer.contains("简历分析专家"), "面试兜底不能变成简历分析角色");
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
     * @param path 相对 classpath 的文件路径
     * @return 系统提示词
     * @throws Exception 配置文件读取失败
     */
    private String readPromptFile(String path) throws Exception {
        return new String(
                new ClassPathResource(path).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
