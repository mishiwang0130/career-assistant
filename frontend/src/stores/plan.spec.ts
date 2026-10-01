import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import * as planApi from '@/api/plan'
import { usePlanStore } from '@/stores/plan'
import type { TrainingPlanRespVO } from '@/types/plan'

// 接口层整体打桩，单测不依赖网络与后端。
vi.mock('@/api/plan')

/**
 * 构造计划桩数据。
 *
 * @param finished 第一条任务是否已完成
 * @returns 计划
 */
function buildPlan(finished: boolean): TrainingPlanRespVO {
  return {
    hasPlan: true,
    planId: 3,
    status: 'ACTIVE',
    targetPosition: 'Java 后端开发',
    startDate: '2026-10-01',
    endDate: '2026-10-02',
    totalDays: 2,
    dailyMinutes: 60,
    remainingDays: 2,
    summary: '概要',
    adjustmentReason: null,
    generatedAt: '2026-10-01 09:00',
    days: [
      {
        dayIndex: 1,
        taskDate: '2026-10-01',
        totalMinutes: 30,
        finishedCount: finished ? 1 : 0,
        tasks: [
          {
            id: 11,
            planId: 3,
            dayIndex: 1,
            taskDate: '2026-10-01',
            topic: 'Redis 分布式锁',
            questionType: '八股',
            difficulty: 2,
            durationMinutes: 30,
            knowledgePoint: 'Redis 分布式锁',
            sortOrder: 1,
            finished,
            finishTime: null,
          },
        ],
      },
    ],
    todayReminder: {
      id: 5,
      reminderDate: '2026-10-01',
      content: '今天练 Redis 分布式锁',
      read: false,
      readTime: null,
    },
    unreadReminderCount: 1,
  }
}

describe('训练计划状态', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('加载计划时同步未读角标', async () => {
    vi.mocked(planApi.getCurrentPlan).mockResolvedValue(buildPlan(false))
    const store = usePlanStore()

    await store.loadPlan()

    expect(store.plan?.planId).toBe(3)
    expect(store.unreadCount).toBe(1)
    expect(store.progress).toEqual({ total: 1, finished: 0, percent: 0 })
  })

  it('勾选任务先本地更新，落库失败回滚', async () => {
    vi.mocked(planApi.getCurrentPlan).mockResolvedValue(buildPlan(false))
    vi.mocked(planApi.finishTask).mockRejectedValue(new Error('网络异常'))
    const store = usePlanStore()
    await store.loadPlan()

    const success = await store.toggleTask(11, true)

    expect(success).toBe(false)
    expect(store.plan?.days[0].tasks[0].finished).toBe(false)
    expect(store.plan?.days[0].finishedCount).toBe(0)
  })

  it('勾选成功后完成数与进度同步更新', async () => {
    vi.mocked(planApi.getCurrentPlan).mockResolvedValue(buildPlan(false))
    vi.mocked(planApi.finishTask).mockResolvedValue()
    const store = usePlanStore()
    await store.loadPlan()

    await store.toggleTask(11, true)

    expect(store.plan?.days[0].tasks[0].finished).toBe(true)
    expect(store.progress).toEqual({ total: 1, finished: 1, percent: 100 })
  })

  it('进入计划页清零未读角标', async () => {
    vi.mocked(planApi.getCurrentPlan).mockResolvedValue(buildPlan(false))
    vi.mocked(planApi.readAllReminders).mockResolvedValue()
    const store = usePlanStore()
    await store.loadPlan()

    await store.markAllRead()

    expect(store.unreadCount).toBe(0)
    expect(store.plan?.todayReminder?.read).toBe(true)
  })

  it('轻量刷新未读角标失败时保留旧值', async () => {
    vi.mocked(planApi.getUnreadCount).mockRejectedValue(new Error('网络异常'))
    const store = usePlanStore()
    store.applyPlan(buildPlan(false))

    await store.loadUnreadCount()

    expect(store.unreadCount).toBe(1)
  })
})
