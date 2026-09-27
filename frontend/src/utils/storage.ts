import type { UserInfoRespVO } from '@/types/auth'

// 存储 key 是本模块冻结约定，页面不能自行拼写。
const ACCESS_TOKEN_KEY = 'career_token'
const REFRESH_TOKEN_KEY = 'career_refresh_token'
const USER_KEY = 'career_user'

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
}
