import type { TrainingPlanResultPayload } from '@/types/plan'

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
 * 判断一条 SSE result 载荷是否要求用户确认保存计划。
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
  if (!payload) {
    return null
  }
  const type = payload.type
  if (type === 'plan_confirm_required' || type === 'training_plan' || type === 'plan_confirm_rejected') {
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
  const inner = (parsed as { data?: unknown }).data
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
    return '正在整理计划正文…'
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

/** 生成过程中的文本：思考与正文分开累积。 */
export interface GenerationStreamText {
  /** 思考增量累积的文本；正文一开始产出就丢弃，不再补回。 */
  thinking: string
  /** 正文增量累积的文本。 */
  answer: string
}

/**
 * 按事件更新生成过程的文本。
 *
 * 规则沿用会话里的展示口径：`thinking` 只在正文产出前展示，一旦出现 `delta` 立刻丢弃思考文本，
 * 之后的思考增量不再补回；`tool`/`node` 属于内部过程，只通过进度文案体现，不混进正文。
 *
 * @param current 当前文本
 * @param event 事件名
 * @param data 事件的 data 行
 * @returns 更新后的文本，事件与文本无关时返回 null
 */
export function nextStreamText(
  current: GenerationStreamText,
  event: string,
  data: string,
): GenerationStreamText | null {
  if (event !== 'thinking' && event !== 'delta') {
    return null
  }
  let content: unknown
  try {
    content = (JSON.parse(data) as { content?: unknown }).content
  } catch {
    return null
  }
  if (typeof content !== 'string' || content.length === 0) {
    return null
  }
  if (event === 'delta') {
    // 正文开始产出：思考过程立刻丢弃，不保留折叠入口。
    return { thinking: '', answer: current.answer + content }
  }
  return { thinking: current.answer ? '' : current.thinking + content, answer: current.answer }
}

/**
 * 计划正文卡片的折叠预览：取前若干行，避免整篇正文直接把页面撑长。
 *
 * @param content 计划正文（支持 Markdown）
 * @param maxLines 预览行数，默认 4
 * @returns 预览文本，正文为空时返回空串
 */
export function buildPlanCardPreview(content: string | null | undefined, maxLines = 4): string {
  if (!content) {
    return ''
  }
  const lines = content
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter((line) => line.trim().length > 0)
  if (lines.length <= maxLines) {
    return lines.join('\n')
  }
  return `${lines.slice(0, maxLines).join('\n')}\n\n……`
}
