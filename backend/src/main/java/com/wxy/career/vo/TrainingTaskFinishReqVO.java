package com.wxy.career.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 勾选 / 取消勾选训练任务的请求参数。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingTaskFinishReqVO {

    /**
     * 目标状态：true-已完成，false-未完成；重复提交同一状态结果一致（幂等）。
     */
    @NotNull(message = "完成状态不能为空")
    private Boolean finished;
}
