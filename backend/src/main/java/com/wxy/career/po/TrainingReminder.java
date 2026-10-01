package com.wxy.career.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wxy.career.common.mybatis.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 训练提醒。
 *
 * <p>每一个用户每一天最多一条，幂等由 {@code (user_id, reminder_date)} 唯一键承载：重复触发只更新当天那一条，
 * 应用重启也不会重复生成。提醒只做站内展示，不做 IM 推送。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("training_reminder")
public class TrainingReminder extends BasePO {

    /**
     * 提醒正文最大长度，与表结构一致；提示词要求整条不超过 60 字。
     */
    public static final int CONTENT_MAX_LENGTH = 200;

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户 ID，对应 training_reminder.user_id，所有查询都必须带该条件。
     */
    private Long userId;

    /**
     * 所属计划 ID，对应 training_reminder.plan_id，关联生成提醒时该用户的生效计划。
     */
    private Long planId;

    /**
     * 提醒日期，对应 training_reminder.reminder_date，与 user_id 组成唯一键。
     */
    private LocalDate reminderDate;

    /**
     * 提醒正文，对应 training_reminder.content。
     */
    private String content;

    /**
     * 是否已读，对应 training_reminder.read_flag：0-未读，1-已读；未读数用于侧栏与计划页角标。
     */
    private Integer readFlag;

    /**
     * 已读时间，对应 training_reminder.read_time，未读时为空。
     */
    private LocalDateTime readTime;
}
