package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 面试报告里的错题条目。
 *
 * <p>判定为「完全不会或答错」的题既进报告的错题清单，也作为薄弱点写入 {@code knowledge_mastery} 与 Mem0，
 * 供 F7 的训练计划补强。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewWrongItemVO {

    /**
     * 主问题序号，从 1 开始。
     */
    private Integer questionIndex;

    /**
     * 轮次：1-主问题，2-追问。
     */
    private Integer roundNo;

    /**
     * 题目正文。
     */
    private String question;

    /**
     * 判定结果，固定为 WRONG。
     */
    private String outcome;

    /**
     * 判定结果的中文说明。
     */
    private String outcomeLabel;

    /**
     * 一句话点评（为什么算错题）。
     */
    private String comment;

    /**
     * 本题涉及的知识点。
     */
    private List<String> knowledgePoints;
}
