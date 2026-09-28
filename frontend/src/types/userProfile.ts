/**
 * 求职目标相关类型，字段与后端 UserProfileRespVO / UserProfileSaveReqVO 对齐。
 */

/**
 * 求职目标响应。
 */
export interface UserProfileRespVO {
  /** 目标岗位。 */
  targetPosition: string
  /** 当前工作年限（年），0 表示应届或不足一年。 */
  workYears: number
}

/**
 * 保存求职目标请求，两个字段都是必填。
 */
export interface UserProfileSaveReqVO {
  /** 目标岗位，非空且不超过 100 个字符。 */
  targetPosition: string
  /** 当前工作年限（年），0–60，0 表示应届或不足一年。 */
  workYears: number
}
