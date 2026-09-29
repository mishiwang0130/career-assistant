import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'

import InterviewReportCard from '@/components/chat/InterviewReportCard.vue'
import type { InterviewReportResult } from '@/types/interview'

/** 已完成的报告桩数据。 */
const SUCCEEDED_REPORT: InterviewReportResult = {
  type: 'interview_report',
  sessionId: '12',
  status: 'SUCCEEDED',
  statusLabel: '已完成',
  generatedAt: '2026-09-29 20:10:00',
  summary: '整体答得稳，项目题讲得清楚，基础题深度不够。',
  highlights: ['项目题能结合真实经历'],
  suggestions: ['把线程池参数与拒绝策略捋一遍'],
  wrongItems: [
    {
      questionIndex: 4,
      roundNo: 1,
      question: 'Redis 分布式锁怎么实现？',
      outcome: 'WRONG',
      outcomeLabel: '完全不会或答错',
      comment: '关键结论说错',
      knowledgePoints: ['Redis 分布式锁'],
    },
  ],
  weaknesses: [
    {
      knowledgePoint: 'Redis 分布式锁',
      masteryScore: 40,
      masteryLevel: 'NEEDS_WORK',
      masteryLevelLabel: '待补强',
      lastOutcome: 'WRONG',
      comment: '关键结论说错',
    },
  ],
  mastery: [
    {
      knowledgePoint: 'Redis 分布式锁',
      masteryScore: 40,
      masteryLevel: 'NEEDS_WORK',
      masteryLevelLabel: '待补强',
      weak: true,
      evidenceCount: 1,
      lastOutcome: 'WRONG',
    },
    {
      knowledgePoint: '线程池参数',
      masteryScore: 72,
      masteryLevel: 'BASIC',
      masteryLevelLabel: '基本掌握',
      weak: false,
      evidenceCount: 2,
      lastOutcome: 'PARTIAL',
    },
  ],
  errorMessage: null,
  canRetry: false,
}

/**
 * 挂载报告卡片，返回容器与事件桩。
 *
 * @param report 报告数据
 * @param pollingExhausted 轮询是否超时
 */
async function mountCard(
  report: InterviewReportResult,
  pollingExhausted = false,
): Promise<{ container: HTMLElement; retry: ReturnType<typeof vi.fn>; refresh: ReturnType<typeof vi.fn> }> {
  const retry = vi.fn()
  const refresh = vi.fn()
  const container = document.createElement('div')
  document.body.appendChild(container)
  const app = createApp(InterviewReportCard, {
    report,
    retrying: false,
    pollingExhausted,
    onRetry: retry,
    onRefresh: refresh,
  })
  app.use(ElementPlus)
  app.mount(container)
  await nextTick()
  return { container, retry, refresh }
}

describe('面试报告卡片', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('生成中展示状态说明，轮询超时后给手动刷新入口', async () => {
    const generating: InterviewReportResult = {
      ...SUCCEEDED_REPORT,
      status: 'GENERATING',
      statusLabel: '报告生成中',
      summary: null,
      highlights: [],
      suggestions: [],
    }
    const { container, refresh } = await mountCard(generating, true)
    expect(container.textContent).toContain('报告生成中')
    expect(container.textContent).toContain('报告正在后台生成')

    const button = Array.from(container.querySelectorAll('button')).find((item) =>
      item.textContent?.includes('刷新')
    )
    expect(button).toBeTruthy()
    button?.click()
    await nextTick()
    expect(refresh).toHaveBeenCalled()
  })

  it('失败态展示原因并触发重试', async () => {
    const failed: InterviewReportResult = {
      ...SUCCEEDED_REPORT,
      status: 'FAILED',
      statusLabel: '生成失败',
      summary: null,
      highlights: [],
      suggestions: [],
      errorMessage: '报告生成超时，请重试',
      canRetry: true,
    }
    const { container, retry } = await mountCard(failed)
    expect(container.textContent).toContain('报告生成超时，请重试')

    const button = Array.from(container.querySelectorAll('button')).find((item) =>
      item.textContent?.includes('重试生成')
    )
    expect(button).toBeTruthy()
    button?.click()
    await nextTick()
    expect(retry).toHaveBeenCalled()
  })

  it('完成后展示错题清单、薄弱点清单、掌握度与总结', async () => {
    const { container } = await mountCard(SUCCEEDED_REPORT)
    const text = container.textContent ?? ''

    expect(text).toContain('错题清单')
    expect(text).toContain('Redis 分布式锁')
    expect(text).toContain('薄弱点清单')
    expect(text).toContain('知识点掌握度')
    expect(text).toContain('待补强')
    expect(text).toContain('基本掌握')
    expect(text).toContain('面试总结')
    expect(text).toContain('整体答得稳')
    // 掌握度用进度条呈现，不只是文字
    expect(container.querySelectorAll('.el-progress').length).toBe(2)
  })
})
