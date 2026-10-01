import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import * as planApi from '@/api/plan'
import { usePlanStore } from '@/stores/plan'
import type { TrainingPlanRespVO } from '@/types/plan'

// 接口层整体打桩，单测不依赖网络与后端。
vi.mock('@/api/plan')

/**
 * 构造计划桩数据：一份按天正文的计划，没有任务列表。
 *
 * @returns 计划
 */
function buildPlan(): TrainingPlanRespVO {
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
    planContent: '第 1 天：Redis 分布式锁\n第 2 天：JVM 内存模型',
    adjustmentReason: null,
    generatedAt: '2026-10-01 09:00',
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
    vi.mocked(planApi.getCurrentPlan).mockResolvedValue(buildPlan())
    const store = usePlanStore()

    await store.loadPlan()

    expect(store.plan?.planId).toBe(3)
    expect(store.unreadCount).toBe(1)
    expect(store.hasPlan).toBe(true)
    expect(store.plan?.planContent).toContain('第 1 天')
  })

  it('进入计划页清零未读角标', async () => {
    vi.mocked(planApi.getCurrentPlan).mockResolvedValue(buildPlan())
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
    store.applyPlan(buildPlan())

    await store.loadUnreadCount()

    expect(store.unreadCount).toBe(1)
  })

  it('生成流的 result 事件按协议解析后写回计划，页面随即有内容', () => {
    const store = usePlanStore()
    const event = JSON.stringify({ data: { type: 'training_plan', plan: buildPlan() } })

    const payload = store.applyGenerationEvent('result', event)

    expect(payload?.type).toBe('training_plan')
    // 这条断言正是「F12 里能看到结果事件、页面却什么都没有」的回归保护：状态必须被写回。
    expect(store.plan?.planId).toBe(3)
    expect(store.plan?.planContent).toContain('Redis 分布式锁')
  })

  it('待确认与放弃保存都不写回计划内容，只回传结果类型', () => {
    const store = usePlanStore()
    const confirmEvent = JSON.stringify({
      data: {
        type: 'plan_confirm_required',
        message: '确认后才会保存',
        draftContent: '第 1 天：Redis 分布式锁',
        existingPlan: { planId: 3 },
      },
    })
    const rejectedEvent = JSON.stringify({ data: { type: 'plan_confirm_rejected', message: '已保留' } })

    const confirm = store.applyGenerationEvent('result', confirmEvent)

    expect(confirm?.type).toBe('plan_confirm_required')
    expect(confirm && 'draftContent' in confirm ? confirm.draftContent : '').toContain('第 1 天')
    expect(store.applyGenerationEvent('result', rejectedEvent)?.type).toBe('plan_confirm_rejected')
    // 未确认就不该有任何计划被写进状态：确认前一个字都没保存。
    expect(store.plan).toBeNull()
  })

  it('非 result 事件与非法 JSON 一律忽略，状态不受影响', () => {
    const store = usePlanStore()
    store.applyPlan(buildPlan())

    expect(store.applyGenerationEvent('delta', '{"content":"x"}')).toBeNull()
    expect(store.applyGenerationEvent('result', '{oops')).toBeNull()
    expect(store.plan?.planId).toBe(3)
  })

  it('退出登录重置计划与角标', () => {
    const store = usePlanStore()
    store.applyPlan(buildPlan())

    store.reset()

    expect(store.plan).toBeNull()
    expect(store.unreadCount).toBe(0)
    expect(store.hasPlan).toBe(false)
  })
})
