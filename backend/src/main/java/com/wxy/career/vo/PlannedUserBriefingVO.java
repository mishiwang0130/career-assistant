package com.wxy.career.vo;

import lombok.Data;

/**
 * 需要提醒的用户简报（提醒 Agent 的只读工具返回）。
 *
 * <p>只带提醒文案必需的信息：用户 ID、目标岗位、剩余天数，以及**计划正文**——没有任务表，
 * 提醒 Agent 需要读计划正文自己推断「今天该干什么」。不带昵称、账号、简历等与提醒无关的个人信息。
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
     * 计划已进行到第几天（第 1 天是计划开始日期当天）。
     */
    private Integer dayIndex;

    /**
     * 计划正文：一天一行「第 N 天：今天练什么知识点」。
     */
    private String planContent;
}
