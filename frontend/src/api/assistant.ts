import { del, get } from '@/api/request'
import { postSse, type SseEventHandler } from '@/utils/sse'
import type { AssistantChatReqVO, AssistantMessageRespVO, PageRespVO } from '@/types/assistant'

/**
 * 流式发送一条消息。
 *
 * @param data 对话请求
 * @param onEvent SSE 事件回调
 * @param signal 取消信号
 */
export function chat(
  data: AssistantChatReqVO,
  onEvent: SseEventHandler,
  signal?: AbortSignal,
): Promise<void> {
  return postSse('/api/assistant/chat', data, onEvent, signal)
}

/**
 * 分页查询历史消息。
 *
 * @param sessionId 会话 ID
 * @param pageNum 页码
 * @param pageSize 每页条数
 */
export function listMessages(
  sessionId: string,
  pageNum = 1,
  pageSize = 20,
): Promise<PageRespVO<AssistantMessageRespVO>> {
  return get<PageRespVO<AssistantMessageRespVO>>('/assistant/messages', {
    params: { sessionId, pageNum, pageSize },
  })
}

/**
 * 清空指定会话的历史消息与 Agent 上下文。
 *
 * @param sessionId 会话 ID
 */
export function clearSession(sessionId: string): Promise<void> {
  return del<void>('/assistant/session', { params: { sessionId } })
}
