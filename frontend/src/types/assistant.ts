import type { InterviewProgressResult } from '@/types/interview'

/**
 * 通用助手相关类型。
 *
 * 事件名与 data 结构与后端 docs/技术约定.md 中的 SSE 事件协议保持一致。
 */

/** 消息角色，与后端 MessageRoleEnum 一致。 */
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

/** 工具调用状态。 */
export type ToolCallStatus = 'START' | 'END'

/** 对话请求。 */
export interface AssistantChatReqVO {
  /** 会话 ID，由后端生成（chat_session.id 的十进制字符串）。 */
  sessionId: string
  /** 用户消息内容。 */
  content: string
}

/** 历史消息响应。 */
export interface AssistantMessageRespVO {
  /** 消息 ID。 */
  id: number
  /** 会话 ID。 */
  sessionId: string
  /** 消息角色。 */
  role: MessageRole
  /** 消息内容。 */
  content: string
  /** 创建时间。 */
  createTime: string
}

/** 通用分页响应，与后端 PageRespVO 一致。 */
export interface PageRespVO<T> {
  /** 总记录数。 */
  total: number
  /** 当前页码。 */
  pageNum: number
  /** 每页条数。 */
  pageSize: number
  /** 当前页记录。 */
  records: T[]
}

/** meta 事件数据。 */
export interface AssistantMetaEvent {
  scene: string
  sessionId: string
  provider: string
  messageId: string
}

/** delta 与 thinking 事件的通用数据。 */
export interface AssistantContentEvent {
  content: string
}

/** tool 事件数据。 */
export interface AssistantToolEvent {
  name: string
  status: ToolCallStatus
  detail: string
}

/** error 事件数据。 */
export interface AssistantErrorEvent {
  message: string
}

/** 简历诊断的单项维度评分。 */
export interface ResumeDiagnosisDimension {
  /** 维度名。 */
  name: string
  /** 维度得分，0-100。 */
  score: number
  /** 一句话理由。 */
  comment: string
}

/** 简历诊断的问题条目。 */
export interface ResumeDiagnosisProblem {
  /** 问题描述。 */
  problem: string
  /** 问题出现的位置。 */
  location: string
  /** 为什么是问题。 */
  reason: string
  /** 怎么改。 */
  suggestion: string
  /** 严重程度：HIGH / MEDIUM / LOW。 */
  severity: string
}

/** 简历诊断的亮点条目。 */
export interface ResumeDiagnosisHighlight {
  /** 值得保留的写法。 */
  point: string
  /** 为什么好。 */
  reason: string
}

/** 简历诊断的优化建议条目。 */
export interface ResumeDiagnosisSuggestion {
  /** 优先级，1 最高。 */
  priority: number
  /** 建议内容。 */
  content: string
}

/**
 * 简历诊断结论。
 *
 * 与后端 ResumeDiagnosisResultVO 一一对应：诊断卡片据此渲染，其中 optimizedResume 用于
 * 「另存为新简历」，不覆盖原简历。
 */
export interface ResumeDiagnosisResult {
  /** 结果类型标识，用于区分后续 F6 的点评卡片与报告卡片。 */
  type: 'resume_diagnosis'
  /** 被诊断的简历 ID。 */
  resumeId: number
  /** 被诊断的简历标题。 */
  resumeTitle: string
  /** 综合得分，0-100。 */
  overallScore: number
  /** 综合得分说明。 */
  scoreSummary: string
  /** 维度评分，至少 4 项。 */
  dimensions: ResumeDiagnosisDimension[]
  /** 问题清单。 */
  problems: ResumeDiagnosisProblem[]
  /** 亮点清单。 */
  highlights: ResumeDiagnosisHighlight[]
  /** 优化建议。 */
  suggestions: ResumeDiagnosisSuggestion[]
  /** 优化后的简历正文。 */
  optimizedResume: string
  /** 可能被追问的项目点。 */
  interviewFollowUps: string[]
}

/**
 * SSE result 事件的结构化载荷。
 *
 * 用 type 做判别联合：当前有简历诊断结论与面试进度，后续 F6 的点评与报告只新增取值，
 * 不改这里的字段含义与事件形态。
 */
export type AssistantResultPayload = ResumeDiagnosisResult | InterviewProgressResult

/** result 事件数据。 */
export interface AssistantResultEvent {
  /** 结构化结果。 */
  data: AssistantResultPayload
}

/** 页面展示用的工具调用提示。 */
export interface ToolTip {
  /** 工具名或子智能体名。 */
  name: string
  /** 状态，START 表示开始调用。 */
  status: ToolCallStatus
  /** 明细，例如工具调用 ID。 */
  detail: string
}

/** 页面展示用的对话消息。 */
export interface ChatMessage {
  /** 前端生成的消息 ID。 */
  id: string
  /** 消息角色。 */
  role: MessageRole
  /** 消息正文。 */
  content: string
  /**
   * 思考内容，模型不支持思考时为空。
   *
   * 只在正文产出前存在于前端内存：正文一开始产出（或本轮结束）就丢弃，不留回看入口，
   * 不落库，历史消息里恒为空串（见 docs/技术约定.md「SSE 事件协议」展示边界）。
   */
  thinking: string
  /** 工具调用提示。 */
  tools: ToolTip[]
  /** 是否正在流式接收。 */
  streaming: boolean
  /** 是否以错误结束。 */
  failed: boolean
  /** 结构化结果（如简历诊断结论），没有时为空。 */
  result: AssistantResultPayload | null
}
