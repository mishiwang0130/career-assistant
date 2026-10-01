package com.wxy.career.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 计划 Agent 提交的单条训练任务。
 *
 * <p>字段名与任务 schema 一致：模型照 schema 填，服务端再按分配器骨架与配置边界校验。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrainingTaskSubmitVO {

    /**
     * 第几天，从 1 开始，必须落在计划天数内。
     */
    @JsonAlias("day_index")
    private Integer dayIndex;

    /**
     * 训练主题，例如「Redis 分布式锁补齐」。
     */
    private String topic;

    /**
     * 题型，例如八股、项目、综合。
     */
    @JsonAlias("question_type")
    private String questionType;

    /**
     * 难度，取值 1-5。
     */
    private Integer difficulty;

    /**
     * 预计时长（分钟）。
     */
    @JsonAlias("duration_minutes")
    private Integer durationMinutes;

    /**
     * 对应知识点名称，可为空（主题不是补薄弱点时可留空）。
     */
    @JsonAlias("knowledge_point")
    private String knowledgePoint;
}
