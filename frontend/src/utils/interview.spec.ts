import { describe, expect, it } from 'vitest'

import {
  findLatestProgress,
  findLatestResult,
  formatDifficulty,
  formatInterviewProgress,
} from '@/utils/interview'
import type { InterviewProgressResult, InterviewResult } from '@/types/interview'

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
})
