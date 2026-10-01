import { describe, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'

import InterviewResultCard from '@/components/chat/InterviewResultCard.vue'
import type { InterviewResult } from '@/types/interview'

/** 面试结果桩数据：一道有评分的题 + 一道评分不可用的题。 */
const RESULT: InterviewResult = {
  type: 'interview_result',
  sessionId: '12',
  questionCount: 8,
  answeredCount: 2,
  correctCount: 0,
  partialCount: 1,
  wrongCount: 0,
  averageScore: 70,
  finished: true,
  items: [
    {
      questionIndex: 1,
      roundNo: 1,
      question: '讲讲线程池参数',
      answer: '核心参数有 corePoolSize',
      outcome: 'PARTIAL',
      outcomeLabel: '答得有遗漏',
      difficulty: 3,
      score: 70,
      comment: '思路对，漏了拒绝策略',
      correctPoints: ['答到了核心参数'],
      missingPoints: ['拒绝策略'],
      wrongPoints: ['把 corePoolSize 说成最大线程数'],
      expressionIssues: ['结论在最后才给'],
      suggestions: ['按「参数 → 流程 → 拒绝策略」三段回答'],
      knowledgePoints: ['线程池参数'],
      referenceAnswer: '1. 七大核心参数… 2. 提交流程… 3. 拒绝策略…',
      evaluated: true,
    },
    {
      questionIndex: 2,
      roundNo: 1,
      question: '讲讲你负责的模块',
      answer: '记不清了',
      outcome: 'PARTIAL',
      outcomeLabel: '答得有遗漏',
      difficulty: 3,
      score: null,
      comment: '评分不可用，本回合按答得有遗漏处理',
      correctPoints: [],
      missingPoints: [],
      wrongPoints: [],
      expressionIssues: [],
      suggestions: [],
      knowledgePoints: [],
      referenceAnswer: null,
      evaluated: false,
    },
  ],
}

/**
 * 挂载结果卡片。
 *
 * 项目没有引入组件测试库，这里用 createApp + jsdom 渲染，保持依赖不新增。
 *
 * @returns 容器
 */
async function mountCard(): Promise<HTMLElement> {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const app = createApp(InterviewResultCard, { result: RESULT })
  app.use(ElementPlus)
  app.mount(container)
  await nextTick()
  return container
}

describe('面试结果卡片（结束时的逐题点评）', () => {
  it('逐题展示答对的点、漏掉的点、说错的地方、表达问题、建议补充与标准答案', async () => {
    const container = await mountCard()
    const text = container.textContent ?? ''

    expect(text).toContain('本场面试结果')
    expect(text).toContain('答对的点')
    expect(text).toContain('答到了核心参数')
    expect(text).toContain('漏掉的点')
    expect(text).toContain('拒绝策略')
    expect(text).toContain('说错的地方')
    expect(text).toContain('表达问题')
    expect(text).toContain('下次这样答')
    expect(text).toContain('标准答案')
    expect(text).toContain('七大核心参数')
  })

  it('评分不可用的题只保留判定与提示，不渲染点评清单', async () => {
    const container = await mountCard()
    const items = container.querySelectorAll('.item')

    expect(items.length).toBe(2)
    expect(items[1].textContent).toContain('没有拿到评分结论')
    expect(items[1].textContent).not.toContain('答对的点')
  })
})
