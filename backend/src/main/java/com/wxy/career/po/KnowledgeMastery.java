package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 知识点掌握度。
 *
 * <p>一个用户一个知识点一行，是掌握度的权威数据（Mem0 只存可跨会话召回的薄弱点，读取时以本表为准）。
 * 计算口径写死在 {@code MasteryCalculator} 与 {@code docs/技术约定.md} 的「面试点评与报告（F6）」章节：
 * 近期多次证据按时间衰减加权，再加一条中性先验，单题答错不会直接打到最低。
 *
 * @author wxy
 * @date 2026-09-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_mastery")
public class KnowledgeMastery extends BasePO {

    /**
     * 知识点名称最大长度，与表结构一致。
     */
    public static final int KNOWLEDGE_POINT_MAX_LENGTH = 200;

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 knowledge_mastery.user_id，所有查询都必须带该条件。
     */
    private Long userId;

    /**
     * 知识点名称，对应 knowledge_mastery.knowledge_point，粒度到「Redis 分布式锁」这一级。
     */
    private String knowledgePoint;

    /**
     * 掌握度分数，对应 knowledge_mastery.mastery_score，取值 0-100。
     */
    private Integer masteryScore;

    /**
     * 掌握度等级，对应 knowledge_mastery.mastery_level，取值见 KnowledgeMasteryLevelEnum。
     */
    private String masteryLevel;

    /**
     * 是否薄弱点，对应 knowledge_mastery.weak：0-否，1-是。
     */
    private Integer weak;

    /**
     * 参与计算的证据条数（不含中性先验），对应 knowledge_mastery.evidence_count。
     */
    private Integer evidenceCount;

    /**
     * 最近一次证据所在的面试会话 ID，对应 knowledge_mastery.last_session_id。
     */
    private Long lastSessionId;

    /**
     * 最近一次证据的判定，对应 knowledge_mastery.last_outcome，取值见 InterviewOutcomeEnum。
     */
    private String lastOutcome;

    /**
     * 最近一次证据的时间，对应 knowledge_mastery.last_evidence_time。
     */
    private LocalDateTime lastEvidenceTime;
}
