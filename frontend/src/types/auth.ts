/**
 * 用户注册请求。
 */
export interface UserRegisterReqVO {
  username: string
  password: string
  nickname: string
}

/**
 * 用户登录请求。
 */
export interface UserLoginReqVO {
  username: string
  password: string
}

/**
 * 刷新令牌请求。
 */
export interface TokenRefreshReqVO {
  refreshToken: string
}

/**
 * 用户信息。
 */
export interface UserInfoRespVO {
  id: number
  username: string
  nickname: string
}

/**
 * 登录响应。
 */
export interface AuthRespVO {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: UserInfoRespVO
}
