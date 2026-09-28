import { get, put } from '@/api/request'
import type { UserProfileRespVO, UserProfileSaveReqVO } from '@/types/userProfile'

/**
 * 查询当前登录用户的求职目标，未填写时返回 null（后端返回 data 为 null）。
 */
export function getUserProfile(): Promise<UserProfileRespVO | null> {
  return get<UserProfileRespVO | null>('/user-profile')
}

/**
 * 保存求职目标，一人一份，不存在则由后端新增。
 *
 * @param data 保存请求
 */
export function saveUserProfile(data: UserProfileSaveReqVO): Promise<UserProfileRespVO> {
  return put<UserProfileRespVO>('/user-profile', data)
}
