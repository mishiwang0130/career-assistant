import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as planApi from '@/api/plan'
import type { TrainingDayRespVO, TrainingPlanRespVO } from '@/types/plan'
import { buildPlanProgress, type TrainingPlanProgress } from '@/utils/plan'

/**
 * 训练计划状态。
 *
 * 只保留计划快照与未读角标：计划页与侧栏入口共用这份状态，勾选任务先本地更新再落库，失败时回滚，
 * 避免整页重新拉取造成闪烁。
 */
export const usePlanStore = defineStore('plan', () => {
  /** 当前计划，null 表示还没加载过。 */
  const plan = ref<TrainingPlanRespVO | null>(null)

  /** 是否正在加载计划。 */
  const loading = ref(false)

  /** 未读提醒数，侧栏角标与计划页共用。 */
  const unreadCount = ref(0)

  /** 计划完成进度。 */
  const progress = computed<TrainingPlanProgress>(() => buildPlanProgress(plan.value))

  /**
   * 拉取当前计划并同步未读角标。
   */
  async function loadPlan(): Promise<void> {
    loading.value = true
    try {
      const loaded = await planApi.getCurrentPlan()
      applyPlan(loaded)
    } finally {
      loading.value = false
    }
  }

  /**
   * 只用轻量接口刷新未读角标（侧栏挂在应用壳上，不需要拉整份计划）。
   */
  async function loadUnreadCount(): Promise<void> {
    try {
      const result = await planApi.getUnreadCount()
      unreadCount.value = result.count ?? 0
      if (plan.value) {
        plan.value.unreadReminderCount = unreadCount.value
      }
    } catch {
      // 角标失败不影响页面，保持上一次的数字。
    }
  }

  /**
   * 勾选 / 取消勾选一条任务。
   *
   * 先本地更新（界面立即响应），落库失败再回滚。
   *
   * @param taskId 任务 ID
   * @param finished 目标状态
   * @returns 是否成功落库
   */
  async function toggleTask(taskId: number, finished: boolean): Promise<boolean> {
    const day = findDayOfTask(taskId)
    if (!day) {
      return false
    }
    const task = day.tasks.find((item) => item.id === taskId)
    if (!task) {
      return false
    }
    const previousFinished = task.finished
    task.finished = finished
    day.finishedCount += finished ? 1 : -1
    try {
      await planApi.finishTask(taskId, finished)
      return true
    } catch {
      task.finished = previousFinished
      day.finishedCount += finished ? -1 : 1
      return false
    }
  }

  /**
   * 清零未读角标：把全部未读提醒标记为已读。
   */
  async function markAllRead(): Promise<void> {
    if (unreadCount.value === 0) {
      return
    }
    try {
      await planApi.readAllReminders()
      unreadCount.value = 0
      if (plan.value) {
        plan.value.unreadReminderCount = 0
        if (plan.value.todayReminder) {
          plan.value.todayReminder.read = true
        }
      }
    } catch {
      // 已读失败不影响阅读，下一次进入计划页会重试。
    }
  }

  /**
   * 应用一份计划快照（首次加载或生成完成后）。
   *
   * @param loaded 计划
   */
  function applyPlan(loaded: TrainingPlanRespVO): void {
    plan.value = loaded
    unreadCount.value = loaded.unreadReminderCount ?? 0
  }

  /**
   * 找到某条任务所在的当天分组。
   *
   * @param taskId 任务 ID
   * @returns 当天分组，找不到时返回 null
   */
  function findDayOfTask(taskId: number): TrainingDayRespVO | null {
    for (const day of plan.value?.days ?? []) {
      if (day.tasks.some((task) => task.id === taskId)) {
        return day
      }
    }
    return null
  }

  /**
   * 退出登录或切换账号时重置，避免下一个账号看到上一个账号的计划与角标。
   */
  function reset(): void {
    plan.value = null
    unreadCount.value = 0
    loading.value = false
  }

  return {
    plan,
    loading,
    unreadCount,
    progress,
    loadPlan,
    loadUnreadCount,
    toggleTask,
    markAllRead,
    applyPlan,
    reset,
  }
})
