/**
 * 模拟面试相关类型。
 *
 * 与后端 `InterviewStateRespVO` / `InterviewProgressResultVO` 一一对应：状态接口用于进入会话时恢复进度，
 * SSE 的 `result` 事件用于实时刷新进度与难度。
 */

/** 面试题型，与后端 InterviewQuestionTypeEnum 一致。 */
export type InterviewQuestionType = 'BASIC' | 'PROJECT' | 'COMPREHENSIVE'

/** 面试状态快照，GET /api/interviews/{sessionId} 的返回结构。 */
export interface InterviewStateRespVO {
  /** 面试会话 ID。 */
  sessionId: string
  /** 当前（或下一道）主问题序号，从 1 开始。 */
  questionIndex: number
  /** 主问题总题量。 */
  questionCount: number
  /** 当前题目难度等级 1-5。 */
  difficulty: number
  /** 当前轮次：1 主问题，2 追问。 */
  roundNo: number
  /** 本场面试是否已结束。 */
  finished: boolean
  /** 起始难度，由工作年限决定，也是全场难度下限。 */
  startDifficulty: number
  /** 本轮题型的建议，已结束时为 null。 */
  recommendedQuestionType: InterviewQuestionType | null
}

/**
 * SSE result 事件里的面试进度载荷。
 *
 * 沿用后端冻结的判别联合：`type` 为 `interview_progress`，F6 的点评与报告卡片继续只新增 type 取值。
 */
export interface InterviewProgressResult {
  /** 结构化结果类型标识。 */
  type: 'interview_progress'
  /** 面试会话 ID。 */
  sessionId: string
  /** 当前（或下一道）主问题序号。 */
  questionIndex: number
  /** 主问题总题量。 */
  questionCount: number
  /** 当前题目难度等级 1-5。 */
  difficulty: number
  /** 当前轮次：1 主问题，2 追问。 */
  roundNo: number
  /** 本场面试是否已结束。 */
  finished: boolean
}
