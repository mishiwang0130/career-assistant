package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 面试结果里的单题明细。
 *
 * <p>一场面试结束后逐题给用户回看：题目、自己的回答、判定结果、答得不好的地方（缺失点、错误点、表达问题）、
 * 下次怎么答，以及这道题的标准答案。数据来自评分子 Agent 的结论（`interview_qa.evaluation_json`）。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewResultItemVO {

    /**
     * 主问题序号，从 1 开始。
     */
    private Integer questionIndex;

    /**
     * 同一道主问题下的轮次：1-主问题，2-追问。
     */
    private Integer roundNo;

    /**
     * 题目正文。
     */
    private String question;

    /**
     * 用户本题的回答。
     */
    private String answer;

    /**
     * 判定结果：CORRECT / PARTIAL / WRONG。
     */
    private String outcome;

    /**
     * 判定结果的中文说明，前端直接展示。
     */
    private String outcomeLabel;

    /**
     * 本题难度等级 1-5。
     */
    private Integer difficulty;

    /**
     * 参考得分 0-100，没有评分时为 null。
     */
    private Integer score;

    /**
     * 一句话点评。
     */
    private String comment;

    /**
     * 应该提到但没提到的点——「哪里答得不好」的主要来源。
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
     * 下次遇到同类题应该怎么答。
     */
    private List<String> suggestions;

    /**
     * 本题涉及的知识点。
     */
    private List<String> knowledgePoints;

    /**
     * 标准答案（参考答案）。
     */
    private String referenceAnswer;

    /**
     * 本题是否有评分子 Agent 的结论；false 表示评分不可用，只有判定结果可用。
     */
    private Boolean evaluated;
}
