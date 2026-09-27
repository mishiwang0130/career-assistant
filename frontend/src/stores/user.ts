import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import type {
  AuthRespVO,
  UserInfoRespVO,
  UserLoginReqVO,
  UserRegisterReqVO,
} from '@/types/auth'
import {
  clearAuthStorage,
  getAccessToken,
  getRefreshToken,
  getStoredUser,
  setStoredUser,
  setTokens,
} from '@/utils/storage'

export const useUserStore = defineStore('user', () => {
  const accessToken = ref<string | null>(getAccessToken())
  const refreshToken = ref<string | null>(getRefreshToken())
  const user = ref<UserInfoRespVO | null>(getStoredUser())

  const isLoggedIn = computed(() => Boolean(accessToken.value))

  function persistAuth(data: AuthRespVO): void {
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    user.value = data.user
    setTokens(data.accessToken, data.refreshToken)
    setStoredUser(data.user)
  }

  async function login(reqVO: UserLoginReqVO): Promise<void> {
    persistAuth(await authApi.login(reqVO))
  }

  async function register(reqVO: UserRegisterReqVO): Promise<void> {
    persistAuth(await authApi.register(reqVO))
  }

  async function fetchCurrentUser(): Promise<void> {
    const currentUser = await authApi.getCurrentUser()
    user.value = currentUser
    setStoredUser(currentUser)
    accessToken.value = getAccessToken()
    refreshToken.value = getRefreshToken()
  }

  async function refreshSession(): Promise<void> {
    if (!refreshToken.value) {
      clearAuth()
      throw new Error('刷新令牌不存在')
    }
    persistAuth(await authApi.refreshToken({ refreshToken: refreshToken.value }))
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 退出接口失败不阻塞本地登录态清理。
    } finally {
      clearAuth()
    }
  }

  function clearAuth(): void {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
    clearAuthStorage()
  }

  return {
    accessToken,
    refreshToken,
    user,
    isLoggedIn,
    login,
    register,
    fetchCurrentUser,
    refreshSession,
    logout,
    clearAuth,
  }
})
