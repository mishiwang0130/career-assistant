package com.wxy.career.vo;

import lombok.Data;

/**
 * 训练提醒未读数响应，供侧栏角标轮询。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class TrainingReminderUnreadRespVO {

    /**
     * 未读提醒条数。
     */
    private Long count;
}
