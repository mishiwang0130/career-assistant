package com.wxy.career.vo;

import lombok.Data;

/**
 * 提醒 Agent 提交的单条站内提醒。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingReminderSubmitVO {

    /**
     * 目标用户 ID，必须是只读工具返回过的用户。
     */
    private String userId;

    /**
     * 提醒正文，整条不超过 60 字。
     */
    private String content;
}
