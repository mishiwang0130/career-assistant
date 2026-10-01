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

/** 面试结果里的单题明细。 */
export interface InterviewResultItem {
  /** 主问题序号，从 1 开始。 */
  questionIndex: number
  /** 轮次：1 主问题，2 追问。 */
  roundNo: number
  /** 题目正文。 */
  question: string
  /** 用户当时的回答。 */
  answer: string
  /** 判定结果。 */
  outcome: 'CORRECT' | 'PARTIAL' | 'WRONG'
  /** 判定结果的中文说明。 */
  outcomeLabel: string | null
  /** 本题难度 1-5。 */
  difficulty: number
  /** 参考得分，没有评分时为 null。 */
  score: number | null
  /** 一句话点评。 */
  comment: string | null
  /** 答对或答到的点。 */
  correctPoints: string[]
  /** 应该提到但没提到的点。 */
  missingPoints: string[]
  /** 说错、理解偏差的点。 */
  wrongPoints: string[]
  /** 表达层面的问题。 */
  expressionIssues: string[]
  /** 下次遇到同类题的建议。 */
  suggestions: string[]
  /** 本题涉及的知识点。 */
  knowledgePoints: string[]
  /** 标准答案；评分不可用时为 null。 */
  referenceAnswer: string | null
  /** 本题是否有评分结论。 */
  evaluated: boolean
}

/** 面试结果：SSE result 事件与结果接口共用同一份结构。 */
export interface InterviewResult {
  /** 结构化结果类型标识。 */
  type: 'interview_result'
  /** 面试会话 ID。 */
  sessionId: string
  /** 主问题总题量。 */
  questionCount: number
  /** 已作答的回合数（含追问）。 */
  answeredCount: number
  /** 答到要点的回合数。 */
  correctCount: number
  /** 有遗漏的回合数。 */
  partialCount: number
  /** 不会或答错的回合数。 */
  wrongCount: number
  /** 有评分回合的平均分，没有评分时为 null。 */
  averageScore: number | null
  /** 本场面试是否已结束。 */
  finished: boolean
  /** 逐题明细，按作答顺序排列。 */
  items: InterviewResultItem[]
}

/** SSE result 事件里的逐题点评载荷（F6），与后端 InterviewEvaluationRespVO 一一对应。 */
export interface InterviewEvaluationResult {
  /** 结构化结果类型标识。 */
  type: 'interview_evaluation'
  /** 面试会话 ID。 */
  sessionId: string
  /** 主问题序号，从 1 开始。 */
  questionIndex: number
  /** 轮次：1 主问题，2 追问。 */
  roundNo: number
  /** 判定结果。 */
  outcome: 'CORRECT' | 'PARTIAL' | 'WRONG'
  /** 判定结果的中文说明。 */
  outcomeLabel: string | null
  /** 本题难度 1-5。 */
  difficulty: number
  /** 参考得分，没有评分时为 null。 */
  score: number | null
  /** 一句话点评。 */
  comment: string | null
  /** 答对或答到的点。 */
  correctPoints: string[]
  /** 应该提到但没提到的点。 */
  missingPoints: string[]
  /** 说错、理解偏差的点。 */
  wrongPoints: string[]
  /** 表达层面的问题。 */
  expressionIssues: string[]
  /** 下次遇到同类题的建议。 */
  suggestions: string[]
  /** 本题涉及的知识点。 */
  knowledgePoints: string[]
  /** 标准答案；评分不可用时为 null。 */
  referenceAnswer: string | null
  /** 本题是否有评分结论。 */
  evaluated: boolean
}

/** 面试报告生成状态。 */
export type InterviewReportStatus = 'GENERATING' | 'SUCCEEDED' | 'FAILED'

/** 掌握度等级。 */
export type KnowledgeMasteryLevel = 'WEAK' | 'NEEDS_WORK' | 'BASIC' | 'PROFICIENT' | 'MASTERED'

/** 报告里的知识点掌握度条目。 */
export interface KnowledgeMasteryItem {
  /** 知识点名称。 */
  knowledgePoint: string
  /** 掌握度分数 0-100。 */
  masteryScore: number
  /** 掌握度等级。 */
  masteryLevel: KnowledgeMasteryLevel | null
  /** 掌握度等级中文说明。 */
  masteryLevelLabel: string | null
  /** 是否薄弱点。 */
  weak: boolean
  /** 参与计算的证据条数。 */
  evidenceCount: number
  /** 最近一次证据的判定。 */
  lastOutcome: 'CORRECT' | 'PARTIAL' | 'WRONG' | null
}

/** 报告里的错题条目。 */
export interface InterviewWrongItem {
  /** 主问题序号。 */
  questionIndex: number
  /** 轮次。 */
  roundNo: number
  /** 题目正文。 */
  question: string
  /** 判定结果，固定为 WRONG。 */
  outcome: 'WRONG'
  /** 判定结果的中文说明。 */
  outcomeLabel: string | null
  /** 一句话点评。 */
  comment: string | null
  /** 本题涉及的知识点。 */
  knowledgePoints: string[]
}

/** 报告里的薄弱点条目。 */
export interface InterviewWeaknessItem {
  /** 知识点名称。 */
  knowledgePoint: string
  /** 掌握度分数 0-100。 */
  masteryScore: number
  /** 掌握度等级。 */
  masteryLevel: KnowledgeMasteryLevel | null
  /** 掌握度等级中文说明。 */
  masteryLevelLabel: string | null
  /** 最近一次证据的判定。 */
  lastOutcome: 'CORRECT' | 'PARTIAL' | 'WRONG' | null
  /** 最近一次点评，说明为什么薄弱。 */
  comment: string | null
}

/** SSE result 事件的报告载荷，与后端 InterviewReportRespVO 一一对应。 */
export interface InterviewReportResult {
  /** 结构化结果类型标识。 */
  type: 'interview_report'
  /** 面试会话 ID。 */
  sessionId: string
  /** 生成状态：生成中 / 已完成 / 生成失败。 */
  status: InterviewReportStatus
  /** 生成状态中文说明。 */
  statusLabel: string | null
  /** 生成完成时间。 */
  generatedAt: string | null
  /** 面试总结正文。 */
  summary: string | null
  /** 亮点清单。 */
  highlights: string[]
  /** 下一步建议清单。 */
  suggestions: string[]
  /** 错题清单。 */
  wrongItems: InterviewWrongItem[]
  /** 薄弱点清单。 */
  weaknesses: InterviewWeaknessItem[]
  /** 本场涉及知识点的掌握度。 */
  mastery: KnowledgeMasteryItem[]
  /** 失败原因。 */
  errorMessage: string | null
  /** 是否允许重试。 */
  canRetry: boolean | null
}
