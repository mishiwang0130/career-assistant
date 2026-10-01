package com.wxy.career.vo;

import com.wxy.career.common.enums.KnowledgeMasteryLevelEnum;
import com.wxy.career.po.KnowledgeMastery;
import lombok.Data;

/**
 * 知识点掌握度展示结构。
 *
 * <p>面试报告里的掌握度用等级与进度条展示（不只是一段文字），等级口径见 {@link KnowledgeMasteryLevelEnum}。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
public class KnowledgeMasteryVO {

    /**
     * 知识点名称。
     */
    private String knowledgePoint;

    /**
     * 掌握度分数 0-100。
     */
    private Integer masteryScore;

    /**
     * 掌握度等级：WEAK / NEEDS_WORK / BASIC / PROFICIENT / MASTERED。
     */
    private String masteryLevel;

    /**
     * 掌握度等级中文说明。
     */
    private String masteryLevelLabel;

    /**
     * 是否薄弱点。
     */
    private Boolean weak;

    /**
     * 参与计算的证据条数。
     */
    private Integer evidenceCount;

    /**
     * 最近一次证据的判定。
     */
    private String lastOutcome;

    /**
     * 由掌握度记录构造展示结构。
     *
     * @param record 掌握度记录
     * @return 展示结构
     */
    public static KnowledgeMasteryVO from(KnowledgeMastery record) {
        KnowledgeMasteryVO vo = new KnowledgeMasteryVO();
        vo.setKnowledgePoint(record.getKnowledgePoint());
        vo.setMasteryScore(record.getMasteryScore());
        vo.setMasteryLevel(record.getMasteryLevel());
        KnowledgeMasteryLevelEnum level = KnowledgeMasteryLevelEnum.find(record.getMasteryLevel());
        vo.setMasteryLevelLabel(level == null ? null : level.getLabel());
        vo.setWeak(Integer.valueOf(1).equals(record.getWeak()));
        vo.setEvidenceCount(record.getEvidenceCount());
        vo.setLastOutcome(record.getLastOutcome());
        return vo;
    }
}
