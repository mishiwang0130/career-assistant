package com.wxy.career.vo;

import lombok.Data;

/**
 * 面试进度载荷，SSE {@code result} 事件的 data。
 *
 * <p>沿用 F2 冻结的判别联合：{@code type} 取 {@code interview_progress}，前端据此更新顶部进度与
 * 当前难度，F6 的点评与报告卡片继续只新增 type 取值，不改事件形态。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewProgressResultVO {

    /**
     * 结构化结果类型标识，面试进度为 interview_progress。
     */
    public static final String TYPE_INTERVIEW_PROGRESS = "interview_progress";

    /**
     * 结果类型标识，前端据此分流。
     */
    private String type = TYPE_INTERVIEW_PROGRESS;

    /**
     * 面试会话 ID。
     */
    private String sessionId;

    /**
     * 当前（或下一道）主问题序号，从 1 开始。
     */
    private Integer questionIndex;

    /**
     * 主问题总题量。
     */
    private Integer questionCount;

    /**
     * 当前题目难度等级，取值范围 1-5。
     */
    private Integer difficulty;

    /**
     * 当前轮次：1-主问题，2-追问。
     */
    private Integer roundNo;

    /**
     * 本场面试是否已结束。
     */
    private Boolean finished;

    /**
     * 由面试状态构造下发载荷。
     *
     * @param state 面试状态
     * @return 面试进度载荷
     */
    public static InterviewProgressResultVO from(InterviewStateRespVO state) {
        InterviewProgressResultVO result = new InterviewProgressResultVO();
        result.setSessionId(state.getSessionId());
        result.setQuestionIndex(state.getQuestionIndex());
        result.setQuestionCount(state.getQuestionCount());
        result.setDifficulty(state.getDifficulty());
        result.setRoundNo(state.getRoundNo());
        result.setFinished(state.getFinished());
        return result;
    }
}
