import type { UserInfoRespVO } from '@/types/auth'

// 存储 key 是本模块冻结约定，页面不能自行拼写。
const ACCESS_TOKEN_KEY = 'career_token'
const REFRESH_TOKEN_KEY = 'career_refresh_token'
const USER_KEY = 'career_user'

// 历史上由前端自造并持久化的会话 ID key。M16 起会话 ID 由后端生成，登录态清理时顺手删除该残留。
const LEGACY_ASSISTANT_SESSION_KEY = 'career_assistant_session'

// 本次登录是否已提示过求职目标未填写。写在 sessionStorage：登录成功时清除，关闭标签页自然失效。
const PROFILE_PROMPTED_KEY = 'career_profile_prompted'

/**
 * 获取 Access Token。
 */
export function getAccessToken(): string | null {
  return window.localStorage.getItem(ACCESS_TOKEN_KEY)
}

/**
 * 获取 Refresh Token。
 */
export function getRefreshToken(): string | null {
  return window.localStorage.getItem(REFRESH_TOKEN_KEY)
}

/**
 * 保存 Access Token 和 Refresh Token。
 */
export function setTokens(accessToken: string, refreshToken: string): void {
  window.localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  window.localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

/**
 * 保存当前用户信息。
 */
export function setStoredUser(user: UserInfoRespVO): void {
  window.localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/**
 * 读取当前用户信息，异常 JSON 会自动清理，避免应用持续读坏数据。
 */
export function getStoredUser(): UserInfoRespVO | null {
  const value = window.localStorage.getItem(USER_KEY)
  if (!value) {
    return null
  }
  try {
    return JSON.parse(value) as UserInfoRespVO
  } catch {
    window.localStorage.removeItem(USER_KEY)
    return null
  }
}

/**
 * 清理全部登录态。
 */
export function clearAuthStorage(): void {
  window.localStorage.removeItem(ACCESS_TOKEN_KEY)
  window.localStorage.removeItem(REFRESH_TOKEN_KEY)
  window.localStorage.removeItem(USER_KEY)
  window.localStorage.removeItem(LEGACY_ASSISTANT_SESSION_KEY)
  clearProfilePrompted()
}

/**
 * 本次登录是否已经提示过求职目标未填写。
 */
export function getProfilePrompted(): boolean {
  return window.sessionStorage.getItem(PROFILE_PROMPTED_KEY) === '1'
}

/**
 * 标记本次登录已经提示过，保证同一个登录会话内最多弹一次提醒窗。
 */
export function setProfilePrompted(): void {
  window.sessionStorage.setItem(PROFILE_PROMPTED_KEY, '1')
}

/**
 * 清除提醒标记：登录成功与档案填写完成后都要调用，保证下次登录还会提醒。
 */
export function clearProfilePrompted(): void {
  window.sessionStorage.removeItem(PROFILE_PROMPTED_KEY)
}
