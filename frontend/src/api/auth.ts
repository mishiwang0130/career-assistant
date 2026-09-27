import { get, post } from '@/api/request'
import type {
  AuthRespVO,
  TokenRefreshReqVO,
  UserInfoRespVO,
  UserLoginReqVO,
  UserRegisterReqVO,
} from '@/types/auth'

export function register(data: UserRegisterReqVO): Promise<AuthRespVO> {
  return post<AuthRespVO>('/auth/register', data)
}

export function login(data: UserLoginReqVO): Promise<AuthRespVO> {
  return post<AuthRespVO>('/auth/login', data)
}

export function refreshToken(data: TokenRefreshReqVO): Promise<AuthRespVO> {
  return post<AuthRespVO>('/auth/refresh', data)
}

export function getCurrentUser(): Promise<UserInfoRespVO> {
  return get<UserInfoRespVO>('/auth/info')
}

export function logout(): Promise<void> {
  return post<void>('/auth/logout')
}
