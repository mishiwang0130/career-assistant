/**
 * 后端统一响应结构。
 */
export interface Result<T> {
  /** 业务响应码，200 表示成功。 */
  code: number
  /** 响应提示。 */
  msg: string
  /** 响应数据。 */
  data: T
}
