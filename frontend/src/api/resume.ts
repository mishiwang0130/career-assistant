import { get, post, put, remove } from '@/api/request'
import type {
  ResumeDetailRespVO,
  ResumeListRespVO,
  ResumeManualReqVO,
  ResumeUpdateReqVO,
} from '@/types/resume'

/**
 * 查询当前用户简历列表。
 */
export function getResumeList(): Promise<ResumeListRespVO[]> {
  return get<ResumeListRespVO[]>('/resumes')
}

/**
 * 查询简历详情。
 */
export function getResumeDetail(id: number): Promise<ResumeDetailRespVO> {
  return get<ResumeDetailRespVO>(`/resumes/${id}`)
}

/**
 * 上传简历文件并同步解析。
 */
export function uploadResume(file: File, title?: string): Promise<ResumeDetailRespVO> {
  const formData = new FormData()
  formData.append('file', file)
  if (title?.trim()) {
    formData.append('title', title.trim())
  }
  return post<ResumeDetailRespVO>('/resumes/upload', formData)
}

/**
 * 在线创建简历。
 */
export function createManualResume(data: ResumeManualReqVO): Promise<ResumeDetailRespVO> {
  return post<ResumeDetailRespVO>('/resumes/manual', data)
}

/**
 * 更新简历标题或正文。
 */
export function updateResume(
  id: number,
  data: ResumeUpdateReqVO,
): Promise<ResumeDetailRespVO> {
  return put<ResumeDetailRespVO>(`/resumes/${id}`, data)
}

/**
 * 逻辑删除简历。
 */
export function deleteResume(id: number): Promise<void> {
  return remove<void>(`/resumes/${id}`)
}

/**
 * 设为默认简历。
 */
export function setDefaultResume(id: number): Promise<void> {
  return put<void>(`/resumes/${id}/default`)
}
