package com.wxy.career.vo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 覆盖已有训练计划的确认请求。
 *
 * <p>计划页不是会话，确认与生成分成两个请求：生成流遇到「要不要覆盖当前计划」时下发确认请求并结束，
 * 用户点确认或取消后由本请求把结论送回后端，恢复同一次生成。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingPlanConfirmReqVO {

    /**
     * 是否同意覆盖：true-覆盖并生成新计划，false-放弃本次生成、保留当前计划不变。
     */
    @NotNull(message = "确认结果不能为空")
    private Boolean approved;
}
