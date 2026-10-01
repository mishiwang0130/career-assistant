/**
 * 训练计划相关类型，字段与后端 VO / SSE result 取值对齐。
 */

/** 单条训练任务。 */
export interface TrainingTaskRespVO {
  /** 任务 ID，勾选时回传。 */
  id: number
  /** 所属计划 ID。 */
  planId: number
  /** 第几天，从 1 开始。 */
  dayIndex: number
  /** 任务日期，yyyy-MM-dd。 */
  taskDate: string
  /** 训练主题。 */
  topic: string
  /** 题型，例如八股、项目、综合。 */
  questionType: string
  /** 难度，1-5。 */
  difficulty: number
  /** 预计时长（分钟）。 */
  durationMinutes: number
  /** 对应知识点名称，可为空。 */
  knowledgePoint: string | null
  /** 同一天内的排序号。 */
  sortOrder: number
  /** 是否已完成。 */
  finished: boolean
  /** 完成时间，yyyy-MM-dd HH:mm，未完成时为空。 */
  finishTime: string | null
}

/** 按天分组的任务清单。 */
export interface TrainingDayRespVO {
  /** 第几天，从 1 开始。 */
  dayIndex: number
  /** 当天日期，yyyy-MM-dd。 */
  taskDate: string
  /** 当天时长合计（分钟）。 */
  totalMinutes: number
  /** 当天已完成任务数。 */
  finishedCount: number
  /** 当天任务清单。 */
  tasks: TrainingTaskRespVO[]
}

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

/** 计划概览 + 按天任务 + 今日提醒 + 未读角标。 */
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
  /** 计划概要。 */
  summary: string | null
  /** 调整原因，首次生成为空。 */
  adjustmentReason: string | null
  /** 生成时间，yyyy-MM-dd HH:mm。 */
  generatedAt: string | null
  /** 按天分组的任务清单。 */
  days: TrainingDayRespVO[]
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

/** 覆盖确认请求。 */
export interface TrainingPlanConfirmReqVO {
  /** 是否同意覆盖当前计划。 */
  approved: boolean
}

/** 勾选任务的请求参数。 */
export interface TrainingTaskFinishReqVO {
  /** 目标状态。 */
  finished: boolean
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
  /** 任务总数。 */
  totalTasks?: number
  /** 已完成任务数。 */
  finishedTasks?: number
}

/** 需要用户确认覆盖计划。 */
export interface TrainingPlanConfirmRequiredResult {
  /** 结果类型。 */
  type: 'plan_confirm_required'
  /** 提示文字。 */
  message: string
  /** 当前计划摘要。 */
  existingPlan: ExistingPlanSummary
}

/** 计划生成完成。 */
export interface TrainingPlanResult {
  /** 结果类型。 */
  type: 'training_plan'
  /** 生成后的计划。 */
  plan: TrainingPlanRespVO
}

/** 用户放弃覆盖。 */
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
