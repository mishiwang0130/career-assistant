package com.wxy.career.vo;

import lombok.Data;

/**
 * 面试 Agent 提交单题判定的入参。
 *
 * <p>只填「题目、题型、是否结束」：判定结果不再由面试 Agent 转述，而是由评分子 Agent 用
 * {@code submit_answer_evaluation} 提交、流程服务从运行态缓冲里取，避免评分内容经面试官之口泄漏给用户。
 * 用户本题的回答也不在这里——它由对话通道在进流前记下，保证落库的是用户实际发送的内容。
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
     * 用户是否主动要求结束本场面试；为 true 时本回合结束后直接收尾。
     */
    private Boolean endNow;
}
