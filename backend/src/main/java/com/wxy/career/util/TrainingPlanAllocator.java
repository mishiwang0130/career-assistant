package com.wxy.career.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 训练计划分配器（纯函数，可单测）。
 *
 * <p>把「还有几天、每天能练多久」翻译成按天的任务时长骨架：每天几个任务、每个任务多少分钟。骨架先给模型，
 * 模型只负责填主题、题型、难度与知识点，因此时长切分是确定性的，不会出现「每天排 5 小时」这类不落地的计划。
 *
 * <p>口径与 {@code training-planning} 技能一致，**以本类为准**（技能里写的是同一套数值说明）：
 *
 * <ol>
 *   <li>每天任务数 = {@code clamp(round(dailyMinutes / 30), 1, 6)}；每日时长不超过 10 分钟时固定 1 个任务；</li>
 *   <li>单任务时长按 5 分钟取整，同一天各任务时长之和不超过 {@code dailyMinutes}；</li>
 *   <li>任务总数上限为 {@code maxTasks}：先减少每天任务数（最少 1 个），仍超出时只给前 {@code maxTasks} 天排任务；</li>
 *   <li>剩余分钟优先补给当天的第一个任务（优先练最薄弱主题），单个任务最长 120 分钟，超出部分顺延给后面的任务。</li>
 * </ol>
 *
 * @author wxy
 * @date 2026-10-01
 */
public final class TrainingPlanAllocator {

    /**
     * 单任务时长取整粒度（分钟）。
     */
    public static final int MINUTE_STEP = 5;

    /**
     * 每天任务数上限。
     */
    public static final int MAX_TASKS_PER_DAY = 6;

    /**
     * 单个任务的最长时长（分钟）。
     */
    public static final int MAX_TASK_MINUTES = 120;

    /**
     * 「每天任务数 = 每日时长 / 该值」的口径基数（分钟）。
     */
    private static final int MINUTES_PER_TASK_BASE = 30;

    /**
     * 每日时长不超过该值时固定排 1 个任务。
     */
    private static final int SHORT_DAY_MINUTES = 10;

    /**
     * 工具类禁止实例化。
     */
    private TrainingPlanAllocator() {
    }

    /**
     * 按天分配任务时长骨架。
     *
     * @param days 计划总天数，「还有几天」，至少 1
     * @param dailyMinutes 每天可练时长（分钟），至少 1
     * @param maxTasks 任务总数上限，至少 1
     * @return 按天排列的骨架，元素顺序即第 1..N 天
     */
    public static List<DaySlot> allocate(int days, int dailyMinutes, int maxTasks) {
        if (days < 1 || dailyMinutes < 1 || maxTasks < 1) {
            throw new IllegalArgumentException("计划天数、每日时长与任务数上限都必须大于 0");
        }
        int tasksPerDay = tasksPerDay(dailyMinutes);
        // 任务总数超上限时先压缩每天任务数；每天至少要有一个任务，压到 1 个仍超出则只给前 maxTasks 天排任务。
        while (tasksPerDay > 1 && (long) tasksPerDay * days > maxTasks) {
            tasksPerDay--;
        }
        int scheduledDays = (int) Math.min(days, maxTasks);
        List<DaySlot> slots = new ArrayList<>(scheduledDays);
        for (int dayIndex = 1; dayIndex <= scheduledDays; dayIndex++) {
            slots.add(new DaySlot(dayIndex, splitMinutes(dailyMinutes, tasksPerDay)));
        }
        return slots;
    }

    /**
     * 计算每天的任务数。
     *
     * @param dailyMinutes 每天可练时长（分钟）
     * @return 每天任务数，取值 1..{@link #MAX_TASKS_PER_DAY}
     */
    public static int tasksPerDay(int dailyMinutes) {
        if (dailyMinutes <= SHORT_DAY_MINUTES) {
            return 1;
        }
        int tasks = Math.round((float) dailyMinutes / MINUTES_PER_TASK_BASE);
        return Math.max(1, Math.min(MAX_TASKS_PER_DAY, tasks));
    }

    /**
     * 把一天的时长切成若干任务时长。
     *
     * <p>先均分到 5 分钟粒度，再把剩余分钟从第一个任务开始按 5 分钟一档补，单个任务不超过 120 分钟，
     * 保证各任务时长之和恰好等于当天的总时长。
     *
     * @param dailyMinutes 当天总时长（分钟）
     * @param taskCount 当天任务数
     * @return 每个任务的时长（分钟），长度等于 {@code taskCount}
     */
    private static List<Integer> splitMinutes(int dailyMinutes, int taskCount) {
        // 只排一个任务时整段时间都给它（每日时长短于取整粒度时也保持「合计 = 用户输入」）。
        if (taskCount == 1) {
            return new ArrayList<>(List.of(Math.min(dailyMinutes, MAX_TASK_MINUTES)));
        }
        int base = dailyMinutes / taskCount;
        base = Math.max(MINUTE_STEP, base - base % MINUTE_STEP);
        // 均分后可能超过当天总时长（例如 11 分钟切 2 个任务），此时退到最小档位。
        while (base * taskCount > dailyMinutes && base > MINUTE_STEP) {
            base -= MINUTE_STEP;
        }
        List<Integer> minutes = new ArrayList<>(taskCount);
        for (int index = 0; index < taskCount; index++) {
            minutes.add(base);
        }
        int remaining = dailyMinutes - base * taskCount;
        int index = 0;
        while (remaining >= MINUTE_STEP) {
            int slot = index % taskCount;
            if (minutes.get(slot) + MINUTE_STEP <= MAX_TASK_MINUTES) {
                minutes.set(slot, minutes.get(slot) + MINUTE_STEP);
                remaining -= MINUTE_STEP;
            } else if (allSlotsFull(minutes)) {
                // 所有任务都到上限：剩余分钟留空（模型不会再排任务），保证总时长不超预算。
                break;
            }
            index++;
        }
        // 不足一个档位的零头补给第一个任务，保证「当天时长合计 = 用户输入」。
        if (remaining > 0 && minutes.get(0) + remaining <= MAX_TASK_MINUTES) {
            minutes.set(0, minutes.get(0) + remaining);
        }
        return minutes;
    }

    /**
     * 判断当天所有任务是否都已到时长上限。
     *
     * @param minutes 当天任务时长
     * @return true 表示都已到上限
     */
    private static boolean allSlotsFull(List<Integer> minutes) {
        for (Integer minute : minutes) {
            if (minute < MAX_TASK_MINUTES) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一天的任务时长骨架。
     *
     * @author wxy
     * @date 2026-10-01
     */
    public static final class DaySlot {

        /**
         * 第几天，从 1 开始。
         */
        private final int dayIndex;

        /**
         * 当天每个任务的时长（分钟）。
         */
        private final List<Integer> taskMinutes;

        /**
         * 构造一天的任务骨架。
         *
         * @param dayIndex 第几天
         * @param taskMinutes 当天每个任务的时长（分钟）
         */
        DaySlot(int dayIndex, List<Integer> taskMinutes) {
            this.dayIndex = dayIndex;
            this.taskMinutes = List.copyOf(taskMinutes);
        }

        /**
         * 获取第几天。
         *
         * @return 第几天，从 1 开始
         */
        public int getDayIndex() {
            return dayIndex;
        }

        /**
         * 获取当天每个任务的时长。
         *
         * @return 任务时长列表（分钟）
         */
        public List<Integer> getTaskMinutes() {
            return taskMinutes;
        }

        /**
         * 获取当天任务数。
         *
         * @return 任务数
         */
        public int getTaskCount() {
            return taskMinutes.size();
        }

        /**
         * 获取当天时长合计。
         *
         * @return 时长合计（分钟）
         */
        public int getTotalMinutes() {
            return taskMinutes.stream().mapToInt(Integer::intValue).sum();
        }
    }
}
