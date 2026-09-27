export interface UserRegisterReqVO {
  username: string
  password: string
  nickname: string
}

export interface UserLoginReqVO {
  username: string
  password: string
}

export interface TokenRefreshReqVO {
  refreshToken: string
}

export interface UserInfoRespVO {
  id: number
  username: string
  nickname: string
}

export interface AuthRespVO {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: UserInfoRespVO
}
