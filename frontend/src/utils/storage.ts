import type { UserInfoRespVO } from '@/types/auth'

const ACCESS_TOKEN_KEY = 'career_token'
const REFRESH_TOKEN_KEY = 'career_refresh_token'
const USER_KEY = 'career_user'

export function getAccessToken(): string | null {
  return window.localStorage.getItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return window.localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setTokens(accessToken: string, refreshToken: string): void {
  window.localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  window.localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function setStoredUser(user: UserInfoRespVO): void {
  window.localStorage.setItem(USER_KEY, JSON.stringify(user))
}

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

export function clearAuthStorage(): void {
  window.localStorage.removeItem(ACCESS_TOKEN_KEY)
  window.localStorage.removeItem(REFRESH_TOKEN_KEY)
  window.localStorage.removeItem(USER_KEY)
}
