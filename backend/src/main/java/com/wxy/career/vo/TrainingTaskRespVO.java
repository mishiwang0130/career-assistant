package com.wxy.career.vo;

import lombok.Data;

/**
 * 训练任务响应。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingTaskRespVO {

    /**
     * 任务 ID，勾选时回传。
     */
    private Long id;

    /**
     * 所属计划 ID。
     */
    private Long planId;

    /**
     * 第几天，从 1 开始。
     */
    private Integer dayIndex;

    /**
     * 任务日期，格式 yyyy-MM-dd。
     */
    private String taskDate;

    /**
     * 训练主题。
     */
    private String topic;

    /**
     * 题型，例如八股、项目、综合。
     */
    private String questionType;

    /**
     * 难度，取值 1-5。
     */
    private Integer difficulty;

    /**
     * 预计时长（分钟）。
     */
    private Integer durationMinutes;

    /**
     * 对应知识点名称，可为空。
     */
    private String knowledgePoint;

    /**
     * 同一天内的排序号。
     */
    private Integer sortOrder;

    /**
     * 是否已完成。
     */
    private Boolean finished;

    /**
     * 完成时间，格式 yyyy-MM-dd HH:mm，未完成时为空。
     */
    private String finishTime;
}
