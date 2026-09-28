import { del, get, patch, post } from '@/api/request'
import type { PageRespVO } from '@/types/assistant'
import type {
  ChatSessionCreateReqVO,
  ChatSessionRenameReqVO,
  ChatSessionRespVO,
} from '@/types/session'

/** 会话列表每页条数，与后端默认值保持一致。 */
export const SESSION_PAGE_SIZE = 20

/**
 * 新建会话，返回后端生成的会话 ID。
 *
 * @param data 新建请求
 */
export function createSession(data: ChatSessionCreateReqVO): Promise<ChatSessionRespVO> {
  return post<ChatSessionRespVO>('/sessions', data)
}

/**
 * 分页查询当前用户会话列表，最近消息时间倒序（第 1 页是最新会话）。
 *
 * @param pageNum 页码，从 1 开始
 * @param pageSize 每页条数
 */
export function listSessions(
  pageNum = 1,
  pageSize = SESSION_PAGE_SIZE,
): Promise<PageRespVO<ChatSessionRespVO>> {
  return get<PageRespVO<ChatSessionRespVO>>('/sessions', { params: { pageNum, pageSize } })
}

/**
 * 重命名会话，返回服务端归一化后的会话。
 *
 * @param sessionId 会话 ID
 * @param data 重命名请求
 */
export function renameSession(
  sessionId: string,
  data: ChatSessionRenameReqVO,
): Promise<ChatSessionRespVO> {
  return patch<ChatSessionRespVO>(`/sessions/${sessionId}`, data)
}

/**
 * 删除会话，同时清空消息与 Agent 会话状态。
 *
 * @param sessionId 会话 ID
 */
export function deleteSession(sessionId: string): Promise<void> {
  return del<void>(`/sessions/${sessionId}`)
}
