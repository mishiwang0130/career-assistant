package com.wxy.career.vo;

import lombok.Data;

import java.util.List;

/**
 * 计划 Agent 提交的训练计划结论。
 *
 * <p>计划正文不落工作区文件：模型把完整的按天任务清单通过本结构一次提交，服务端校验后落 MySQL（旧计划标记
 * 结束、新计划与新任务写入），提交成功后模型只需简短说明取舍。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingPlanSubmitVO {

    /**
     * 本次生成的天数，必须与请求里输入的一致。
     */
    private Integer days;

    /**
     * 本次生成每天可练时长（分钟），必须与请求里输入的一致。
     */
    private Integer dailyMinutes;

    /**
     * 计划概要：总体思路与取舍说明，可为空。
     */
    private String summary;

    /**
     * 调整原因：重新规划时写清依据（新增薄弱点、进度落后、时间变化）；首次生成留空。
     */
    private String adjustmentReason;

    /**
     * 按天排列的训练任务，至少一条。
     */
    private List<TrainingTaskSubmitVO> tasks;
}
