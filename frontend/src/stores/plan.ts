import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as planApi from '@/api/plan'
import type { TrainingPlanRespVO, TrainingPlanResultPayload } from '@/types/plan'
import { parsePlanResultEvent } from '@/utils/plan'

/**
 * 训练计划状态。
 *
 * 只保留计划快照与未读角标：计划页与侧栏入口共用这份状态。计划没有任务表——正文就是按天的「今天练什么知识点」，
 * 因此这里没有勾选/进度这类状态。
 */
export const usePlanStore = defineStore('plan', () => {
  /** 当前计划，null 表示还没加载过。 */
  const plan = ref<TrainingPlanRespVO | null>(null)

  /** 是否正在加载计划。 */
  const loading = ref(false)

  /** 未读提醒数，侧栏角标与计划页共用。 */
  const unreadCount = ref(0)

  /** 是否有生效中的计划。 */
  const hasPlan = computed(() => Boolean(plan.value?.hasPlan))

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
   * 处理生成流的 `result` 事件：命中「计划已保存」时把计划写回状态。
   *
   * 解析口径固定在 {@link parsePlanResultEvent}（data 行是 `{"data": 结构化产物}`）。
   *
   * @param event 事件名
   * @param data 事件的 data 行
   * @returns 训练计划结果载荷，不是计划结果时返回 null
   */
  function applyGenerationEvent(event: string, data: string): TrainingPlanResultPayload | null {
    if (event !== 'result') {
      return null
    }
    const payload = parsePlanResultEvent(data)
    if (!payload) {
      return null
    }
    if (payload.type === 'training_plan') {
      applyPlan(payload.plan)
    }
    return payload
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
    hasPlan,
    loadPlan,
    loadUnreadCount,
    markAllRead,
    applyPlan,
    applyGenerationEvent,
    reset,
  }
})
