package com.wxy.career.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 岗位匹配提示词的契约测试。
 *
 * <p>提示词是 Markdown 文本，改错内容或漏掉关键约束都不会编译失败，因此这里把两类要求固化下来：
 * 一是岗位匹配子 Agent 的提示词包含真实工具名、分段续读、五块输出结构与四条对外行为约定；
 * 二是助手提示词写明了把 JD 原文带进派发指令、以及「简历和这个 JD 一起看看」时同时派两个子 Agent。
 *
 * @author wxy
 * @date 2026-09-29
 */
class JobMatchPromptContractTest {

    /**
     * 岗位匹配子 Agent 提示词必须包含的片段。
     */
    private static final List<String> JOB_MATCH_REQUIRED_SNIPPETS = List.of(
            "job-match",
            "read_resume",
            "hasMore",
            "segment",
            "不要先反问",
            "匹配度",
            "维度评分",
            "命中关键词",
            "缺失关键词",
            "差距补齐建议",
            "如果你确实做过",
            "不编造",
            "不通篇诊断简历",
            "不许替用户编造简历里没有的数字",
            "不写「接下来我将」「已加载」「已读取」",
            "不提到技能名、工具名或子 Agent");

    /**
     * 助手提示词里与岗位匹配派发相关的片段。
     */
    private static final List<String> ASSISTANT_REQUIRED_SNIPPETS = List.of(
            "五块内容",
            "JD 原文",
            "timeout_seconds 设为 180",
            "两项分析一次只派一个",
            // 用户实测反馈：助手先自己读一遍简历写一版分析、拿到子 Agent 结论后又复述一版，回答里出现两份。
            "不要自己先用 read_resume 读一遍简历",
            "一份分析结论只讲一遍",
            "不许替用户编造简历里没有的数字");

    /**
     * 校验岗位匹配子 Agent 提示词的内容契约。
     *
     * @throws Exception 读取提示词文件失败
     */
    @Test
    @DisplayName("岗位匹配子 Agent 提示词包含工具、输出结构与行为约定")
    void shouldKeepJobMatchSubAgentPromptContract() throws Exception {
        String prompt = readPromptFile("prompts/sub-job-match.md");

        JOB_MATCH_REQUIRED_SNIPPETS.forEach(snippet ->
                assertTrue(prompt.contains(snippet), "岗位匹配子 Agent 提示词缺少片段：" + snippet));
        // 输出结构是用户可感知的契约：五块内容缺一块，用户就拿不到完整结论。
        assertTrue(prompt.contains("按顺序给出下面五块内容"), "五块输出结构必须写死在提示词里");
        // 简历正文靠分段读，提示词必须要求读完再下结论。
        assertTrue(prompt.contains("直到读完再下结论"), "缺失关键词的判断必须建立在读完整份简历之上");
    }

    /**
     * 校验助手提示词写明了岗位匹配的派发方式。
     *
     * @throws Exception 读取提示词文件失败
     */
    @Test
    @DisplayName("助手提示词写明 JD 原文随派发指令传入且可同时派两个子 Agent")
    void shouldKeepAssistantJobMatchDispatchContract() throws Exception {
        String prompt = readPromptFile("prompts/assistant.md");

        ASSISTANT_REQUIRED_SNIPPETS.forEach(snippet ->
                assertTrue(prompt.contains(snippet), "助手提示词缺少岗位匹配派发约定：" + snippet));
    }

    /**
     * 从 classpath 读取提示词文件。
     *
     * @param path 相对 classpath 的文件路径
     * @return 提示词正文
     * @throws Exception 提示词文件读取失败
     */
    private String readPromptFile(String path) throws Exception {
        return new String(
                new ClassPathResource(path).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
