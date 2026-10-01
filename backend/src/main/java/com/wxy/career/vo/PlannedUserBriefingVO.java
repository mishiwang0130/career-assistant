package com.wxy.career.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 需要提醒的用户简报（提醒 Agent 的只读工具返回）。
 *
 * <p>只带提醒文案必需的训练数据：用户 ID、目标岗位、剩余天数、今天的任务与昨天未完成数；不带昵称、账号、
 * 简历等其它个人信息，避免提醒链路接触到与文案无关的用户数据。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Data
public class PlannedUserBriefingVO {

    /**
     * 用户 ID，写出提醒时必须原样回传这个值。
     */
    private String userId;

    /**
     * 目标岗位，可为空。
     */
    private String targetPosition;

    /**
     * 计划剩余天数，按截止日期实时算出。
     */
    private Integer remainingDays;

    /**
     * 今天的任务描述，形如「Redis 分布式锁补齐（八股，30 分钟）」。
     */
    private List<String> todayTasks = new ArrayList<>();

    /**
     * 昨天未完成的任务数。
     */
    private long yesterdayUnfinishedCount;
}
