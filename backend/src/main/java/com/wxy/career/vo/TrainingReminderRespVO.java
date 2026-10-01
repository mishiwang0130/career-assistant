package com.wxy.career.vo;

import lombok.Data;

/**
 * 训练提醒响应。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingReminderRespVO {

    /**
     * 提醒 ID，标记已读时回传。
     */
    private Long id;

    /**
     * 提醒日期，格式 yyyy-MM-dd。
     */
    private String reminderDate;

    /**
     * 提醒正文。
     */
    private String content;

    /**
     * 是否已读。
     */
    private Boolean read;

    /**
     * 已读时间，格式 yyyy-MM-dd HH:mm，未读时为空。
     */
    private String readTime;
}
