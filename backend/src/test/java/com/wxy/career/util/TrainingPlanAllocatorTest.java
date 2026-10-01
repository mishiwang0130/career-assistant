package com.wxy.career.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 训练计划分配器测试。
 *
 * <p>固定「按天数与每日时长切分任务」的口径：1 天、大量天、每日时长极短与任务数上限压缩四类边界。
 *
 * @author wxy
 * @date 2026-10-01
 */
class TrainingPlanAllocatorTest {

    /**
     * 每天任务数的取值口径。
     */
    @Test
    void shouldComputeTasksPerDayByDailyMinutes() {
        assertThat(TrainingPlanAllocator.tasksPerDay(5)).isEqualTo(1);
        assertThat(TrainingPlanAllocator.tasksPerDay(10)).isEqualTo(1);
        assertThat(TrainingPlanAllocator.tasksPerDay(20)).isEqualTo(1);
        assertThat(TrainingPlanAllocator.tasksPerDay(45)).isEqualTo(2);
        assertThat(TrainingPlanAllocator.tasksPerDay(60)).isEqualTo(2);
        assertThat(TrainingPlanAllocator.tasksPerDay(90)).isEqualTo(3);
        assertThat(TrainingPlanAllocator.tasksPerDay(180)).isEqualTo(6);
        // 上限固定为 6：再长的每日时长也不会排出十几条任务。
        assertThat(TrainingPlanAllocator.tasksPerDay(600)).isEqualTo(6);
    }

    /**
     * 只排一天：任务时长之和等于当天时长。
     */
    @Test
    void shouldAllocateSingleDay() {
        List<TrainingPlanAllocator.DaySlot> slots = TrainingPlanAllocator.allocate(1, 60, 200);

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).getDayIndex()).isEqualTo(1);
        assertThat(slots.get(0).getTaskCount()).isEqualTo(2);
        assertThat(slots.get(0).getTaskMinutes()).containsExactly(30, 30);
        assertThat(slots.get(0).getTotalMinutes()).isEqualTo(60);
    }

    /**
     * 每天时长极短：一天只排一个任务，时长与输入一致。
     */
    @Test
    void shouldAllocateVeryShortDailyMinutes() {
        List<TrainingPlanAllocator.DaySlot> slots = TrainingPlanAllocator.allocate(7, 10, 200);

        assertThat(slots).hasSize(7);
        for (TrainingPlanAllocator.DaySlot slot : slots) {
            assertThat(slot.getTaskCount()).isEqualTo(1);
            assertThat(slot.getTaskMinutes()).containsExactly(10);
        }
    }

    /**
     * 大量天：任务总数触顶后只给前 N 天排任务，且每天仍至少一条。
     */
    @Test
    void shouldCompressWhenDaysExceedTaskLimit() {
        // 365 天 × 2 条 = 730 条，先把每天压到 1 条，仍超 200 条，于是只排前 200 天。
        List<TrainingPlanAllocator.DaySlot> slots = TrainingPlanAllocator.allocate(365, 60, 200);

        assertThat(slots).hasSize(200);
        assertThat(slots.get(0).getDayIndex()).isEqualTo(1);
        assertThat(slots.get(199).getDayIndex()).isEqualTo(200);
        assertThat(slots).allSatisfy(slot -> assertThat(slot.getTaskCount()).isEqualTo(1));
    }

    /**
     * 任务总数刚好触顶：不压缩每天任务数。
     */
    @Test
    void shouldKeepTasksPerDayWhenWithinLimit() {
        List<TrainingPlanAllocator.DaySlot> slots = TrainingPlanAllocator.allocate(200, 60, 200);

        // 200 天 × 2 条 = 400 条超上限，压到每天 1 条刚好 200 条。
        assertThat(slots).hasSize(200);
        assertThat(slots).allSatisfy(slot -> assertThat(slot.getTaskCount()).isEqualTo(1));
    }

    /**
     * 每天时长较长：每天 6 条任务，均分当天时长。
     */
    @Test
    void shouldSplitLongDailyMinutesEvenly() {
        List<TrainingPlanAllocator.DaySlot> slots = TrainingPlanAllocator.allocate(3, 600, 200);

        assertThat(slots).hasSize(3);
        for (TrainingPlanAllocator.DaySlot slot : slots) {
            assertThat(slot.getTaskCount()).isEqualTo(6);
            assertThat(slot.getTotalMinutes()).isEqualTo(600);
            assertThat(slot.getTaskMinutes()).allSatisfy(minutes -> assertThat(minutes).isEqualTo(100));
        }
    }

    /**
     * 兜底口径：任何输入下当天时长合计都不超过用户填写的每日时长，且每天至少一条任务。
     */
    @Test
    void shouldNeverExceedDailyMinutes() {
        int[] dailyMinutesCases = {10, 11, 15, 25, 35, 45, 60, 75, 90, 125, 180, 240};
        for (int dailyMinutes : dailyMinutesCases) {
            List<TrainingPlanAllocator.DaySlot> slots = TrainingPlanAllocator.allocate(5, dailyMinutes, 200);
            assertThat(slots).hasSize(5);
            for (TrainingPlanAllocator.DaySlot slot : slots) {
                assertThat(slot.getTaskCount()).isGreaterThanOrEqualTo(1);
                assertThat(slot.getTotalMinutes()).isLessThanOrEqualTo(dailyMinutes);
            }
        }
    }

    /**
     * 入参非法时直接拒绝，避免排出空计划。
     */
    @Test
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> TrainingPlanAllocator.allocate(0, 60, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrainingPlanAllocator.allocate(7, 0, 200))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrainingPlanAllocator.allocate(7, 60, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
