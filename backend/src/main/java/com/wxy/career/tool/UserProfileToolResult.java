package com.wxy.career.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wxy.career.vo.UserProfileRespVO;
import lombok.Getter;

/**
 * Agent 工具的求职目标返回体。
 *
 * <p>工具不直接返回数据库实体：只暴露模型需要判断的字段。未填写时用 {@code filled=false} 表达空状态
 * 而不是报错，让模型据此提示用户去补填；空字段不下发，避免模型把 null 当成真实取值。
 *
 * @author wxy
 * @date 2026-09-28
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserProfileToolResult {

    /**
     * 是否已填写求职目标。
     */
    private final boolean filled;

    /**
     * 目标岗位，未填写时为 null。
     */
    private final String targetPosition;

    /**
     * 当前工作年限（年），未填写时为 null。
     */
    private final Integer workYears;

    /**
     * 未填写时给模型的提示语，已填写时为 null。
     */
    private final String hint;

    /**
     * 构造工具返回体。
     *
     * @param filled 是否已填写
     * @param targetPosition 目标岗位
     * @param workYears 当前工作年限
     * @param hint 未填写时的提示语
     */
    private UserProfileToolResult(boolean filled, String targetPosition, Integer workYears, String hint) {
        this.filled = filled;
        this.targetPosition = targetPosition;
        this.workYears = workYears;
        this.hint = hint;
    }

    /**
     * 构造已填写的结果。
     *
     * @param userProfile 求职目标
     * @return 已填写的结果
     */
    public static UserProfileToolResult filled(UserProfileRespVO userProfile) {
        return new UserProfileToolResult(
                true, userProfile.getTargetPosition(), userProfile.getWorkYears(), null);
    }

    /**
     * 构造未填写的结果。
     *
     * @return 未填写的结果
     */
    public static UserProfileToolResult missing() {
        return new UserProfileToolResult(false, null, null,
                "当前用户还没有填写求职目标，请提示他到左侧「求职目标」页填写目标岗位与当前工作年限后再继续");
    }
}
