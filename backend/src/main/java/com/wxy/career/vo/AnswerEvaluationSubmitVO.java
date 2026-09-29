package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 评分子 Agent 提交的单题评分结论。
 *
 * <p>字段与 {@code prompts/sub-evaluator.md} 的结构一一对应：{@code outcome} 与 {@code comment} 是面试流程
 * 「追问还是换题」「难度怎么走」与落库判定的必需项，其余清单供 F6 的逐题点评与掌握度使用。
 *
 * <p>结论走工具提交而不是让子 Agent 输出 JSON 文本：文本会被上级 Agent 原样转述给用户（已经出现过
 * 评分 JSON 直接贴进回答的问题），结构化提交后子 Agent 只回一句「评分完成」，用户可见的正文里就没有
 * 任何评分内容。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class AnswerEvaluationSubmitVO {

    /**
     * 判定结果，取值见 InterviewOutcomeEnum：CORRECT / PARTIAL / WRONG。
     */
    private String outcome;

    /**
     * 参考得分，取值范围 0-100，用于后续批次的掌握度统计。
     */
    private Integer score;

    /**
     * 答对或答到的点。
     */
    private List<String> correctPoints;

    /**
     * 应该提到但没有提到的点。
     */
    private List<String> missingPoints;

    /**
     * 说错、理解偏差的点。
     */
    private List<String> wrongPoints;

    /**
     * 表达层面的问题。
     */
    private List<String> expressionIssues;

    /**
     * 下次遇到同类题的建议。
     */
    private List<String> suggestions;

    /**
     * 本题涉及的知识点，用于掌握度统计。
     */
    private List<String> knowledgePoints;

    /**
     * 一句话点评，直接给结论；落库时作为判定要点（截断到 500 字符）。
     */
    private String comment;

    /**
     * 标准答案（参考答案）：这道题应该怎么答，写成可直接对照的要点与结论。
     *
     * <p>面试结束后随逐题结果一起给用户回看，因此必须给出，且不要写评分过程。
     */
    private String referenceAnswer;
}
