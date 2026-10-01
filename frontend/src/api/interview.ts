import { get } from '@/api/request'
import { post } from '@/api/request'
import type { InterviewReportResult, InterviewResult, InterviewStateRespVO } from '@/types/interview'

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

/**
 * 读取面试报告：状态（生成中 / 已完成 / 生成失败）+ 错题清单 + 薄弱点清单 + 掌握度 + 面试总结。
 *
 * 报告由后台任务生成，因此面试结束后先拿「生成中」，再按固定间隔轮询这条接口拿最新状态；刷新页面同样走它。
 * 会话不存在、已删除或跨账号返回 1051，不是模拟面试的会话返回 1502，面试尚未结束返回 1601。
 *
 * @param sessionId 面试会话 ID
 */
export function getInterviewReport(sessionId: string): Promise<InterviewReportResult> {
  return get<InterviewReportResult>(`/interviews/${sessionId}/report`)
}

/**
 * 报告生成失败后重试。
 *
 * 生成中返回 1602，已完成时幂等返回现有报告，面试未结束返回 1601。
 *
 * @param sessionId 面试会话 ID
 */
export function retryInterviewReport(sessionId: string): Promise<InterviewReportResult> {
  return post<InterviewReportResult>(`/interviews/${sessionId}/report/retry`)
}
