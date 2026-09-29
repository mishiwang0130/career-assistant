import { describe, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'

import InterviewEvaluationCard from '@/components/chat/InterviewEvaluationCard.vue'
import type { InterviewEvaluationResult } from '@/types/interview'

/** 逐题点评桩数据。 */
const EVALUATION: InterviewEvaluationResult = {
  type: 'interview_evaluation',
  sessionId: '12',
  questionIndex: 2,
  roundNo: 1,
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
}

/**
 * 挂载点评卡片。
 *
 * 项目没有引入组件测试库，这里用 createApp + jsdom 渲染，保持依赖不新增。
 *
 * @param evaluation 点评数据
 * @returns 容器
 */
async function mountCard(evaluation: InterviewEvaluationResult): Promise<HTMLElement> {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const app = createApp(InterviewEvaluationCard, { evaluation })
  app.use(ElementPlus)
  app.mount(container)
  await nextTick()
  return container
}

describe('面试点评卡片', () => {
  it('展示正确点、遗漏点、错误点、表达问题、建议补充与标准答案入口', async () => {
    const container = await mountCard(EVALUATION)
    const text = container.textContent ?? ''

    expect(text).toContain('第 2 题点评')
    expect(text).toContain('答得有遗漏')
    expect(text).toContain('答对的点')
    expect(text).toContain('答到了核心参数')
    expect(text).toContain('漏掉的点')
    expect(text).toContain('拒绝策略')
    expect(text).toContain('说错的地方')
    expect(text).toContain('表达问题')
    expect(text).toContain('建议补充')
    // 标准答案走折叠面板：默认收起，需要时展开
    expect(text).toContain('看标准答案')
    expect(container.querySelector('.el-collapse')).toBeTruthy()
  })

  it('评分不可用时只保留判定，并提示没有评分结论', async () => {
    const container = await mountCard({
      ...EVALUATION,
      score: null,
      evaluated: false,
      correctPoints: [],
      missingPoints: [],
      wrongPoints: [],
      expressionIssues: [],
      suggestions: [],
      referenceAnswer: null,
    })
    const text = container.textContent ?? ''

    expect(text).toContain('没有拿到评分结论')
    expect(text).not.toContain('答对的点')
  })
})
