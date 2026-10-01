import type { AssistantResultPayload } from '@/types/assistant'
import type {
  InterviewProgressResult,
  InterviewReportResult,
  InterviewResult,
  InterviewStateRespVO,
} from '@/types/interview'

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
  messages: ReadonlyArray<ResultCarrier>,
): InterviewProgressResult | null {
  return findLatestByType<InterviewProgressResult>(messages, 'interview_progress')
}

/**
 * 取消息区里最近一次面试结果。
 *
 * 面试结束时后端会在同一轮里下发结果事件；看历史面试时消息里没有结果，改由结果接口补齐。
 *
 * @param messages 消息区消息
 * @returns 最近一次面试结果，没有时返回 null
 */
export function findLatestResult(
  messages: ReadonlyArray<ResultCarrier>,
): InterviewResult | null {
  return findLatestByType<InterviewResult>(messages, 'interview_result')
}

/**
 * 取消息区里最近一次面试报告状态（F6）。
 *
 * @param messages 消息区消息
 * @returns 最近一次报告状态，没有时返回 null
 */
export function findLatestReport(
  messages: ReadonlyArray<ResultCarrier>,
): InterviewReportResult | null {
  return findLatestByType<InterviewReportResult>(messages, 'interview_report')
}

/** 消息区里带结构化结果的最小结构：`results` 是完整数组，`result` 是最后一个（历史用法）。 */
interface ResultCarrier {
  /** 本回合最后一个结构化结果。 */
  result: AssistantResultPayload | null
  /** 本回合全部结构化结果；老数据可能没有这个字段。 */
  results?: AssistantResultPayload[]
}

/**
 * 从后往前按类型查找最近一次结构化结果。
 *
 * 优先扫 `results` 数组（一轮可能有多个结果），没有数组时回退到单个 `result` 字段。
 *
 * @param messages 消息区消息
 * @param type 结果类型标识
 */
function findLatestByType<T extends AssistantResultPayload>(
  messages: ReadonlyArray<ResultCarrier>,
  type: T['type'],
): T | null {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    const payloads = message.results?.length ? message.results : message.result ? [message.result] : []
    for (let payloadIndex = payloads.length - 1; payloadIndex >= 0; payloadIndex -= 1) {
      const payload = payloads[payloadIndex]
      if (payload.type === type) {
        return payload as T
      }
    }
  }
  return null
}
