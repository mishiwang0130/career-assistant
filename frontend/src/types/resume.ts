/**
 * 简历来源类型。
 */
export type ResumeSourceType = 'UPLOAD' | 'MANUAL'

/**
 * 简历解析状态。
 */
export type ResumeParseStatus = 'PENDING' | 'SUCCESS' | 'FAILED'

/**
 * 在线创建简历请求。
 */
export interface ResumeManualReqVO {
  title: string
  rawText: string
}

/**
 * 更新简历请求，标题和正文至少填写一项。
 */
export interface ResumeUpdateReqVO {
  title?: string
  rawText?: string
}

/**
 * 简历列表响应。
 */
export interface ResumeListRespVO {
  id: number
  title: string
  sourceType: ResumeSourceType
  fileName: string | null
  fileSize: number | null
  fileExt: string | null
  parseStatus: ResumeParseStatus
  parseError: string | null
  defaultFlag: boolean
  createTime: string
  updateTime: string
}

/**
 * 简历详情响应。
 */
export interface ResumeDetailRespVO extends ResumeListRespVO {
  rawText: string | null
}
