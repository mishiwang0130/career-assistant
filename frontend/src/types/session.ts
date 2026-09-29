/**
 * 会话中心相关类型。
 *
 * 会话 ID 由后端生成，取值是 chat_session.id 的十进制字符串，前端不再自造 UUID。
 */

/**
 * 会话场景。
 *
 * 只有「有状态的长流程」型功能才算会话场景，一次性任务（简历诊断、岗位匹配、专项辅导）
 * 由助手 Agent 在对话内派发子 Agent 或加载 Skill 完成。当前开放通用助手（默认入口）与模拟面试；
 * ChatView 的面板映射按本联合类型补全，新增场景时类型不完整会直接编译报错。
 */
export type ChatScene = 'ASSISTANT' | 'INTERVIEW'

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
