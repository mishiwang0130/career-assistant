import { get, post } from '@/api/request'
import { postSse, type SseEventHandler } from '@/utils/sse'
import type {
  TrainingPlanConfirmReqVO,
  TrainingPlanGenerateReqVO,
  TrainingPlanRespVO,
  TrainingReminderUnreadRespVO,
} from '@/types/plan'

/**
 * 查询当前用户的训练计划（含按天任务、今日提醒与未读角标）。
 */
export function getCurrentPlan(): Promise<TrainingPlanRespVO> {
  return get<TrainingPlanRespVO>('/plans/current')
}

/**
 * 生成（或重新规划）训练计划，流式返回过程与结果。
 *
 * 已有生效计划时，流会以 result{type:'plan_confirm_required'} 结束，等用户确认后再调 confirmGeneration 继续。
 *
 * @param data 生成参数
 * @param onEvent SSE 事件回调
 * @param signal 取消信号
 */
export function generatePlan(
  data: TrainingPlanGenerateReqVO,
  onEvent: SseEventHandler,
  signal?: AbortSignal,
): Promise<void> {
  return postSse('/api/plans/generation', data, onEvent, signal)
}

/**
 * 回填「是否覆盖已有计划」的确认结论并继续生成。
 *
 * @param data 确认结果
 * @param onEvent SSE 事件回调
 * @param signal 取消信号
 */
export function confirmGeneration(
  data: TrainingPlanConfirmReqVO,
  onEvent: SseEventHandler,
  signal?: AbortSignal,
): Promise<void> {
  return postSse('/api/plans/generation/confirm', data, onEvent, signal)
}

/**
 * 勾选或取消勾选训练任务。
 *
 * @param taskId 任务 ID
 * @param finished 目标状态
 */
export function finishTask(taskId: number, finished: boolean): Promise<void> {
  return post<void>(`/plans/tasks/${taskId}/finish`, { finished })
}

/**
 * 查询未读提醒数（侧栏角标）。
 */
export function getUnreadCount(): Promise<TrainingReminderUnreadRespVO> {
  return get<TrainingReminderUnreadRespVO>('/plans/reminders/unread-count')
}

/**
 * 把一条提醒标记为已读。
 *
 * @param reminderId 提醒 ID
 */
export function readReminder(reminderId: number): Promise<void> {
  return post<void>(`/plans/reminders/${reminderId}/read`)
}

/**
 * 把全部未读提醒标记为已读（进入计划页即清零角标）。
 */
export function readAllReminders(): Promise<void> {
  return post<void>('/plans/reminders/read-all')
}
