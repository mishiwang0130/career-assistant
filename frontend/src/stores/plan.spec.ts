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

  it('生成流的 result 事件按协议解析后写回计划，页面随即有内容', () => {
    const store = usePlanStore()
    const event = JSON.stringify({ data: { type: 'training_plan', plan: buildPlan(false) } })

    const payload = store.applyGenerationEvent('result', event)

    expect(payload?.type).toBe('training_plan')
    // 这条断言正是「F12 里能看到结果事件、页面却什么都没有」的回归保护：状态必须被写回。
    expect(store.plan?.planId).toBe(3)
    expect(store.plan?.days[0].tasks[0].topic).toBe('Redis 分布式锁')
    expect(store.progress).toEqual({ total: 1, finished: 0, percent: 0 })
  })

  it('覆盖确认与放弃覆盖不写回计划内容，只回传结果类型', () => {
    const store = usePlanStore()
    const confirmEvent = JSON.stringify({
      data: { type: 'plan_confirm_required', message: '已有计划', existingPlan: { planId: 3 } },
    })
    const rejectedEvent = JSON.stringify({ data: { type: 'plan_confirm_rejected', message: '已保留' } })

    expect(store.applyGenerationEvent('result', confirmEvent)?.type).toBe('plan_confirm_required')
    expect(store.applyGenerationEvent('result', rejectedEvent)?.type).toBe('plan_confirm_rejected')
    expect(store.plan).toBeNull()
  })

  it('非 result 事件与非法 JSON 一律忽略，状态不受影响', () => {
    const store = usePlanStore()
    store.applyPlan(buildPlan(false))

    expect(store.applyGenerationEvent('delta', '{"content":"x"}')).toBeNull()
    expect(store.applyGenerationEvent('result', '{oops')).toBeNull()
    expect(store.plan?.planId).toBe(3)
  })
})
