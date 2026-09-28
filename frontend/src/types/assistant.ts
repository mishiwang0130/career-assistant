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
  /** 思考内容，模型不支持思考时为空。 */
  thinking: string
  /** 工具调用提示。 */
  tools: ToolTip[]
  /** 是否正在流式接收。 */
  streaming: boolean
  /** 是否以错误结束。 */
  failed: boolean
}
