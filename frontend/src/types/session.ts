/**
 * 会话中心相关类型。
 *
 * 会话 ID 由后端生成，取值是 chat_session.id 的十进制字符串，前端不再自造 UUID。
 */

/**
 * 会话场景。
 *
 * 本期只开放通用助手；M7/M8/M14 追加 INTERVIEW、DIAGNOSIS、MATCH、TUTOR 时，
 * 在这里扩展联合类型，ChatView 的面板映射会因为类型不完整而编译报错，提醒补齐面板。
 */
export type ChatScene = 'ASSISTANT'

/** 会话列表项。 */
export interface ChatSessionRespVO {
  /** 会话 ID，后端生成的 chat_session.id 十进制字符串。 */
  sessionId: string
  /** 会话标题，新建时为「新会话」，首条消息后自动改写。 */
  title: string
  /** 会话场景。 */
  scene: ChatScene
  /** 最近一条用户消息时间，用于展示相对时间。 */
  lastMessageAt: string
}

/** 新建会话请求。 */
export interface ChatSessionCreateReqVO {
  /** 会话场景，本期固定 ASSISTANT。 */
  scene: ChatScene
}

/** 重命名会话请求。 */
export interface ChatSessionRenameReqVO {
  /** 新标题，最长 100 个字符。 */
  title: string
}
