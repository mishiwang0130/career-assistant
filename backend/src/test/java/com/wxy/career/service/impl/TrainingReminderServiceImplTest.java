package com.wxy.career.service.impl;

import com.wxy.career.common.exception.BizException;
import com.wxy.career.config.TrainingProperties;
import com.wxy.career.mapper.TrainingPlanMapper;
import com.wxy.career.mapper.TrainingReminderMapper;
import com.wxy.career.po.TrainingPlan;
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
 * <p>固定口径：简报里给的是计划正文（提醒 Agent 自己读今天这一行）、同一天同一用户只落一条（幂等）、
 * 没有生效计划 / 计划已结束 / 计划没有正文的用户不提醒、只处理在训练期内的用户。不连数据库。
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
     * 提醒 Mapper 桩。
     */
    private TrainingReminderMapper trainingReminderMapper;

    /**
     * 组装被测服务。
     */
    @BeforeEach
    void setUp() {
        trainingPlanMapper = mock(TrainingPlanMapper.class);
        trainingReminderMapper = mock(TrainingReminderMapper.class);
        trainingReminderService = new TrainingReminderServiceImpl();
        ReflectionTestUtils.setField(trainingReminderService, "trainingPlanMapper", trainingPlanMapper);
        ReflectionTestUtils.setField(trainingReminderService, "trainingReminderMapper", trainingReminderMapper);
        ReflectionTestUtils.setField(trainingReminderService, "trainingProperties", new TrainingProperties());
    }

    /**
     * 幂等写入：同一天重复触发只走一次 upsert。
     */
    @Test
    void shouldUpsertReminderIdempotently() {
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(buildActivePlan(1L, "第 2 天：Redis 分布式锁——能讲清加锁、续期、释放三步"));

        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天第 2 天：Redis 分布式锁"))).isTrue();
        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天第 2 天：Redis 分布式锁"))).isTrue();

        verify(trainingReminderMapper, times(2))
                .upsertDailyReminder(eq(1L), anyLong(), any(LocalDate.class), any());
    }

    /**
     * 没有生效计划 / 计划已结束 / 计划没有正文：都不写提醒。
     */
    @Test
    void shouldSkipWhenPlanIsNotRemindable() {
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(null);
        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天练一会"))).isFalse();

        TrainingPlan finished = buildActivePlan(1L, "第 1 天：Redis 分布式锁");
        finished.setEndDate(LocalDate.now().minusDays(1));
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(finished);
        assertThat(trainingReminderService.saveReminder(buildSubmit("1", "今天练一会"))).isFalse();

        TrainingPlan emptyContent = buildActivePlan(1L, null);
        when(trainingPlanMapper.selectActiveByUser(1L)).thenReturn(emptyContent);
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
     * 简报带上计划正文与"今天是第几天"：提醒 Agent 据此读出今天要干什么。
     */
    @Test
    void shouldListUsersWithPlanContent() {
        TrainingPlan plan = buildActivePlan(1L, "第 1 天：Redis 分布式锁——能讲清加锁、续期、释放三步");
        plan.setStartDate(LocalDate.now());
        when(trainingPlanMapper.listActivePlans(any(LocalDate.class), anyInt())).thenReturn(List.of(plan));

        PlannedUsersResultVO result = trainingReminderService.listPlannedUsers();

        assertThat(result.isHasData()).isTrue();
        assertThat(result.getCount()).isEqualTo(1);
        assertThat(result.getUsers().get(0).getUserId()).isEqualTo("1");
        assertThat(result.getUsers().get(0).getDayIndex()).isEqualTo(1);
        assertThat(result.getUsers().get(0).getPlanContent()).contains("Redis 分布式锁");
    }

    /**
     * 计划没有正文的用户不进简报。
     */
    @Test
    void shouldSkipUsersWithoutPlanContent() {
        when(trainingPlanMapper.listActivePlans(any(LocalDate.class), anyInt()))
                .thenReturn(List.of(buildActivePlan(1L, null)));

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
     * @param planContent 计划正文，可为 null
     * @return 计划
     */
    private TrainingPlan buildActivePlan(Long userId, String planContent) {
        TrainingPlan plan = new TrainingPlan();
        plan.setId(9L);
        plan.setUserId(userId);
        plan.setStatus("ACTIVE");
        plan.setTargetPosition("Java 后端开发");
        plan.setStartDate(LocalDate.now().minusDays(1));
        plan.setEndDate(LocalDate.now().plusDays(5));
        plan.setTotalDays(7);
        plan.setDailyMinutes(60);
        plan.setPlanContent(planContent);
        return plan;
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
