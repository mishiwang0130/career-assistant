package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.TrainingReminder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 训练提醒 Mapper。
 *
 * <p>所有查询都带 {@code user_id}：提醒是用户私有数据，跨账号必须读不到。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Mapper
public interface TrainingReminderMapper extends BaseMapper<TrainingReminder> {

    /**
     * 幂等写入当天提醒：同一天同一用户只保留一条，重复触发只更新正文。
     *
     * <p>用一条 INSERT ... ON DUPLICATE KEY UPDATE 完成，避免「先查再写」的并发窗口；命中唯一键时把未读状态
     * 重置为未读并清空已读时间——今天的内容变了，用户应当重新看到一次角标。
     *
     * @param userId 用户 ID
     * @param planId 计划 ID
     * @param reminderDate 提醒日期
     * @param content 提醒正文
     * @return 受影响行数
     */
    @Update("INSERT INTO training_reminder (user_id, plan_id, reminder_date, content, read_flag, read_time,"
            + " create_time, create_by, update_time, update_by, is_delete)"
            + " VALUES (#{userId}, #{planId}, #{reminderDate}, #{content}, 0, NULL, NOW(), 0, NOW(), 0, 0)"
            + " ON DUPLICATE KEY UPDATE plan_id = VALUES(plan_id), content = VALUES(content),"
            + " read_flag = 0, read_time = NULL, update_time = NOW(), is_delete = 0")
    int upsertDailyReminder(
            @Param("userId") Long userId,
            @Param("planId") Long planId,
            @Param("reminderDate") LocalDate reminderDate,
            @Param("content") String content);

    /**
     * 查询某用户某天的提醒。
     *
     * @param userId 用户 ID
     * @param reminderDate 提醒日期
     * @return 提醒记录，不存在时返回 null
     */
    default TrainingReminder selectByUserAndDate(Long userId, LocalDate reminderDate) {
        return selectOne(new LambdaQueryWrapper<TrainingReminder>()
                .eq(TrainingReminder::getUserId, userId)
                .eq(TrainingReminder::getReminderDate, reminderDate));
    }

    /**
     * 统计未读提醒数，用于侧栏与计划页角标。
     *
     * @param userId 用户 ID
     * @return 未读条数
     */
    @Select("SELECT COUNT(*) FROM training_reminder WHERE user_id = #{userId} AND read_flag = 0 AND is_delete = 0")
    long countUnread(@Param("userId") Long userId);

    /**
     * 把一条提醒标记为已读。
     *
     * @param userId 用户 ID
     * @param id 提醒 ID
     * @return 受影响行数
     */
    @Update("UPDATE training_reminder SET read_flag = 1, read_time = NOW(), update_time = NOW()"
            + " WHERE id = #{id} AND user_id = #{userId} AND is_delete = 0")
    int markRead(@Param("userId") Long userId, @Param("id") Long id);

    /**
     * 把该用户的全部未读提醒标记为已读（进入计划页即清零角标）。
     *
     * @param userId 用户 ID
     * @return 受影响行数
     */
    @Update("UPDATE training_reminder SET read_flag = 1, read_time = NOW(), update_time = NOW()"
            + " WHERE user_id = #{userId} AND read_flag = 0 AND is_delete = 0")
    int markAllRead(@Param("userId") Long userId);
}
