package com.wxy.career.vo;

import lombok.Data;

/**
 * 面试 Agent 提交单题判定的入参。
 *
 * <p>由面试 Agent 转述评分子 Agent 的结论后填入：题目、题型与判定结果决定追问还是换题、难度怎么走。
 * 用户本题的回答不在这里——它由对话通道在进流前记下，保证落库的是用户实际发送的内容。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewAnswerSubmitVO {

    /**
     * 本次提问的题目正文（追问时填追问内容）。
     */
    private String question;

    /**
     * 题型，取值见 InterviewQuestionTypeEnum：BASIC/PROJECT/COMPREHENSIVE。
     */
    private String questionType;

    /**
     * 判定结果，取值见 InterviewOutcomeEnum：CORRECT/PARTIAL/WRONG。
     */
    private String outcome;

    /**
     * 判定要点，来自评分子 Agent 的结论摘要，可空，超长会被截断。
     */
    private String judgement;

    /**
     * 用户是否主动要求结束本场面试；为 true 时本回合结束后直接收尾。
     */
    private Boolean endNow;
}
