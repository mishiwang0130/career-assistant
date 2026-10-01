package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.config.TrainingProperties;
import com.wxy.career.mapper.TrainingPlanMapper;
import com.wxy.career.mapper.TrainingReminderMapper;
import com.wxy.career.mapper.TrainingTaskMapper;
import com.wxy.career.po.TrainingPlan;
import com.wxy.career.po.TrainingTask;
import com.wxy.career.vo.PlannedUsersResultVO;
import com.wxy.career.vo.TrainingReminderSubmitVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 训练提醒服务测试。
 *
 * <p>固定口径：同一天同一用户只落一条（幂等）、没有活跃计划或今天没有任务的用户不提醒、只处理有活跃计划的用户、
 * 目标用户 ID 必须是数字。不连数据库。
 *
 * @author wxy
 * @date 2026-10-01
 */
class TrainingReminderServiceImplTest {

    /**
     * 被测服务。
     */
    private TrainingReminderServiceImpl trainingReminderService;

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
        trainingReminderService = new TrainingReminderServiceImpl();
        ReflectionTestUtils.setField(trainingReminderService, "trainingPlanMapper", trainingPlanMapper);
        ReflectionTestUtils.setField(trainingReminderService, "trainingTaskMapper", trainingTaskMapper);
        ReflectionTestUtils.setField(trainingReminderService, "trainingReminderMapper", trainingReminderMapper);
        ReflectionTestUtils.setField(trainingReminderService, "trainingProperties", new TrainingProperties());
    }

    /**
     * 幂等写入：同一天重复触发只走一次 upsert，第二条不会产生新记录。
     */
    @Test
    void shouldUpsertReminderIdempotently() {
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(buildActivePlan(1L, 9L));
        when(trainingTaskMapper.listByPlanAndDate(eq(1L), eq(9L), any(LocalDate.class)))
                .thenReturn(List.of(buildTask()));

        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天练 Redis 分布式锁"))).isTrue();
        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天练 Redis 分布式锁"))).isTrue();

        // 幂等由 (user_id, reminder_date) 唯一键 + ON DUPLICATE KEY UPDATE 承载，只调用 upsert，不做先查再写。
        verify(trainingReminderMapper, times(2)).upsertDailyReminder(eq(1L), eq(9L), any(LocalDate.class), any());
    }

    /**
     * 用户没有生效计划时不写提醒。
     */
    @Test
    void shouldSkipWhenNoActivePlan() {
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(null);

        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天练一会"))).isFalse();

        verify(trainingReminderMapper, never()).upsertDailyReminder(anyLong(), anyLong(), any(), any());
    }

    /**
     * 计划已结束时同样跳过。
     */
    @Test
    void shouldSkipWhenPlanFinished() {
        TrainingPlan ended = buildActivePlan(1L, 9L);
        ended.setEndDate(LocalDate.now().minusDays(1));
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(ended);

        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天练一会"))).isFalse();

        verify(trainingReminderMapper, never()).upsertDailyReminder(anyLong(), anyLong(), any(), any());
    }

    /**
     * 今天没有任务的用户不提醒。
     */
    @Test
    void shouldSkipWhenNoTaskToday() {
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(buildActivePlan(1L, 9L));
        when(trainingTaskMapper.listByPlanAndDate(eq(1L), eq(9L), any(LocalDate.class))).thenReturn(List.of());

        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天练一会"))).isFalse();

        verify(trainingReminderMapper, never()).upsertDailyReminder(anyLong(), anyLong(), any(), any());
    }

    /**
     * 目标用户 ID 非法时拒绝，避免把提醒写到错的账号上。
     */
    @Test
    void shouldRejectInvalidTargetUser() {
        assertThatThrownBy(() -> trainingReminderService.saveReminder(buildSubmit("abc", "今天练一会")))
                .isInstanceOf(BizException.class);
    }

    /**
     * 只有有活跃计划且今天有任务的用户出现在简报里。
     */
    @Test
    void shouldListOnlyUsersWithTasksToday() {
        when(trainingPlanMapper.listActivePlans(any(LocalDate.class), anyInt()))
                .thenReturn(List.of(buildActivePlan(1L, 9L), buildActivePlan(2L, 10L)));
        when(trainingTaskMapper.listByPlanAndDate(eq(1L), eq(9L), any(LocalDate.class)))
                .thenReturn(List.of(buildTask()));
        when(trainingTaskMapper.listByPlanAndDate(eq(2L), eq(10L), any(LocalDate.class))).thenReturn(List.of());
        when(trainingTaskMapper.countUnfinishedOnDate(eq(1L), eq(9L), any(LocalDate.class))).thenReturn(2L);

        PlannedUsersResultVO result = trainingReminderService.listPlannedUsers();

        assertThat(result.isHasData()).isTrue();
        assertThat(result.getCount()).isEqualTo(1);
        assertThat(result.getUsers().get(0).getUserId()).isEqualTo("1");
        assertThat(result.getUsers().get(0).getYesterdayUnfinishedCount()).isEqualTo(2L);
        assertThat(result.getUsers().get(0).getTodayTasks()).hasSize(1);
    }

    /**
     * 没有用户需要提醒时返回空状态而不是抛错。
     */
    @Test
    void shouldReturnEmptyStateWhenNobodyNeedsReminder() {
        when(trainingPlanMapper.listActivePlans(any(LocalDate.class), anyInt())).thenReturn(List.of());

        PlannedUsersResultVO result = trainingReminderService.listPlannedUsers();

        assertThat(result.isHasData()).isFalse();
        assertThat(result.getCount()).isZero();
        assertThat(result.getMessage()).isNotBlank();
    }

    /**
     * 未读角标与已读操作的读取入口。
     */
    @Test
    void shouldExposeUnreadAndMarkRead() {
        when(trainingReminderMapper.countUnread(1L)).thenReturn(3L);

        assertThat(trainingReminderService.unreadCount(1L)).isEqualTo(3L);

        trainingReminderService.markRead(1L, 7L);
        trainingReminderService.markAllRead(1L);

        verify(trainingReminderMapper).markRead(1L, 7L);
        verify(trainingReminderMapper).markAllRead(1L);
    }

    /**
     * 构造生效计划。
     *
     * @param userId 用户 ID
     * @param planId 计划 ID
     * @return 计划
     */
    private TrainingPlan buildActivePlan(Long userId, Long planId) {
        TrainingPlan plan = new TrainingPlan();
        plan.setId(planId);
        plan.setUserId(userId);
        plan.setStatus("ACTIVE");
        plan.setTargetPosition("Java 后端开发");
        plan.setStartDate(LocalDate.now());
        plan.setEndDate(LocalDate.now().plusDays(3));
        plan.setTotalDays(4);
        plan.setDailyMinutes(60);
        return plan;
    }

    /**
     * 构造一条当天的训练任务。
     *
     * @return 任务
     */
    private TrainingTask buildTask() {
        TrainingTask task = new TrainingTask();
        task.setId(1L);
        task.setTopic("Redis 分布式锁补齐");
        task.setQuestionType("八股");
        task.setDurationMinutes(30);
        task.setKnowledgePoint("Redis 分布式锁");
        return task;
    }

    /**
     * 构造提醒写入参数。
     *
     * @param userId 目标用户 ID
     * @param content 提醒正文
     * @return 写入参数
     */
    private TrainingReminderSubmitVO buildSubmit(String userId, String content) {
        TrainingReminderSubmitVO submit = new TrainingReminderSubmitVO();
        submit.setUserId(userId);
        submit.setContent(content);
        return submit;
    }
}
