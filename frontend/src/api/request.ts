import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'

import type { Result } from '@/types/api'
import type { AuthRespVO } from '@/types/auth'
import {
  clearAuthStorage,
  getAccessToken,
  getRefreshToken,
  setStoredUser,
  setTokens,
} from '@/utils/storage'

interface RetryableRequestConfig extends InternalAxiosRequestConfig {
  /** 标记请求是否已因 401 重试过一次，避免无限刷新。 */
  _retry?: boolean
}

// baseURL 优先读取环境变量，本地开发默认走 Vite 的 /api 代理。
const baseURL = import.meta.env.VITE_API_BASE || '/api'

const request = axios.create({
  baseURL,
  timeout: 10000,
})

const refreshClient = axios.create({
  baseURL,
  timeout: 10000,
})

let refreshPromise: Promise<string> | null = null

/**
 * 登录和刷新接口不能触发自动刷新，否则会在失效令牌上形成循环。
 */
function isAuthEntry(url: string | undefined): boolean {
  return Boolean(url?.includes('/auth/login') || url?.includes('/auth/refresh'))
}

/**
 * 清理登录态并跳转登录页。
 */
function redirectToLogin(): void {
  clearAuthStorage()
  if (window.location.pathname !== '/login') {
    window.location.replace('/login')
  }
}

/**
 * 使用 Refresh Token 换取新 Access Token。
 * 并发 401 会复用同一个刷新 Promise，避免同时发起多次轮换。
 */
async function refreshAccessToken(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new Error('刷新令牌不存在')
  }
  if (!refreshPromise) {
    refreshPromise = refreshClient
      .post<Result<AuthRespVO>>('/auth/refresh', { refreshToken })
      .then((response) => {
        const result = response.data
        if (result.code !== 200) {
          throw new Error(result.msg)
        }
        setTokens(result.data.accessToken, result.data.refreshToken)
        setStoredUser(result.data.user)
        return result.data.accessToken
      })
      .catch((error: unknown) => {
        redirectToLogin()
        throw error
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

// 每个业务请求都从统一的存储工具读取最新 Access Token。
request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const accessToken = getAccessToken()
  if (accessToken) {
    config.headers.set('Authorization', `Bearer ${accessToken}`)
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const result = response.data as Result<unknown>
    // 后端 HTTP 200 但业务码非 200 时，统一按失败响应处理。
    if (result.code !== 200) {
      ElMessage.error(result.msg)
      return Promise.reject(new Error(result.msg))
    }
    return response
  },
  async (error: AxiosError<Result<unknown>>) => {
    const config = error.config as RetryableRequestConfig | undefined
    const status = error.response?.status
    const shouldRefresh = status === 401
      && config
      && !config._retry
      && !isAuthEntry(config.url)

    if (shouldRefresh) {
      config._retry = true
      try {
        // 刷新成功后重放原请求，业务层无需感知 token 轮换。
        const accessToken = await refreshAccessToken()
        config.headers.set('Authorization', `Bearer ${accessToken}`)
        return request(config)
      } catch {
        redirectToLogin()
        return Promise.reject(error)
      }
    }

    if (status === 401 && !isAuthEntry(config?.url)) {
      redirectToLogin()
    }

    const message = error.response?.data?.msg || error.message || '请求失败'
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

/**
 * 解包统一响应，向 API 层返回业务数据。
 */
function unwrap<T>(result: Result<T>): T {
  if (result.code !== 200) {
    throw new Error(result.msg)
  }
  return result.data
}

/**
 * 发送 GET 请求并返回业务数据。
 */
export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const response = await request.get<Result<T>>(url, config)
  return unwrap(response.data)
}

/**
 * 发送 POST 请求并返回业务数据。
 */
export async function post<T>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  const response = await request.post<Result<T>>(url, data, config)
  return unwrap(response.data)
}

export default request
