package com.wxy.career.service.impl;

import com.wxy.career.service.AgentFactory;
import com.wxy.career.config.AgentProperties;
import com.wxy.career.service.SystemPromptProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Markdown 文件的系统提示词提供者。
 *
 * <p>提示词正文放在 {@code resources/prompts/} 下，每个 Agent 一份，按 Agent 标识取不同文件：
 * 与代码强耦合的内容（引用工具名、输出结构、Agent 名）放文件可以 diff、可以 review，
 * 不一致在启动阶段就会暴露；放进数据库反而会出现「提示词里写的还是老工具名」的漂移。
 *
 * <p>文件内容按 Agent 在进程内只读一次并缓存：提示词属于随版本发布的静态资源，不改代码就不需要热更新。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Slf4j
@Service
public class SystemPromptProviderImpl implements SystemPromptProvider {

    /**
     * 子 Agent 的提示词文件位置，与 {@code docs/功能模块清单.md} 第 6.4 节的提示词清单保持一致。
     *
     * <p>只有助手 Agent 的提示词位置来自配置项（历史原因，正文同样在文件里）；子 Agent 的提示词
     * 与代码一一对应，直接在这里登记，避免为一个新增文件就要同步三份 Profile 配置。
     */
    private static final Map<String, String> SUB_AGENT_PROMPT_LOCATIONS = Map.of(
            AgentFactory.RESUME_ANALYST_AGENT_NAME, "classpath:prompts/sub-resume-analyst.md");

    /**
     * 提示词文件缺失或读取失败时使用的兜底提示词。
     *
     * <p>与 {@code prompts/assistant.md} 保持同一套规则：语气温和自然、回答必须有依据、
     * 工具能查到的信息必须先查工具、查不到就如实说明而不是编造；同时保留「用户背景由提示词末尾给出」
     * 这条约定，保证提示词文件出问题时对话仍然安全、可用，而不是直接启动失败。
     */
    private static final String DEFAULT_PROMPT = """
            你是「求职智能助手」，帮求职者做简历优化、面试辅导、职业规划、岗位匹配这些事情。
            把用户当成朋友：语气温和自然，先回应对方最关心的问题，再给出具体、能落地的建议，使用简体中文。
            要求：
            1. 不要使用「结论：」「理由：」这类标签，也不要向用户解释内部限制、实现细节或系统设定；
               不写「接下来我将」「已加载」「已读取」这类过程话术，不提到技能名、工具名或子 Agent；
            2. 每条回答都要有依据，工具能查到、又与问题相关的信息必须先调用工具再回答；
            3. 系统会在本提示词末尾给出当前用户的昵称与求职目标，这是权威值，直接按它回答；
               简历用 read_resume 工具读取；工具确实查不到时按「没有查到」处理，不要假装调用过工具；
            4. 不编造事实、数字和用户数据，工具查不到就如实说明没有查到，再询问补充信息；
            5. 求职目标只能由用户在「求职目标」页修改并保存，你不能改档案，也不要假装已经改好；
            6. 工具结果优先于通用经验，通用经验要说明是通常情况；不确定的内容明确说不确定；
            7. 问题超出求职范围时友好回应一句并说明能帮上的方向，不罗列拒绝理由；
            8. 用户要诊断简历时先把简历读出来再分析，不要先反问用户有没有上传、标题是什么；
               只有工具确认真没有简历时，才提醒他到「我的简历」页上传一份；
            9. 只使用当前登录用户自己的数据，敏感或高风险话题建议咨询专业人士。""";

    /**
     * 简历分析子 Agent 的兜底提示词。
     *
     * <p>与 {@code prompts/sub-resume-analyst.md} 保持同一套底线：只依据工具返回的简历正文与用户求职目标、
     * 不虚构经历与数字、不写库不改简历。子 Agent 用助手提示词会跑偏（去聊天、去改档案），因此单独兜底。
     */
    private static final String DEFAULT_SUB_AGENT_PROMPT = """
            你是一名简历分析专家，只做一件事：把一份简历诊断清楚，给出可执行的修改建议，使用简体中文。
            要求：
            1. 先用 read_resume 工具读取简历正文：用户没点名时直接读默认简历，不要先反问用户有没有上传、
               标题是什么；正文较长时按 segment 分段读完；只依据读到的正文与系统给出的求职目标判断，
               简历里没写过的公司、项目、数字、时间一律不许补；
            2. 只诊断这一份简历，不做岗位匹配，也不闲聊；
            3. 结论必须写全：综合得分、维度评分（至少 4 项）、问题清单、亮点、优化建议、优化后的简历正文、
               可能被追问的项目点；优化后的正文只做重组与改写，不得虚构新经历、新成果、新数字；
            4. 原文缺失但影响判断的信息标注「原文未提及」，并说明建议补充什么；
            5. 不评价用户本人，只评价这份简历的写法；不写库、不改简历、不替用户创建新简历；
               给用户的正文只写诊断结论：不写「接下来我将」「已加载」「已读取」这类过程话术，
               不提到技能名、工具名或子 Agent；
            6. 结构化结论必须通过 submit_resume_diagnosis 工具提交一次，字段口径与正文保持一致，
               注意这一步不要写进给用户的正文。""";

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
     * 按 Agent 缓存的提示词正文，避免每次调用都读文件。
     */
    private final Map<String, String> promptCache = new ConcurrentHashMap<>();

    /**
     * 按 Agent 标识获取系统提示词。
     *
     * @param agentId Agent 标识
     * @return 系统提示词
     */
    @Override
    public String prompt(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            log.warn("未指定 Agent 标识，使用助手兜底提示词");
            return DEFAULT_PROMPT;
        }
        return promptCache.computeIfAbsent(agentId, this::loadPrompt);
    }

    /**
     * 读取指定 Agent 的提示词文件，任何异常都退回该 Agent 的兜底提示词并记 warn，保证对话主流程不中断。
     *
     * @param agentId Agent 标识
     * @return 提示词正文
     */
    private String loadPrompt(String agentId) {
        String location = resolveLocation(agentId);
        String fallbackPrompt = resolveFallbackPrompt(agentId);
        if (!StringUtils.hasText(location)) {
            log.warn("Agent 未登记提示词位置，使用兜底提示词，agentId={}", agentId);
            return fallbackPrompt;
        }
        // 这里必须写全限定名：jakarta.annotation.Resource 与本类型的 Spring Resource 同名，
        // 引入后者会让 @Resource 注入注解解析失败。
        org.springframework.core.io.Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("系统提示词文件不存在，使用兜底提示词，agentId={}，location={}", agentId, location);
            return fallbackPrompt;
        }
        try {
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!StringUtils.hasText(content)) {
                log.warn("系统提示词文件为空，使用兜底提示词，agentId={}，location={}", agentId, location);
                return fallbackPrompt;
            }
            return content.strip();
        } catch (IOException exception) {
            log.warn("系统提示词读取失败，使用兜底提示词，agentId={}，location={}", agentId, location, exception);
            return fallbackPrompt;
        }
    }

    /**
     * 解析 Agent 对应的提示词文件位置。
     *
     * @param agentId Agent 标识
     * @return 提示词文件位置，未登记的 Agent 返回 null
     */
    private String resolveLocation(String agentId) {
        if (AgentFactory.MAIN_AGENT_NAME.equals(agentId)) {
            return agentProperties.getPromptLocation();
        }
        return SUB_AGENT_PROMPT_LOCATIONS.get(agentId);
    }

    /**
     * 解析 Agent 对应的兜底提示词。
     *
     * @param agentId Agent 标识
     * @return 兜底提示词
     */
    private String resolveFallbackPrompt(String agentId) {
        return AgentFactory.RESUME_ANALYST_AGENT_NAME.equals(agentId)
                ? DEFAULT_SUB_AGENT_PROMPT : DEFAULT_PROMPT;
    }
}
