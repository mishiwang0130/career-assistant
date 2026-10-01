package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 逐题点评载荷，SSE {@code result} 事件的 {@code interview_evaluation}。
 *
 * <p>内容就是 F5 评分子 Agent 提交的那份结论（不重新评分），答完一题立即下发，界面在对应气泡下方渲染
 * 点评卡片；刷新页面或回看历史时用 {@code GET /api/interviews/{sessionId}/result} 的逐题明细回放同一份内容。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewEvaluationRespVO {

    /**
     * 结构化结果类型标识，逐题点评为 interview_evaluation。
     */
    public static final String TYPE_INTERVIEW_EVALUATION = "interview_evaluation";

    /**
     * 结果类型标识，前端据此分流。
     */
    private String type = TYPE_INTERVIEW_EVALUATION;

    /**
     * 面试会话 ID。
     */
    private String sessionId;

    /**
     * 主问题序号，从 1 开始。
     */
    private Integer questionIndex;

    /**
     * 轮次：1-主问题，2-追问。
     */
    private Integer roundNo;

    /**
     * 判定结果：CORRECT / PARTIAL / WRONG。
     */
    private String outcome;

    /**
     * 判定结果的中文说明。
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
     * 本题涉及的知识点。
     */
    private List<String> knowledgePoints;

    /**
     * 标准答案；评分不可用时为 null。
     */
    private String referenceAnswer;

    /**
     * 本题是否有评分结论。
     */
    private Boolean evaluated;
}
