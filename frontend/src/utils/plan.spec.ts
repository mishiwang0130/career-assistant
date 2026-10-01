import { describe, expect, it } from 'vitest'

import type { TrainingPlanRespVO } from '@/types/plan'
import {
  buildPlanCardPreview,
  countRemainingDays,
  describeGenerationProgress,
  isConfirmRequired,
  isGenerationTerminalEvent,
  nextStreamText,
  parsePlanResultEvent,
} from '@/utils/plan'

/** 计划桩数据：一份三天、每天 60 分钟的按天正文计划，没有任务列表。 */
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
  planContent: '第 1 天：Redis 分布式锁\n第 2 天：JVM 内存模型\n第 3 天：MySQL 索引',
  adjustmentReason: null,
  generatedAt: '2026-10-01 09:00',
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

  it('只有 plan_confirm_required 才走确认弹窗分支', () => {
    expect(
      isConfirmRequired({
        type: 'plan_confirm_required',
        message: '',
        draftContent: '第 1 天：Redis',
        existingPlan: {},
      }),
    ).toBe(true)
    expect(isConfirmRequired({ type: 'plan_confirm_rejected', message: '' })).toBe(false)
  })

  it('result 事件按协议取外层 data 里的结构化产物', () => {
    // 冻结协议：data 行是 {"data": <结构化产物>}，不是把产物直接当 data。
    const raw = JSON.stringify({ data: { type: 'training_plan', plan: PLAN } })

    const payload = parsePlanResultEvent(raw)

    expect(payload?.type).toBe('training_plan')
    expect(payload && 'plan' in payload ? payload.plan.planId : null).toBe(9)
    expect(payload && 'plan' in payload ? payload.plan.planContent : null).toContain('第 1 天')
  })

  it('确认草稿与放弃保存的结果都能解析出来', () => {
    const confirmRaw = JSON.stringify({
      data: {
        type: 'plan_confirm_required',
        message: '确认后才会保存',
        draftContent: '第 1 天：Redis 分布式锁',
        existingPlan: { planId: 9 },
      },
    })
    const rejectedRaw = JSON.stringify({ data: { type: 'plan_confirm_rejected', message: '已保留' } })

    const confirmPayload = parsePlanResultEvent(confirmRaw)
    expect(confirmPayload?.type).toBe('plan_confirm_required')
    expect(confirmPayload && 'draftContent' in confirmPayload ? confirmPayload.draftContent : '')
      .toContain('第 1 天')
    expect(parsePlanResultEvent(rejectedRaw)?.type).toBe('plan_confirm_rejected')
  })

  it('非计划结果、空值与非法 JSON 一律返回 null，不抛异常', () => {
    // 助手链路的 result（简历诊断等）不能误当成训练计划结果。
    expect(parsePlanResultEvent(JSON.stringify({ data: { type: 'resume_diagnosis' } }))).toBeNull()
    expect(parsePlanResultEvent(JSON.stringify({ type: 'training_plan' }))).toBeNull()
    expect(parsePlanResultEvent('')).toBeNull()
    expect(parsePlanResultEvent('{oops')).toBeNull()
  })

  it('工具事件翻译成用户能看懂的进度，不暴露工具名', () => {
    expect(describeGenerationProgress('tool', JSON.stringify({
      name: 'get_weak_points',
      status: 'START',
    }))).toBe('正在读取你的薄弱点…')
    expect(describeGenerationProgress('tool', JSON.stringify({
      name: 'submit_training_plan',
      status: 'START',
    }))).toBe('正在整理计划正文…')
    // 工具结束事件不改变进度文案。
    expect(describeGenerationProgress('tool', JSON.stringify({
      name: 'get_weak_points',
      status: 'END',
    }))).toBeNull()
    expect(describeGenerationProgress('thinking', '{"content":"x"}')).toBeNull()
    expect(describeGenerationProgress('tool', '{oops')).toBeNull()
  })

  it('只有 done 与 error 是生成流的终态事件', () => {
    expect(isGenerationTerminalEvent('done')).toBe(true)
    expect(isGenerationTerminalEvent('error')).toBe(true)
    expect(isGenerationTerminalEvent('result')).toBe(false)
    expect(isGenerationTerminalEvent('tool')).toBe(false)
  })

  it('思考增量先累积，正文一开始产出就丢弃思考', () => {
    const first = nextStreamText({ thinking: '', answer: '' }, 'thinking', '{"content":"先看薄弱点"}')
    expect(first).toEqual({ thinking: '先看薄弱点', answer: '' })

    const second = nextStreamText(first!, 'thinking', '{"content":"，再排期"}')
    expect(second).toEqual({ thinking: '先看薄弱点，再排期', answer: '' })

    const third = nextStreamText(second!, 'delta', '{"content":"## 计划\\n第 1 天"}')
    expect(third).toEqual({ thinking: '', answer: '## 计划\n第 1 天' })

    // 正文产出后的思考增量不再补回。
    const fourth = nextStreamText(third!, 'thinking', '{"content":"继续想"}')
    expect(fourth).toEqual({ thinking: '', answer: '## 计划\n第 1 天' })
  })

  it('非文本事件与非法 JSON 不改变生成过程文本', () => {
    expect(nextStreamText({ thinking: 'a', answer: 'b' }, 'tool', '{"name":"get_weak_points"}')).toBeNull()
    expect(nextStreamText({ thinking: 'a', answer: 'b' }, 'delta', '{oops')).toBeNull()
    expect(nextStreamText({ thinking: 'a', answer: 'b' }, 'delta', '{"content":""}')).toBeNull()
  })

  it('计划正文卡片默认只露前几行', () => {
    const content = '第一行\n\n第二行\n第三行\n第四行\n第五行'

    const preview = buildPlanCardPreview(content)

    expect(preview).toContain('第一行')
    expect(preview).toContain('第四行')
    expect(preview).not.toContain('第五行')
    expect(preview.endsWith('……')).toBe(true)
    // 行数少时原样返回，不加省略号。
    expect(buildPlanCardPreview('只有一行')).toBe('只有一行')
    expect(buildPlanCardPreview(null)).toBe('')
  })
})
