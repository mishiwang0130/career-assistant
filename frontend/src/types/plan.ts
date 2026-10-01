/**
 * 训练计划相关类型，字段与后端 VO / SSE result 取值对齐。
 */

/** 训练提醒。 */
export interface TrainingReminderRespVO {
  /** 提醒 ID。 */
  id: number
  /** 提醒日期，yyyy-MM-dd。 */
  reminderDate: string
  /** 提醒正文。 */
  content: string
  /** 是否已读。 */
  read: boolean
  /** 已读时间，yyyy-MM-dd HH:mm，未读时为空。 */
  readTime: string | null
}

/** 计划概览 + 计划正文 + 今日提醒 + 未读角标。 */
export interface TrainingPlanRespVO {
  /** 是否存在生效中的计划。 */
  hasPlan: boolean
  /** 计划 ID。 */
  planId: number | null
  /** 计划状态。 */
  status: string | null
  /** 生成时的目标岗位快照。 */
  targetPosition: string | null
  /** 计划开始日期，yyyy-MM-dd。 */
  startDate: string | null
  /** 计划截止日期，yyyy-MM-dd。 */
  endDate: string | null
  /** 计划总天数。 */
  totalDays: number | null
  /** 每天可练时长（分钟）。 */
  dailyMinutes: number | null
  /** 剩余天数，由截止日期实时算出。 */
  remainingDays: number | null
  /** 计划正文：按天一句话概括当天练什么知识点（Markdown）。 */
  planContent: string | null
  /** 调整原因，首次生成为空。 */
  adjustmentReason: string | null
  /** 生成时间，yyyy-MM-dd HH:mm。 */
  generatedAt: string | null
  /** 今日提醒，没有时为空。 */
  todayReminder: TrainingReminderRespVO | null
  /** 未读提醒数。 */
  unreadReminderCount: number
}

/** 生成计划的请求参数。 */
export interface TrainingPlanGenerateReqVO {
  /** 还有几天。 */
  days: number
  /** 每天可练时长（分钟）。 */
  dailyMinutes: number
}

/** 确认请求：确认后才保存计划。 */
export interface TrainingPlanConfirmReqVO {
  /** 是否同意保存这份计划。 */
  approved: boolean
}

/** 未读数响应。 */
export interface TrainingReminderUnreadRespVO {
  /** 未读条数。 */
  count: number
}

/** 已有计划摘要，随确认请求一起下发。 */
export interface ExistingPlanSummary {
  /** 计划 ID。 */
  planId?: number
  /** 目标岗位。 */
  targetPosition?: string
  /** 截止日期。 */
  endDate?: string
  /** 剩余天数。 */
  remainingDays?: number
}

/** 需要用户确认保存计划（确认之前一个字都没落库）。 */
export interface TrainingPlanConfirmRequiredResult {
  /** 结果类型。 */
  type: 'plan_confirm_required'
  /** 提示文字。 */
  message: string
  /** 计划草稿正文：给用户确认用的 Markdown。 */
  draftContent: string
  /** 当前计划摘要，没有旧计划时为空对象。 */
  existingPlan: ExistingPlanSummary
}

/** 计划生成完成（已保存）。 */
export interface TrainingPlanResult {
  /** 结果类型。 */
  type: 'training_plan'
  /** 保存后的计划。 */
  plan: TrainingPlanRespVO
}

/** 用户放弃保存。 */
export interface TrainingPlanRejectedResult {
  /** 结果类型。 */
  type: 'plan_confirm_rejected'
  /** 提示文字。 */
  message: string
}

/** SSE result 事件里可能出现的训练计划结果。 */
export type TrainingPlanResultPayload =
  | TrainingPlanConfirmRequiredResult
  | TrainingPlanResult
  | TrainingPlanRejectedResult
