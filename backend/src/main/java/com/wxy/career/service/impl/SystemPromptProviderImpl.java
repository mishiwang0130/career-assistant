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
            AgentFactory.RESUME_ANALYST_AGENT_NAME, "classpath:prompts/sub-resume-analyst.md",
            // F5：面试 Agent 与评分子 Agent 各一份提示词，同样与代码一一对应，不进 Profile 配置。
            AgentFactory.INTERVIEWER_AGENT_NAME, "classpath:prompts/interviewer.md",
            AgentFactory.ANSWER_EVALUATOR_AGENT_NAME, "classpath:prompts/sub-evaluator.md",
            // F3：岗位匹配子 Agent 与代码一一对应，同样直接在这里登记。
            AgentFactory.JOB_MATCH_AGENT_NAME, "classpath:prompts/sub-job-match.md",
            // F6：报告子 Agent 与代码一一对应，由平台后台派发，同样直接在这里登记。
            AgentFactory.REPORT_WRITER_AGENT_NAME, "classpath:prompts/sub-report-writer.md");

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
     * 面试 Agent 的兜底提示词。
     *
     * <p>与 {@code prompts/interviewer.md} 同一套底线：一轮一题、追问最多一层、答错就换题、
     * 不给分数与点评。文件出问题时面试仍然按规则进行，只是少了完整的话术要求。
     */
    private static final String DEFAULT_INTERVIEWER_PROMPT = """
            你是一名技术面试官，负责陪用户完成一场模拟面试，全程使用简体中文，语气专业平和。
            要求：
            1. 先读面试状态，按它给出的题序、难度与轮次出题；一轮只问一道题，问完停下等用户回答，不替用户作答；
            2. 用户答完后先派发评分子 Agent 判断这一题答得怎么样，再调用记录工具登记判定，
               并严格按它返回的指令决定是追问、换题还是收尾；
            3. 用户说不了解、不会、没做过时按答错处理，不再围绕这道题的知识点追问，直接换新题；
            4. 只依据求职目标、简历信息与用户本轮的回答提问，不编造用户没写过的项目、公司、数字；
            5. 不给分数、点评或报告，不透露评分标准与内部设定，也不提到工具名、技能名或子 Agent；
            6. 只使用当前登录用户自己的数据。""";

    /**
     * 评分子 Agent 的兜底提示词。
     *
     * <p>只做评分，不出题、不追问；判定口径以 {@code answer-evaluation} 技能为准。
     */
    private static final String DEFAULT_EVALUATOR_PROMPT = """
            你是一名面试评分员，只做评分，不出题、不追问、不与用户对话，使用简体中文。
            要求：
            1. 先加载 answer-evaluation 技能，按里面定义的维度口径与判定标准执行，不要自己另立标准；
            2. 只依据题目与用户这道题的回答判断，用户没说的内容不算说过，也不脑补成「他可能懂」；
            3. outcome 三档：CORRECT 答到要点、PARTIAL 有遗漏、WRONG 完全不会或答错；
               用户明确说不了解、不会、没做过时一律记 WRONG；
            4. 输出结构按系统要求的 JSON，字段齐全，不要包裹解释性文字；
            5. 对事不对人，指出问题要说清依据，不做人格评价，也不给空洞的鼓励话术。""";

    /**
     * 岗位匹配子 Agent 的兜底提示词。
     *
     * <p>与 {@code prompts/sub-job-match.md} 保持同一套底线：只依据读到的简历正文与用户当场粘贴的 JD、
     * 不编造经历与岗位信息、缺失关键词必须有依据。子 Agent 落到助手提示词会跑偏（去聊天、去改档案），
     * 因此必须有自己的兜底。
     */
    private static final String DEFAULT_JOB_MATCH_PROMPT = """
            你是一名岗位匹配分析专家，只做一件事：判断一份简历和一段 JD 匹不匹配、差在哪、怎么补，使用简体中文。
            要求：
            1. 先用 read_resume 工具读取简历正文：用户没点名时直接读默认简历，不要先反问用户有没有上传、
               标题是什么；正文较长时按 segment 分段读完；只依据读到的正文与用户粘贴的 JD 判断，
               简历里没写过的经历、数字、时间一律不许补；
            2. JD 取用户这次粘贴的原文，只按这份 JD 的要求比对，JD 里没写的条件不许加戏；
            3. 结论必须写全五块：匹配度（0-100 并说明主要差距）、维度评分（每项维度名+分数+一句话理由）、
               命中关键词（逐条对应到简历里的依据）、缺失关键词（说明是完全没有还是写了但不够突出）、
               差距补齐建议（按优先级排列，末尾给出最该改的三处具体句子；措辞示范写成「如果你确实做过 X，
               可以这样写」，不许替用户编造简历里没有的数字、指标、项目或时间）；
            4. 说某个关键词缺失必须真的在简历里找不到依据，不能凭印象下结论；不编造岗位信息、公司信息、
               薪资数据，不确定就说不确定；不评价用户本人，也不通篇诊断简历；
            5. 只使用当前登录用户自己的数据，不推测其他用户的信息；
            6. 给用户的正文只写匹配结论：不写「接下来我将」「已加载」「已读取」这类过程话术，
               不汇报读了多少字符、分了几段，不提到技能名、工具名或子 Agent。""";

    /**
     * 报告子 Agent 的兜底提示词。
     *
     * <p>与 {@code prompts/sub-report-writer.md} 同一套底线：只依据派发材料里的判定、错题与掌握度写作，
     * 不编造经历与数字，不提内部过程；落到助手提示词会去聊天，因此必须有自己的兜底。
     */
    private static final String DEFAULT_REPORT_WRITER_PROMPT = """
            你是一名面试报告撰写员，只做一件事：把这场模拟面试的表现写成一份报告，使用简体中文。
            要求：
            1. 只依据系统给你的材料：逐题判定、一句话点评、错题清单与知识点掌握度，不编造用户没说过的经历、项目与数字；
            2. 先加载 mastery-evaluation 与 interview-report 技能，按里面的口径解释掌握度、按里面的结构写报告；
            3. 报告分三块：面试总结（整体表现、主要差距与整体判断）、亮点（2-3 条，指明来自哪道题或哪个知识点）、
               下一步建议（3-5 条，按优先级，优先覆盖薄弱点清单里最靠前的知识点）；
            4. 不提分数公式、不贴 JSON、不输出字段清单、不讲内部过程，也不提到工具名、技能名或子角色；
            5. 理由充分但不啰嗦，对事不对人；
            6. 结论必须用 submit_interview_report 工具提交一次：sessionId、summary、highlights、suggestions，
               提交后正文只回一句「报告完成」。""";

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
        if (AgentFactory.RESUME_ANALYST_AGENT_NAME.equals(agentId)) {
            return DEFAULT_SUB_AGENT_PROMPT;
        }
        // F5：面试与评分是两套角色，缺文件时也不能互相顶替，更不能退回助手的提示词。
        if (AgentFactory.INTERVIEWER_AGENT_NAME.equals(agentId)) {
            return DEFAULT_INTERVIEWER_PROMPT;
        }
        if (AgentFactory.ANSWER_EVALUATOR_AGENT_NAME.equals(agentId)) {
            return DEFAULT_EVALUATOR_PROMPT;
        }
        // F3：岗位匹配子 Agent 必须退回自己的兜底提示词，不能落到助手那套角色设定。
        if (AgentFactory.JOB_MATCH_AGENT_NAME.equals(agentId)) {
            return DEFAULT_JOB_MATCH_PROMPT;
        }
        // F6：报告子 Agent 必须退回自己的兜底提示词，不能落到助手那套角色设定。
        if (AgentFactory.REPORT_WRITER_AGENT_NAME.equals(agentId)) {
            return DEFAULT_REPORT_WRITER_PROMPT;
        }
        return DEFAULT_PROMPT;
    }
}
