package com.wxy.career.vo;

import lombok.Data;

/**
 * 一回合记录完成后的下一步指令。
 *
 * <p>面试 Agent 的工具返回值：它据此知道该追问还是换题、下一题用哪个难度与题型、是不是该收尾。
 * 字段与 {@link InterviewStateRespVO} 同口径，供测试与提示词使用。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewAnswerResultVO {

    /**
     * 面试会话 ID。
     */
    private String sessionId;

    /**
     * 本回合之后的流程动作，取值见 InterviewActionEnum。
     */
    private String action;

    /**
     * 下一步要出的题的主问题序号，从 1 开始。
     */
    private Integer questionIndex;

    /**
     * 主问题总题量。
     */
    private Integer questionCount;

    /**
     * 下一步题目的难度等级，取值范围 1-5。
     */
    private Integer difficulty;

    /**
     * 下一步的轮次：1-主问题，2-追问。
     */
    private Integer roundNo;

    /**
     * 下一步题目的题型：追问沿用主问题题型，换新题时按配比给出建议题型；已结束时为 null。
     */
    private String questionType;

    /**
     * 本场面试是否已结束。
     */
    private Boolean finished;
}
