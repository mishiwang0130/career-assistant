import { get } from '@/api/request'
import type { InterviewResult, InterviewStateRespVO } from '@/types/interview'

/**
 * 读取面试进度与当前难度。
 *
 * 进入面试会话（含刷新页面、离开再回来）时用它恢复「第 n 题 / 共 N 题、当前难度」；
 * 会话不存在、已删除或跨账号返回 1051，不是模拟面试的会话返回 1502。
 *
 * @param sessionId 面试会话 ID
 */
export function getInterviewState(sessionId: string): Promise<InterviewStateRespVO> {
  return get<InterviewStateRespVO>(`/interviews/${sessionId}`)
}

/**
 * 读取面试结果：逐题明细（哪里答得不好、标准答案）与整体统计。
 *
 * 面试结束时也会通过 SSE 的 result 事件下发同一份数据，这条接口用于刷新页面与回看历史面试。
 *
 * @param sessionId 面试会话 ID
 */
export function getInterviewResult(sessionId: string): Promise<InterviewResult> {
  return get<InterviewResult>(`/interviews/${sessionId}/result`)
}
