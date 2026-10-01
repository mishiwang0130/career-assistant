package com.wxy.career.vo;

import lombok.Data;

/**
 * 训练计划响应：计划概览 + 计划正文 + 今日提醒 + 未读角标。
 *
 * <p>「剩余天数」由截止日期与今天实时算出，不落库、不做定时更新；没有生效计划时 {@code hasPlan=false}。
 * 计划正文是按天的「今天练什么知识点」Markdown，前端直接渲染。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingPlanRespVO {

    /**
     * 是否存在生效中的计划。
     */
    private Boolean hasPlan = Boolean.FALSE;

    /**
     * 计划 ID。
     */
    private Long planId;

    /**
     * 计划状态值：ACTIVE-生效中，ENDED-已结束。
     */
    private String status;

    /**
     * 计划生成时的目标岗位快照。
     */
    private String targetPosition;

    /**
     * 计划开始日期，格式 yyyy-MM-dd。
     */
    private String startDate;

    /**
     * 计划截止日期，格式 yyyy-MM-dd。
     */
    private String endDate;

    /**
     * 计划总天数（生成时输入的「还有几天」）。
     */
    private Integer totalDays;

    /**
     * 每天可练时长（分钟）。
     */
    private Integer dailyMinutes;

    /**
     * 剩余天数，由截止日期与今天实时算出；截止日期已过为 0。
     */
    private Integer remainingDays;

    /**
     * 计划正文：按天一句话概括当天练什么知识点（Markdown）。
     */
    private String planContent;

    /**
     * 本次生成的调整原因，首次生成为空。
     */
    private String adjustmentReason;

    /**
     * 生成时间，格式 yyyy-MM-dd HH:mm。
     */
    private String generatedAt;

    /**
     * 今日提醒，没有时为空。
     */
    private TrainingReminderRespVO todayReminder;

    /**
     * 未读提醒数，用于侧栏与计划页角标。
     */
    private Long unreadReminderCount = 0L;
}
