import { describe, expect, it } from 'vitest'

import {
  findLatestProgress,
  findLatestReport,
  findLatestResult,
  formatDifficulty,
  formatInterviewProgress,
} from '@/utils/interview'
import type {
  InterviewProgressResult,
  InterviewReportResult,
  InterviewResult,
} from '@/types/interview'

/**
 * 构造一份面试结果桩数据（含逐题点评字段）。
 *
 * @returns 面试结果
 */
function buildResult(): InterviewResult {
  return {
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
}

/**
 * 构造一份面试报告状态桩数据。
 *
 * @param status 报告状态
 * @returns 报告状态
 */
function buildReport(status: InterviewReportResult['status']): InterviewReportResult {
  return {
    type: 'interview_report',
    sessionId: '12',
    status,
    statusLabel: status === 'GENERATING' ? '报告生成中' : '已完成',
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
}

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
    const result = buildResult()

    expect(findLatestResult([])).toBeNull()
    expect(findLatestResult([{ result: null }])).toBeNull()
    expect(findLatestResult([{ result }])).toEqual(result)
  })

  it('一轮下发多个结果时按类型各取最近的：结果在前、报告状态在后也不互相覆盖', () => {
    const result = buildResult()
    const report = buildReport('GENERATING')

    expect(findLatestProgress([{ result: null, results: [] }])).toBeNull()
    expect(findLatestResult([{ result: report, results: [result, report] }])).toEqual(result)
    expect(findLatestReport([{ result: report, results: [result, report] }])).toEqual(report)
  })

  it('取最近一次面试报告状态，未下发时为空', () => {
    const report = buildReport('GENERATING')

    expect(findLatestReport([{ result: null, results: [] }])).toBeNull()
    expect(findLatestReport([{ result: report, results: [report] }])).toEqual(report)
  })
})
