package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 面试结果，SSE {@code result} 事件的 data 与 `GET /api/interviews/{sessionId}/result` 的返回结构。
 *
 * <p>沿用 F2 冻结的判别联合：{@code type} 取 {@code interview_result}。面试结束时下发一次，界面据此渲染
 * 「逐题结果」卡片（每道题答得不好的地方 + 标准答案）；刷新页面或回看历史会话时用同一条接口拿同一份数据。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewResultRespVO {

    /**
     * 结构化结果类型标识，面试结果为 interview_result。
     */
    public static final String TYPE_INTERVIEW_RESULT = "interview_result";

    /**
     * 结果类型标识，前端据此分流。
     */
    private String type = TYPE_INTERVIEW_RESULT;

    /**
     * 面试会话 ID。
     */
    private String sessionId;

    /**
     * 本场面试的主问题总题量。
     */
    private Integer questionCount;

    /**
     * 已作答的回合数（含追问）。
     */
    private Integer answeredCount;

    /**
     * 判定为「答到要点」的回合数。
     */
    private Integer correctCount;

    /**
     * 判定为「答得有遗漏」的回合数。
     */
    private Integer partialCount;

    /**
     * 判定为「不会或答错」的回合数（错题）。
     */
    private Integer wrongCount;

    /**
     * 有评分的回合的平均分，没有评分时为 null。
     */
    private Integer averageScore;

    /**
     * 本场面试是否已结束。
     */
    private Boolean finished;

    /**
     * 逐题明细，按作答顺序排列。
     */
    private List<InterviewResultItemVO> items;
}
