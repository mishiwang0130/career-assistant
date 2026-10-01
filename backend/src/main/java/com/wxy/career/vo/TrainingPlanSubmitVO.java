package com.wxy.career.vo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrainingPlanSubmitVO {

    /**
     * 本次生成的天数。
     *
     * <p>服务端以**用户本次请求的输入**为准（见 {@code TrainingPlanService#recordGenerationInput}）：
     * 模型填错或漏填都不影响落库，这里保留字段只是为了让 schema 与提示词有一份可对照的入参。
     */
    @JsonAlias("days_count")
    private Integer days;

    /**
     * 本次生成每天可练时长（分钟），同样以用户本次请求的输入为准。
     */
    @JsonAlias("daily_minutes")
    private Integer dailyMinutes;

    /**
     * 计划概要：总体思路与取舍说明，可为空。
     */
    private String summary;

    /**
     * 调整原因：重新规划时写清依据（新增薄弱点、进度落后、时间变化）；首次生成留空。
     */
    @JsonAlias("adjustment_reason")
    private String adjustmentReason;

    /**
     * 按天排列的训练任务，至少一条。
     */
    private List<TrainingTaskSubmitVO> tasks;
}
