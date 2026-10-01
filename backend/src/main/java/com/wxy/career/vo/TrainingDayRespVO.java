package com.wxy.career.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 训练计划的「一天」：当天的任务清单与完成进度。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingDayRespVO {

    /**
     * 第几天，从 1 开始。
     */
    private Integer dayIndex;

    /**
     * 当天日期，格式 yyyy-MM-dd。
     */
    private String taskDate;

    /**
     * 当天时长合计（分钟）。
     */
    private Integer totalMinutes;

    /**
     * 当天已完成的任务数。
     */
    private Integer finishedCount;

    /**
     * 当天任务清单。
     */
    private List<TrainingTaskRespVO> tasks = new ArrayList<>();
}
