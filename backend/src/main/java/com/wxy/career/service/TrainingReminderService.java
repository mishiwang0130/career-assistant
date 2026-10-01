package com.wxy.career.service;

import com.wxy.career.vo.PlannedUsersResultVO;
import com.wxy.career.vo.TrainingReminderRespVO;
import com.wxy.career.vo.TrainingReminderSubmitVO;

/**
 * 训练提醒服务。
 *
 * <p>提醒只做站内：每天由定时任务触发一次提醒 Agent，为有活跃计划的用户生成当天的提醒文案并落
 * {@code training_reminder}；幂等由 {@code (user_id, reminder_date)} 唯一键保证，重复触发只更新同一条。
 *
 * @author wxy
 * @date 2026-10-01
 */
public interface TrainingReminderService {

    /**
     * 统计未读提醒数，用于侧栏与计划页角标。
     *
     * @param userId 用户 ID
     * @return 未读条数
     */
    long unreadCount(Long userId);

    /**
     * 查询某用户某天的提醒。
     *
     * @param userId 用户 ID
     * @param reminderDate 提醒日期
     * @return 提醒响应，不存在时返回 null
     */
    TrainingReminderRespVO findReminder(Long userId, java.time.LocalDate reminderDate);

    /**
     * 把一条提醒标记为已读。
     *
     * @param userId 用户 ID
     * @param reminderId 提醒 ID
     */
    void markRead(Long userId, Long reminderId);

    /**
     * 把该用户全部未读提醒标记为已读（进入计划页即清零角标）。
     *
     * @param userId 用户 ID
     */
    void markAllRead(Long userId);

    /**
     * 列出今天需要提醒的用户及其训练简报（提醒 Agent 的只读工具）。
     *
     * <p>口径：有生效计划、计划未结束、今天安排了任务的用户；单次最多返回
     * {@code app.training.reminder.max-users-per-run} 个，按 user_id 升序。
     *
     * @return 用户简报列表
     */
    PlannedUsersResultVO listPlannedUsers();

    /**
     * 写入当天提醒（提醒 Agent 的写出工具）。
     *
     * <p>幂等：同一天同一用户只保留一条。目标用户当天没有生效计划或今天没有任务时直接跳过并返回 false，
     * 不写库、不报错。
     *
     * @param submitVO 单条提醒
     * @return true 表示已写入或更新，false 表示按口径跳过
     */
    boolean saveReminder(TrainingReminderSubmitVO submitVO);
}
