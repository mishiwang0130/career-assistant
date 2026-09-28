package com.wxy.career.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存求职目标请求。
 *
 * <p>只有目标岗位与当前工作年限两个字段，都是必填；校验失败由全局异常处理统一返回 HTTP 400。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Data
public class UserProfileSaveReqVO {

    /**
     * 目标岗位，必填，最长 100 个字符。
     */
    @NotBlank(message = "目标岗位不能为空")
    @Size(max = 100, message = "目标岗位不能超过 100 个字符")
    private String targetPosition;

    /**
     * 当前工作年限（年），必填，取值范围 0–60，0 表示应届或不足一年。
     */
    @NotNull(message = "当前工作年限不能为空")
    @Min(value = 0, message = "当前工作年限不能小于 0")
    @Max(value = 60, message = "当前工作年限不能大于 60")
    private Integer workYears;
}
