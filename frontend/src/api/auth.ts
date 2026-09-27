import { get, post } from '@/api/request'
import type {
  AuthRespVO,
  TokenRefreshReqVO,
  UserInfoRespVO,
  UserLoginReqVO,
  UserRegisterReqVO,
} from '@/types/auth'

/**
 * 注册并直接登录。
 */
export function register(data: UserRegisterReqVO): Promise<AuthRespVO> {
  return post<AuthRespVO>('/auth/register', data)
}

/**
 * 用户登录。
 */
export function login(data: UserLoginReqVO): Promise<AuthRespVO> {
  return post<AuthRespVO>('/auth/login', data)
}

/**
 * 刷新并轮换 token。
 */
export function refreshToken(data: TokenRefreshReqVO): Promise<AuthRespVO> {
  return post<AuthRespVO>('/auth/refresh', data)
}

/**
 * 获取当前登录用户。
 */
export function getCurrentUser(): Promise<UserInfoRespVO> {
  return get<UserInfoRespVO>('/auth/info')
}

/**
 * 退出当前登录会话。
 */
export function logout(): Promise<void> {
  return post<void>('/auth/logout')
}
