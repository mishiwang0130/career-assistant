import { describe, expect, it } from 'vitest'

import type { ResumeDiagnosisResult } from '@/types/assistant'
import { buildOptimizedResumeTitle, toDiagnosisCardModel } from '@/utils/diagnosis'

/** 诊断结论桩数据。 */
const DIAGNOSIS: ResumeDiagnosisResult = {
  type: 'resume_diagnosis',
  resumeId: 5,
  resumeTitle: 'Java 开发简历',
  overallScore: 72,
  scoreSummary: '扣分主要来自项目成果缺少量化',
  dimensions: [
    { name: '结构与排版', score: 80, comment: '层级清楚' },
    { name: '内容完整度', score: 70, comment: '项目缺时间' },
    { name: '成果与量化', score: 60, comment: '几乎没有数字' },
    { name: '表达专业性', score: 78, comment: '动词偏笼统' },
  ],
  problems: [{ problem: '项目缺量化', location: '项目经历', reason: '看不出影响', suggestion: '补数字', severity: 'HIGH' }],
  highlights: [{ point: '技术栈集中', reason: '与目标岗位一致' }],
  suggestions: [{ priority: 1, content: '重写项目描述' }],
  optimizedResume: '优化后的简历正文',
  interviewFollowUps: ['点一', '点二', '点三'],
}

describe('简历诊断卡片展示模型', () => {
  it('保留综合得分、至少四个维度与优化后正文，并派生另存标题', () => {
    const model = toDiagnosisCardModel(DIAGNOSIS)

    expect(model.overallScore).toBe(72)
    expect(model.dimensions).toHaveLength(4)
    expect(model.optimizedResume).toBe('优化后的简历正文')
    expect(model.interviewFollowUps).toHaveLength(3)
    expect(model.saveAsTitle).toBe('Java 开发简历-优化版')
  })

  it('列表字段缺失时兜底为空数组，卡片不会整块渲染失败', () => {
    const model = toDiagnosisCardModel({
      ...DIAGNOSIS,
      dimensions: undefined as unknown as ResumeDiagnosisResult['dimensions'],
      problems: undefined as unknown as ResumeDiagnosisResult['problems'],
      interviewFollowUps: undefined as unknown as ResumeDiagnosisResult['interviewFollowUps'],
    })

    expect(model.dimensions).toEqual([])
    expect(model.problems).toEqual([])
    expect(model.interviewFollowUps).toEqual([])
  })

  it('另存标题超长时截断到 100 字，标题为空时退回默认文案', () => {
    expect(buildOptimizedResumeTitle('简'.repeat(120))).toHaveLength(100)
    expect(buildOptimizedResumeTitle('  ')).toBe('优化版简历')
  })
})
