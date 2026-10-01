package com.wxy.career.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 生成（或重新规划）训练计划的请求参数。
 *
 * <p>两个输入都是生成时的一次性输入：天数是「还有几天」，落进计划的截止日期；每日时长决定每天排几个任务、
 * 每个任务多长。具体边界由 {@code app.training.plan.*} 配置决定，超出边界按参数错误拒绝。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingPlanGenerateReqVO {

    /**
     * 还有几天，至少 1 天。
     */
    @NotNull(message = "计划天数不能为空")
    @Min(value = 1, message = "计划天数至少为 1")
    @Max(value = 3650, message = "计划天数最多为 3650")
    private Integer days;

    /**
     * 每天可练时长（分钟），至少 1 分钟。
     */
    @NotNull(message = "每日时长不能为空")
    @Min(value = 1, message = "每日时长至少为 1 分钟")
    @Max(value = 1440, message = "每日时长最多为 1440 分钟")
    private Integer dailyMinutes;
}
