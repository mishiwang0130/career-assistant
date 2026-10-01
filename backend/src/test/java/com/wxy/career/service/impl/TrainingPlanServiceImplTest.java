package com.wxy.career.service.impl;

import com.wxy.career.common.enums.TrainingPlanStatusEnum;
import com.wxy.career.common.exception.BizException;
import com.wxy.career.config.TrainingProperties;
import com.wxy.career.mapper.TrainingPlanMapper;
import com.wxy.career.mapper.TrainingReminderMapper;
import com.wxy.career.mapper.TrainingTaskMapper;
import com.wxy.career.po.TrainingPlan;
import com.wxy.career.po.TrainingTask;
import com.wxy.career.service.UserProfileService;
import com.wxy.career.vo.TrainingPlanRespVO;
import com.wxy.career.vo.TrainingPlanSubmitVO;
import com.wxy.career.vo.TrainingTaskSubmitVO;
import com.wxy.career.vo.UserProfileRespVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 训练计划服务测试。
 *
 * <p>固定四条写死的口径：重规划覆盖生成且旧计划标记结束（不物理删除）、调整原因被保留、任务必须覆盖每一天、
 * 跨账号读不到别人的任务。不连数据库、不依赖模型。
 *
 * @author wxy
 * @date 2026-10-01
 */
class TrainingPlanServiceImplTest {

    /**
     * 被测服务。
     */
    private TrainingPlanServiceImpl trainingPlanService;

    /**
     * 计划 Mapper 桩。
     */
    private TrainingPlanMapper trainingPlanMapper;

    /**
     * 任务 Mapper 桩。
     */
    private TrainingTaskMapper trainingTaskMapper;

    /**
     * 提醒 Mapper 桩。
     */
    private TrainingReminderMapper trainingReminderMapper;

    /**
     * 组装被测服务。
     */
    @BeforeEach
    void setUp() {
        trainingPlanMapper = mock(TrainingPlanMapper.class);
        trainingTaskMapper = mock(TrainingTaskMapper.class);
        trainingReminderMapper = mock(TrainingReminderMapper.class);
        UserProfileService userProfileService = mock(UserProfileService.class);
        UserProfileRespVO profile = new UserProfileRespVO();
        profile.setTargetPosition("Java 后端开发");
        profile.setWorkYears(3);
        when(userProfileService.getRequiredUserProfile(anyLong())).thenReturn(profile);

        trainingPlanService = new TrainingPlanServiceImpl();
        ReflectionTestUtils.setField(trainingPlanService, "trainingPlanMapper", trainingPlanMapper);
        ReflectionTestUtils.setField(trainingPlanService, "trainingTaskMapper", trainingTaskMapper);
        ReflectionTestUtils.setField(trainingPlanService, "trainingReminderMapper", trainingReminderMapper);
        ReflectionTestUtils.setField(trainingPlanService, "userProfileService", userProfileService);
        ReflectionTestUtils.setField(trainingPlanService, "trainingProperties", new TrainingProperties());
    }

    /**
     * 首次生成：写入计划与任务，截止日期由天数算出，目标岗位取生成时的快照。
     */
    @Test
    void shouldCreatePlanWithSnapshotAndDeadline() {
        when(trainingPlanMapper.insert(any(TrainingPlan.class))).thenAnswer(invocation -> {
            TrainingPlan plan = invocation.getArgument(0);
            plan.setId(77L);
            return 1;
        });
        when(trainingPlanMapper.selectByIdAndUser(77L, 1L)).thenAnswer(invocation -> {
            TrainingPlan plan = new TrainingPlan();
            plan.setId(77L);
            plan.setUserId(1L);
            plan.setStatus(TrainingPlanStatusEnum.ACTIVE.getValue());
            plan.setTargetPosition("Java 后端开发");
            plan.setTotalDays(3);
            plan.setDailyMinutes(60);
            plan.setStartDate(LocalDate.now());
            plan.setEndDate(LocalDate.now().plusDays(2));
            return plan;
        });
        when(trainingTaskMapper.listByPlan(1L, 77L)).thenReturn(List.of());

        trainingPlanService.submitPlan(1L, "training-plan-1", buildSubmit(3, 60, null));

        verify(trainingPlanMapper).endActiveByUser(1L);
        verify(trainingPlanMapper).insert(any(TrainingPlan.class));
        // 3 天 × 2 条 = 6 条任务。
        verify(trainingTaskMapper, times(6)).insert(any(TrainingTask.class));
        TrainingPlanRespVO planned = trainingPlanService.consumeSubmittedPlan(1L, "training-plan-1");
        assertThat(planned).isNotNull();
        assertThat(planned.getUnreadReminderCount()).isZero();
        // 缓冲取走后再取一次为空：结果只下发一次。
        assertThat(trainingPlanService.consumeSubmittedPlan(1L, "training-plan-1")).isNull();
    }

    /**
     * 重规划：仍写入新计划（旧计划由 Mapper 标记结束），调整原因被保留。
     */
    @Test
    void shouldKeepAdjustmentReasonOnRegeneration() {
        List<TrainingPlan> inserted = new ArrayList<>();
        when(trainingPlanMapper.insert(any(TrainingPlan.class))).thenAnswer(invocation -> {
            TrainingPlan plan = invocation.getArgument(0);
            plan.setId(88L);
            inserted.add(plan);
            return 1;
        });

        trainingPlanService.submitPlan(2L, "training-plan-2", buildSubmit(2, 60, "新增薄弱点：Redis 分布式锁"));

        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).getAdjustmentReason()).isEqualTo("新增薄弱点：Redis 分布式锁");
        assertThat(inserted.get(0).getStatus()).isEqualTo(TrainingPlanStatusEnum.ACTIVE.getValue());
        assertThat(inserted.get(0).getStartDate()).isEqualTo(LocalDate.now());
        assertThat(inserted.get(0).getEndDate()).isEqualTo(LocalDate.now().plusDays(1));
    }

    /**
     * 任务没有覆盖每一天时拒绝提交，模型会拿到可读提示重新提交。
     */
    @Test
    void shouldRejectSubmitWhenSomeDayHasNoTask() {
        TrainingPlanSubmitVO submit = buildSubmit(3, 60, null);
        // 整天的任务都删掉：第 3 天没有任务，属于「某一天没有安排」。
        submit.getTasks().removeIf(task -> task.getDayIndex() == 3);

        assertThatThrownBy(() -> trainingPlanService.submitPlan(1L, "training-plan-1", submit))
                .isInstanceOf(BizException.class);
        verify(trainingPlanMapper, never()).insert(any(TrainingPlan.class));
    }

    /**
     * 当天任务时长超过每日时长时拒绝提交。
     */
    @Test
    void shouldRejectSubmitWhenDayExceedsDailyMinutes() {
        TrainingPlanSubmitVO submit = buildSubmit(1, 30, null);
        submit.getTasks().get(1).setDurationMinutes(60);

        assertThatThrownBy(() -> trainingPlanService.submitPlan(1L, "training-plan-1", submit))
                .isInstanceOf(BizException.class);
        verify(trainingPlanMapper, never()).insert(any(TrainingPlan.class));
    }

    /**
     * 勾选任务：任务不存在或跨账号统一按找不到处理（1051）。
     */
    @Test
    void shouldRejectCrossAccountTaskToggle() {
        when(trainingTaskMapper.selectByIdAndUser(99L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> trainingPlanService.finishTask(1L, 99L, true))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1051));
        verify(trainingTaskMapper, never()).updateFinished(anyLong(), anyLong(), anyInt());
    }

    /**
     * 勾选属于已结束计划的任务：提示任务已失效（1702）。
     */
    @Test
    void shouldRejectTaskOfEndedPlan() {
        TrainingTask task = new TrainingTask();
        task.setId(5L);
        task.setUserId(1L);
        task.setPlanId(6L);
        when(trainingTaskMapper.selectByIdAndUser(5L, 1L)).thenReturn(task);
        TrainingPlan ended = new TrainingPlan();
        ended.setId(6L);
        ended.setStatus(TrainingPlanStatusEnum.ENDED.getValue());
        when(trainingPlanMapper.selectByIdAndUser(6L, 1L)).thenReturn(ended);

        assertThatThrownBy(() -> trainingPlanService.finishTask(1L, 5L, true))
                .isInstanceOf(BizException.class)
                .satisfies(exception -> assertThat(((BizException) exception).getErrorCode().getCode())
                        .isEqualTo(1702));
    }

    /**
     * 勾选任务成功：写入目标状态（幂等）。
     */
    @Test
    void shouldToggleTaskOfActivePlan() {
        TrainingTask task = new TrainingTask();
        task.setId(5L);
        task.setUserId(1L);
        task.setPlanId(6L);
        when(trainingTaskMapper.selectByIdAndUser(5L, 1L)).thenReturn(task);
        TrainingPlan active = new TrainingPlan();
        active.setId(6L);
        active.setStatus(TrainingPlanStatusEnum.ACTIVE.getValue());
        when(trainingPlanMapper.selectByIdAndUser(6L, 1L)).thenReturn(active);

        trainingPlanService.finishTask(1L, 5L, true);
        trainingPlanService.finishTask(1L, 5L, true);

        verify(trainingTaskMapper, times(2)).updateFinished(5L, 1L, 1);
    }

    /**
     * 构造合法的提交结论：每天 2 个任务、每条 30 分钟。
     *
     * @param days 天数
     * @param dailyMinutes 每日时长
     * @param adjustmentReason 调整原因
     * @return 提交结论
     */
    private TrainingPlanSubmitVO buildSubmit(int days, int dailyMinutes, String adjustmentReason) {
        TrainingPlanSubmitVO submit = new TrainingPlanSubmitVO();
        submit.setDays(days);
        submit.setDailyMinutes(dailyMinutes);
        submit.setSummary("先补薄弱点");
        submit.setAdjustmentReason(adjustmentReason);
        List<TrainingTaskSubmitVO> tasks = new ArrayList<>();
        for (int dayIndex = 1; dayIndex <= days; dayIndex++) {
            int perTask = dailyMinutes / 2;
            for (int index = 0; index < 2; index++) {
                TrainingTaskSubmitVO task = new TrainingTaskSubmitVO();
                task.setDayIndex(dayIndex);
                task.setTopic("第 " + dayIndex + " 天主题 " + index);
                task.setQuestionType("八股");
                task.setDifficulty(2);
                task.setDurationMinutes(perTask);
                task.setKnowledgePoint("Redis 分布式锁");
                tasks.add(task);
            }
        }
        submit.setTasks(tasks);
        return submit;
    }
}
