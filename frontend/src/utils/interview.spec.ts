import { describe, expect, it } from 'vitest'

import { findLatestProgress, formatDifficulty, formatInterviewProgress } from '@/utils/interview'
import type { InterviewProgressResult } from '@/types/interview'

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
})
