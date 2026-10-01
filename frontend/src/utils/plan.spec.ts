import { describe, expect, it } from 'vitest'

import type { TrainingPlanRespVO } from '@/types/plan'
import {
  buildPlanProgress,
  countRemainingDays,
  describeExistingPlan,
  isConfirmRequired,
  summarizePlan,
} from '@/utils/plan'

/** 计划桩数据：两天各两条任务，其中一条已完成。 */
const PLAN: TrainingPlanRespVO = {
  hasPlan: true,
  planId: 9,
  status: 'ACTIVE',
  targetPosition: 'Java 后端开发',
  startDate: '2026-10-01',
  endDate: '2026-10-03',
  totalDays: 3,
  dailyMinutes: 60,
  remainingDays: 3,
  summary: '先补薄弱点',
  adjustmentReason: null,
  generatedAt: '2026-10-01 09:00',
  days: [
    {
      dayIndex: 1,
      taskDate: '2026-10-01',
      totalMinutes: 60,
      finishedCount: 1,
      tasks: [
        {
          id: 1,
          planId: 9,
          dayIndex: 1,
          taskDate: '2026-10-01',
          topic: 'Redis 分布式锁',
          questionType: '八股',
          difficulty: 2,
          durationMinutes: 30,
          knowledgePoint: 'Redis 分布式锁',
          sortOrder: 1,
          finished: true,
          finishTime: '2026-10-01 09:30',
        },
        {
          id: 2,
          planId: 9,
          dayIndex: 1,
          taskDate: '2026-10-01',
          topic: '项目难点复盘',
          questionType: '项目',
          difficulty: 3,
          durationMinutes: 30,
          knowledgePoint: null,
          sortOrder: 2,
          finished: false,
          finishTime: null,
        },
      ],
    },
  ],
  todayReminder: null,
  unreadReminderCount: 2,
}

describe('训练计划工具函数', () => {
  it('剩余天数按截止日期实时算出，并包含当天', () => {
    expect(countRemainingDays('2026-10-03', new Date(2026, 9, 1))).toBe(3)
    expect(countRemainingDays('2026-10-01', new Date(2026, 9, 1))).toBe(1)
  })

  it('截止日期已过时剩余天数为 0', () => {
    expect(countRemainingDays('2026-09-30', new Date(2026, 9, 1))).toBe(0)
  })

  it('跨天后剩余天数自动减少，说明没有落库缓存', () => {
    expect(countRemainingDays('2026-10-10', new Date(2026, 9, 1))).toBe(10)
    expect(countRemainingDays('2026-10-10', new Date(2026, 9, 2))).toBe(9)
  })

  it('日期非法时按 0 处理，不抛异常', () => {
    expect(countRemainingDays(null, new Date(2026, 9, 1))).toBe(0)
    expect(countRemainingDays('今天', new Date(2026, 9, 1))).toBe(0)
  })

  it('完成进度按任务条数统计', () => {
    expect(buildPlanProgress(PLAN)).toEqual({ total: 2, finished: 1, percent: 50 })
    expect(buildPlanProgress(null)).toEqual({ total: 0, finished: 0, percent: 0 })
  })

  it('计划汇总给出任务数与总时长', () => {
    expect(summarizePlan(PLAN)).toEqual({ totalTasks: 2, totalMinutes: 60 })
  })

  it('只有 plan_confirm_required 才走确认弹窗分支', () => {
    expect(
      isConfirmRequired({ type: 'plan_confirm_required', message: '', existingPlan: {} }),
    ).toBe(true)
    expect(isConfirmRequired({ type: 'plan_confirm_rejected', message: '' })).toBe(false)
  })

  it('确认文案带上目标岗位与进度', () => {
    const text = describeExistingPlan({
      planId: 9,
      targetPosition: 'Java 后端开发',
      remainingDays: 4,
      totalTasks: 8,
      finishedTasks: 3,
    })
    expect(text).toContain('Java 后端开发')
    expect(text).toContain('剩余 4 天')
    expect(text).toContain('3/8')
  })
})
