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
 * 解析 SSE `result` 事件的 data 行。
 *
 * 冻结协议里 `result` 事件的 data 是 `{"data": <结构化产物>}`（见 SseEvent.result 与助手链路同样的读取方式），
 * 不是把结构化产物直接当 data。两者搞混的表现是：F12 里能看到 result 事件与内容，页面却什么都不显示。
 *
 * @param raw SSE data 行（单行 JSON）
 * @returns 训练计划结果载荷，不是计划结果或解析失败时返回 null
 */
export function parsePlanResultEvent(raw: string): TrainingPlanResultPayload | null {
  if (!raw) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  const payload = unwrapResultPayload(parsed)
  if (!payload || typeof payload !== 'object') {
    return null
  }
  const type = (payload as { type?: unknown }).type
  if (type === 'plan_confirm_required' || type === 'training_plan' || type === 'plan_confirm_rejected') {
    // 已按 type 收窄到三个取值，这里显式经 unknown 转换，避免 TS 认为两个类型不重叠。
    return payload as unknown as TrainingPlanResultPayload
  }
  return null
}

/**
 * 剥掉 `result` 事件的外层 `{data: ...}` 包装。
 *
 * @param parsed 解析后的 data 行
 * @returns 结构化产物，取不到时返回 null
 */
function unwrapResultPayload(parsed: unknown): Record<string, unknown> | null {
  if (parsed === null || typeof parsed !== 'object') {
    return null
  }
  const envelope = parsed as { data?: unknown }
  const inner = envelope.data
  if (inner !== null && typeof inner === 'object') {
    return inner as Record<string, unknown>
  }
  return null
}

/**
 * 把工具事件的进度翻译成用户能看懂的一句话。
 *
 * <p>计划页是非会话页面，模型思考与工具调用都是内部过程，界面不展示工具名；但整轮生成要几十秒，
 * 完全没反馈会让人以为卡住了。这里只给「正在做什么」的量级信息，不暴露任何工具名与内部字段。
 *
 * @param event 事件名
 * @param data 事件的 data 行
 * @returns 进度文案，无需展示时返回 null
 */
export function describeGenerationProgress(event: string, data: string): string | null {
  if (event !== 'tool') {
    return null
  }
  let parsed: { name?: unknown; status?: unknown }
  try {
    parsed = JSON.parse(data) as { name?: unknown; status?: unknown }
  } catch {
    return null
  }
  if (parsed.status !== 'START' || typeof parsed.name !== 'string') {
    return null
  }
  if (parsed.name === 'get_weak_points') {
    return '正在读取你的薄弱点…'
  }
  if (parsed.name === 'submit_training_plan') {
    return '正在整理按天计划…'
  }
  return '正在排计划…'
}

/**
 * 判断事件是否为生成流的终态（收到后就不该再转圈）。
 *
 * @param event 事件名
 * @returns 终态返回 true
 */
export function isGenerationTerminalEvent(event: string): boolean {
  return event === 'done' || event === 'error'
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
