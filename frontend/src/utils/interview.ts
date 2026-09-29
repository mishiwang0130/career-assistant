import type { AssistantResultPayload } from '@/types/assistant'
import type { InterviewProgressResult, InterviewStateRespVO } from '@/types/interview'

/**
 * 面试进度与难度的展示口径。
 *
 * 顶部进度条与后续可能出现的复盘视图都用同一套文案，避免各处自己拼字符串。
 */

/** 难度等级上限，与后端一致。 */
export const MAX_DIFFICULTY = 5

/**
 * 开场指令：点「模拟面试」新建会话后由面试面板自动发出，触发面试官出第 1 题。
 *
 * 放在这里是为了侧栏与面板用同一份文案，避免两处各写一份后不一致。
 */
export const INTERVIEW_KICKOFF_COMMAND = '开始面试'

/**
 * 进度文案：第 n 题 / 共 N 题。
 *
 * @param state 面试状态或流内进度
 */
export function formatInterviewProgress(
  state: Pick<InterviewStateRespVO, 'questionIndex' | 'questionCount'>,
): string {
  return `第 ${state.questionIndex} 题 / 共 ${state.questionCount} 题`
}

/**
 * 难度文案：L1-L5，超出范围时按边界收敛。
 *
 * @param difficulty 难度等级
 */
export function formatDifficulty(difficulty: number): string {
  const level = Math.min(Math.max(Math.trunc(difficulty), 1), MAX_DIFFICULTY)
  return `L${level}`
}

/**
 * 取消息区里最近一次面试进度。
 *
 * 流里的进度事件比状态快照新，因此顶部进度以它为准；消息按时间倒序查找，拿到第一条即最新一条。
 *
 * @param messages 消息区消息
 * @returns 最近一次面试进度，没有时返回 null
 */
export function findLatestProgress(
  messages: ReadonlyArray<{ result: AssistantResultPayload | null }>,
): InterviewProgressResult | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const result = messages[index].result
    if (result && result.type === 'interview_progress') {
      return result
    }
  }
  return null
}
