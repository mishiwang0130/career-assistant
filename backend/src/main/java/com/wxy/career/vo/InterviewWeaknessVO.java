package com.wxy.career.vo;

import lombok.Data;

/**
 * 面试报告里的薄弱点条目。
 *
 * <p>薄弱点来自 {@code knowledge_mastery}（掌握度权威数据），只覆盖本场面试涉及的知识点。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class InterviewWeaknessVO {

    /**
     * 知识点名称。
     */
    private String knowledgePoint;

    /**
     * 掌握度分数 0-100。
     */
    private Integer masteryScore;

    /**
     * 掌握度等级。
     */
    private String masteryLevel;

    /**
     * 掌握度等级中文说明。
     */
    private String masteryLevelLabel;

    /**
     * 最近一次证据的判定。
     */
    private String lastOutcome;

    /**
     * 最近一次证据的一句话点评，用于说明为什么薄弱。
     */
    private String comment;
}
