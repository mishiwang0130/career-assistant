import { describe, expect, it } from 'vitest'

import {
  findLatestEvaluation,
  findLatestProgress,
  findLatestReport,
  findLatestResult,
  formatDifficulty,
  formatInterviewProgress,
} from '@/utils/interview'
import type {
  InterviewEvaluationResult,
  InterviewProgressResult,
  InterviewReportResult,
  InterviewResult,
} from '@/types/interview'

/**
 * 面试进度与难度展示口径的单测。
 */
describe('interview utils', () => {
  it('进度文案按「第 n 题 / 共 N 题」输出', () => {
    expect(formatInterviewProgress({ questionIndex: 3, questionCount: 8 })).toBe('第 3 题 / 共 8 题')
  })

  it('难度文案收敛到 L1-L5', () => {
    expect(formatDifficulty(1)).toBe('L1')
    expect(formatDifficulty(3)).toBe('L3')
    expect(formatDifficulty(5)).toBe('L5')
    expect(formatDifficulty(0)).toBe('L1')
    expect(formatDifficulty(9)).toBe('L5')
    expect(formatDifficulty(2.6)).toBe('L2')
  })

  it('取最近一次面试进度，忽略其它类型的结构化结果', () => {
    const older: InterviewProgressResult = {
      type: 'interview_progress',
      sessionId: '12',
      questionIndex: 1,
      questionCount: 8,
      difficulty: 3,
      roundNo: 1,
      finished: false,
    }
    const latest: InterviewProgressResult = { ...older, questionIndex: 2, difficulty: 4 }

    expect(findLatestProgress([])).toBeNull()
    expect(
      findLatestProgress([{ result: null }, { result: older }, { result: null }]),
    ).toEqual(older)
    expect(findLatestProgress([{ result: older }, { result: latest }])).toEqual(latest)
  })

  it('取最近一次面试结果，结果接口回放与流内下发共用同一份结构', () => {
    const result: InterviewResult = {
      type: 'interview_result',
      sessionId: '12',
      questionCount: 8,
      answeredCount: 1,
      correctCount: 0,
      partialCount: 1,
      wrongCount: 0,
      averageScore: 55,
      finished: true,
      items: [
        {
          questionIndex: 1,
          roundNo: 1,
          question: '讲讲 HashMap',
          answer: '数组加链表',
          outcome: 'PARTIAL',
          outcomeLabel: '答得有遗漏',
          difficulty: 3,
          score: 55,
          comment: '漏了红黑树转换条件',
          correctPoints: ['数组加链表'],
          missingPoints: ['链表转红黑树的阈值'],
          wrongPoints: [],
          expressionIssues: [],
          suggestions: ['补上扩容与树化条件'],
          knowledgePoints: ['HashMap'],
          referenceAnswer: '数组+链表+红黑树；链表长度>8 且容量≥64 时树化；扩容翻倍。',
          evaluated: true,
        },
      ],
    }

    expect(findLatestResult([])).toBeNull()
    expect(findLatestResult([{ result: null }])).toBeNull()
    expect(findLatestResult([{ result }])).toEqual(result)
  })

  it('取最近一次逐题点评：一轮里有多个结果时从 results 数组里按类型挑', () => {
    const evaluation: InterviewEvaluationResult = {
      type: 'interview_evaluation',
      sessionId: '12',
      questionIndex: 2,
      roundNo: 1,
      outcome: 'PARTIAL',
      outcomeLabel: '答得有遗漏',
      difficulty: 3,
      score: 70,
      comment: '漏了拒绝策略',
      correctPoints: ['答到了核心参数'],
      missingPoints: ['拒绝策略'],
      wrongPoints: [],
      expressionIssues: [],
      suggestions: ['按三段回答'],
      knowledgePoints: ['线程池参数'],
      referenceAnswer: '七大核心参数…',
      evaluated: true,
    }
    const progress: InterviewProgressResult = {
      type: 'interview_progress',
      sessionId: '12',
      questionIndex: 2,
      questionCount: 8,
      difficulty: 3,
      roundNo: 1,
      finished: false,
    }

    expect(findLatestEvaluation([])).toBeNull()
    expect(findLatestEvaluation([{ result: progress, results: [progress] }])).toBeNull()
    // 一轮里点评在前、进度在后：只留最后一个 result 会丢掉点评，数组能把两个都留下
    expect(
      findLatestEvaluation([{ result: progress, results: [evaluation, progress] }]),
    ).toEqual(evaluation)
    expect(findLatestProgress([{ result: progress, results: [evaluation, progress] }])).toEqual(progress)
  })

  it('取最近一次面试报告状态，未下发时为空', () => {
    const report: InterviewReportResult = {
      type: 'interview_report',
      sessionId: '12',
      status: 'GENERATING',
      statusLabel: '报告生成中',
      generatedAt: null,
      summary: null,
      highlights: [],
      suggestions: [],
      wrongItems: [],
      weaknesses: [],
      mastery: [],
      errorMessage: null,
      canRetry: false,
    }

    expect(findLatestReport([{ result: null, results: [] }])).toBeNull()
    expect(findLatestReport([{ result: report, results: [report] }])).toEqual(report)
  })
})
