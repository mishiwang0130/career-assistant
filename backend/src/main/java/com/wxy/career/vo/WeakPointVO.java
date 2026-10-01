package com.wxy.career.vo;

import com.wxy.career.common.enums.InterviewOutcomeEnum;
import com.wxy.career.common.enums.KnowledgeMasteryLevelEnum;
import com.wxy.career.po.KnowledgeMastery;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 专项辅导（F9）知识点条目。
 *
 * <p>独立于 PO 与 F6 的 {@code KnowledgeMasteryVO}：只暴露模型讲解需要的字段，不带用户 ID、会话 ID
 * 这类内部标识；等级与判定同时给出机器取值与中文说明，模型不必自己翻译枚举。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class WeakPointVO {

    /**
     * 证据时间的展示格式，统一到分钟即可，回答里不会用到秒。
     */
    private static final DateTimeFormatter EVIDENCE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 知识点名称，粒度到「Redis 分布式锁」这一级。
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
     * 掌握度等级中文说明，例如「薄弱」「待补强」。
     */
    private String masteryLevelLabel;

    /**
     * 是否薄弱点：掌握度低于 60 或最近一次判定为「不会或答错」时为 true。
     */
    private boolean weak;

    /**
     * 参与掌握度计算的证据条数（不含中性先验）。
     */
    private Integer evidenceCount;

    /**
     * 最近一次证据的判定：CORRECT / PARTIAL / WRONG，可能为空。
     */
    private String lastOutcome;

    /**
     * 最近一次证据判定的中文说明，例如「完全不会或答错」，可能为空。
     */
    private String lastOutcomeLabel;

    /**
     * 最近一次证据的时间，格式 yyyy-MM-dd HH:mm，可能为空。
     */
    private String lastEvidenceTime;

    /**
     * 由掌握度记录构造工具返回条目。
     *
     * @param record 掌握度记录
     * @return 知识点条目
     */
    public static WeakPointVO from(KnowledgeMastery record) {
        WeakPointVO vo = new WeakPointVO();
        vo.setKnowledgePoint(record.getKnowledgePoint());
        vo.setMasteryScore(record.getMasteryScore());
        vo.setMasteryLevel(record.getMasteryLevel());
        KnowledgeMasteryLevelEnum level = KnowledgeMasteryLevelEnum.find(record.getMasteryLevel());
        vo.setMasteryLevelLabel(level == null ? null : level.getLabel());
        vo.setWeak(Integer.valueOf(1).equals(record.getWeak()));
        vo.setEvidenceCount(record.getEvidenceCount());
        vo.setLastOutcome(record.getLastOutcome());
        InterviewOutcomeEnum outcome = InterviewOutcomeEnum.find(record.getLastOutcome());
        vo.setLastOutcomeLabel(outcome == null ? null : outcome.getLabel());
        vo.setLastEvidenceTime(formatTime(record.getLastEvidenceTime()));
        return vo;
    }

    /**
     * 把最近一次证据时间格式化成模型可读的文本。
     *
     * <p>用固定格式而不是 ISO 带 T 的写法：模型要在回答里转述「什么时候答错的」，ISO 串容易被原样念出来。
     *
     * @param time 证据时间
     * @return 格式化后的时间，为空时返回 null
     */
    private static String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(EVIDENCE_TIME_FORMATTER);
    }
}
