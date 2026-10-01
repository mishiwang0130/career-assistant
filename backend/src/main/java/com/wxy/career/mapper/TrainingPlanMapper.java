package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.common.enums.TrainingPlanStatusEnum;
import com.wxy.career.po.TrainingPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 训练计划 Mapper。
 *
 * <p>所有查询都带 {@code user_id}：计划是用户私有数据，跨账号必须读不到。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Mapper
public interface TrainingPlanMapper extends BaseMapper<TrainingPlan> {

    /**
     * 查询指定用户当前生效的计划。
     *
     * @param userId 用户 ID
     * @return 生效中的计划，没有时返回 null
     */
    default TrainingPlan selectActiveByUser(Long userId) {
        return selectOne(new LambdaQueryWrapper<TrainingPlan>()
                .eq(TrainingPlan::getUserId, userId)
                .eq(TrainingPlan::getStatus, TrainingPlanStatusEnum.ACTIVE.getValue())
                .orderByDesc(TrainingPlan::getId)
                .last("LIMIT 1"));
    }

    /**
     * 按主键与用户查询计划，用于校验任务与计划归属。
     *
     * @param id 计划 ID
     * @param userId 用户 ID
     * @return 计划记录，不属于该用户时返回 null
     */
    default TrainingPlan selectByIdAndUser(Long id, Long userId) {
        return selectOne(new LambdaQueryWrapper<TrainingPlan>()
                .eq(TrainingPlan::getId, id)
                .eq(TrainingPlan::getUserId, userId));
    }

    /**
     * 把该用户当前生效的计划标记为已结束。
     *
     * <p>重规划是覆盖生成：旧计划不物理删除，只改状态，历史任务行保留可回溯。
     *
     * @param userId 用户 ID
     * @return 受影响行数
     */
    @Update("UPDATE training_plan SET status = 'ENDED', update_time = NOW()"
            + " WHERE user_id = #{userId} AND status = 'ACTIVE' AND is_delete = 0")
    int endActiveByUser(@Param("userId") Long userId);

    /**
     * 查询有活跃计划的用户，供每日提醒批量处理。
     *
     * <p>口径：状态为 ACTIVE 且截止日期不早于今天（计划已结束的用户不提醒）。按 user_id 升序保证单次运行的
     * 处理顺序稳定，便于对账与测试。
     *
     * @param today 今天（按提醒时区的本地日期）
     * @param limit 单次最多处理的用户数
     * @return 活跃计划列表，一个用户最多一条
     */
    @Select("SELECT * FROM training_plan WHERE status = 'ACTIVE' AND end_date >= #{today}"
            + " AND is_delete = 0 ORDER BY user_id ASC LIMIT #{limit}")
    List<TrainingPlan> listActivePlans(@Param("today") LocalDate today, @Param("limit") int limit);
}
