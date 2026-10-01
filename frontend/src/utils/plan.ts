import type {
  ExistingPlanSummary,
  TrainingPlanRespVO,
  TrainingPlanResultPayload,
} from '@/types/plan'

/** 计划进度：任务总数、已完成数与完成百分比。 */
export interface TrainingPlanProgress {
  /** 任务总数。 */
  total: number
  /** 已完成任务数。 */
  finished: number
  /** 完成百分比，0-100 的整数。 */
  percent: number
}

/**
 * 把 yyyy-MM-dd 解析成本地时区的当天零点。
 *
 * @param value 日期字符串
 * @returns 当天零点，格式非法时返回 null
 */
function parseDate(value: string | null | undefined): Date | null {
  if (!value) {
    return null
  }
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim())
  if (!match) {
    return null
  }
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(year, month - 1, day)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * 实时计算剩余天数：截止日期含当天，截止日期已过返回 0。
 *
 * 计划页的「剩余天数」不落库、不做定时更新，每次展示都按当前日期现算，因此用户隔天打开页面看到的数字会自动变化。
 *
 * @param endDate 截止日期（yyyy-MM-dd）
 * @param today 今天的基准时间，默认当前时间
 * @returns 剩余天数，解析失败时返回 0
 */
export function countRemainingDays(endDate: string | null | undefined, today: Date = new Date()): number {
  const end = parseDate(endDate)
  if (!end) {
    return 0
  }
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  const diff = Math.round((end.getTime() - startOfToday.getTime()) / 86400000) + 1
  return diff > 0 ? diff : 0
}

/**
 * 统计计划完成进度。
 *
 * @param plan 计划
 * @returns 进度
 */
export function buildPlanProgress(plan: TrainingPlanRespVO | null): TrainingPlanProgress {
  const days = plan?.days ?? []
  let total = 0
  let finished = 0
  for (const day of days) {
    total += day.tasks.length
    finished += day.tasks.filter((task) => task.finished).length
  }
  const percent = total === 0 ? 0 : Math.round((finished / total) * 100)
  return { total, finished, percent }
}

/**
 * 判断一条 SSE result 载荷是否要求用户确认覆盖已有计划。
 *
 * @param payload result 载荷
 * @returns 需要确认时返回 true
 */
export function isConfirmRequired(payload: TrainingPlanResultPayload): boolean {
  return payload.type === 'plan_confirm_required'
}

/**
 * 把确认请求里的现有计划摘要拼成一句人话。
 *
 * @param summary 现有计划摘要
 * @returns 说明文字
 */
export function describeExistingPlan(summary: ExistingPlanSummary | undefined): string {
  if (!summary || !summary.planId) {
    return '当前已有生效中的训练计划'
  }
  const position = summary.targetPosition ? `目标岗位「${summary.targetPosition}」` : '当前计划'
  const remaining = typeof summary.remainingDays === 'number' ? `剩余 ${summary.remainingDays} 天` : ''
  const progress =
    typeof summary.totalTasks === 'number'
      ? `已完成 ${summary.finishedTasks ?? 0}/${summary.totalTasks} 个任务`
      : ''
  return [position, remaining, progress].filter((part) => part).join('，')
}

/**
 * 汇总计划里的任务，便于渲染统计行。
 *
 * @param plan 计划
 * @returns 任务总数与当天任务数
 */
export function summarizePlan(plan: TrainingPlanRespVO | null): {
  totalTasks: number
  totalMinutes: number
} {
  const days = plan?.days ?? []
  let totalTasks = 0
  let totalMinutes = 0
  for (const day of days) {
    totalTasks += day.tasks.length
    totalMinutes += day.totalMinutes
  }
  return { totalTasks, totalMinutes }
}
