package com.wxy.career.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wxy.career.po.TrainingTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * 训练任务 Mapper。
 *
 * <p>所有查询都带 {@code user_id}：任务跟着计划走，跨账号必须读不到。
 *
 * @author wxy
 * @date 2026-10-01
 */
@Mapper
public interface TrainingTaskMapper extends BaseMapper<TrainingTask> {

    /**
     * 查询某份计划的全部任务，按天与天内顺序排列。
     *
     * @param userId 用户 ID
     * @param planId 计划 ID
     * @return 任务列表
     */
    default List<TrainingTask> listByPlan(Long userId, Long planId) {
        return selectList(new LambdaQueryWrapper<TrainingTask>()
                .eq(TrainingTask::getUserId, userId)
                .eq(TrainingTask::getPlanId, planId)
                .orderByAsc(TrainingTask::getDayIndex)
                .orderByAsc(TrainingTask::getSortOrder)
                .orderByAsc(TrainingTask::getId));
    }

    /**
     * 查询某份计划某一天的任务。
     *
     * @param userId 用户 ID
     * @param planId 计划 ID
     * @param taskDate 任务日期
     * @return 任务列表
     */
    default List<TrainingTask> listByPlanAndDate(Long userId, Long planId, LocalDate taskDate) {
        return selectList(new LambdaQueryWrapper<TrainingTask>()
                .eq(TrainingTask::getUserId, userId)
                .eq(TrainingTask::getPlanId, planId)
                .eq(TrainingTask::getTaskDate, taskDate)
                .orderByAsc(TrainingTask::getSortOrder)
                .orderByAsc(TrainingTask::getId));
    }

    /**
     * 统计某一天未完成的任务数，用于提醒文案里的「昨天还有几道没做完」。
     *
     * @param userId 用户 ID
     * @param planId 计划 ID
     * @param taskDate 任务日期
     * @return 未完成任务数
     */
    default long countUnfinishedOnDate(Long userId, Long planId, LocalDate taskDate) {
        return selectCount(new LambdaQueryWrapper<TrainingTask>()
                .eq(TrainingTask::getUserId, userId)
                .eq(TrainingTask::getPlanId, planId)
                .eq(TrainingTask::getTaskDate, taskDate)
                .eq(TrainingTask::getFinished, 0));
    }

    /**
     * 按主键与用户查询任务。
     *
     * @param id 任务 ID
     * @param userId 用户 ID
     * @return 任务记录，不属于该用户时返回 null
     */
    default TrainingTask selectByIdAndUser(Long id, Long userId) {
        return selectOne(new LambdaQueryWrapper<TrainingTask>()
                .eq(TrainingTask::getId, id)
                .eq(TrainingTask::getUserId, userId));
    }

    /**
     * 勾选或取消勾选任务，幂等：状态相同的重复提交结果一致。
     *
     * @param id 任务 ID
     * @param userId 用户 ID
     * @param finished 是否完成
     * @return 受影响行数
     */
    @Update("UPDATE training_task SET finished = #{finished},"
            + " finish_time = IF(#{finished} = 1, NOW(), NULL), update_time = NOW()"
            + " WHERE id = #{id} AND user_id = #{userId} AND is_delete = 0")
    int updateFinished(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("finished") int finished);
}
