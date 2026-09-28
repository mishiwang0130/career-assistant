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
  clearProfilePrompted,
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

  /**
   * 同时更新内存状态和 localStorage。
   */
  function persistAuth(data: AuthRespVO): void {
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    user.value = data.user
    setTokens(data.accessToken, data.refreshToken)
    setStoredUser(data.user)
  }

  /**
   * 登录并持久化登录态。
   */
  async function login(reqVO: UserLoginReqVO): Promise<void> {
    persistAuth(await authApi.login(reqVO))
    // 每次登录都要重新提示一次求职目标未填写，因此登录成功后清掉上一次登录的标记。
    clearProfilePrompted()
  }

  /**
   * 注册并持久化登录态。
   */
  async function register(reqVO: UserRegisterReqVO): Promise<void> {
    persistAuth(await authApi.register(reqVO))
    clearProfilePrompted()
  }

  /**
   * 拉取当前用户，并同步请求层可能刷新过的新 token。
   */
  async function fetchCurrentUser(): Promise<void> {
    const currentUser = await authApi.getCurrentUser()
    user.value = currentUser
    setStoredUser(currentUser)
    accessToken.value = getAccessToken()
    refreshToken.value = getRefreshToken()
  }

  /**
   * 手动刷新当前会话，主要供显式业务调用。
   */
  async function refreshSession(): Promise<void> {
    if (!refreshToken.value) {
      clearAuth()
      throw new Error('刷新令牌不存在')
    }
    persistAuth(await authApi.refreshToken({ refreshToken: refreshToken.value }))
  }

  /**
   * 退出登录；即使后端撤销失败，也必须清理本地状态。
   */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // 退出接口失败不阻塞本地登录态清理。
    } finally {
      clearAuth()
    }
  }

  /**
   * 清理内存和本地存储中的登录态。
   */
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
