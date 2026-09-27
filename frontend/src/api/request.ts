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
  _retry?: boolean
}

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

function isAuthEntry(url: string | undefined): boolean {
  return Boolean(url?.includes('/auth/login') || url?.includes('/auth/refresh'))
}

function redirectToLogin(): void {
  clearAuthStorage()
  if (window.location.pathname !== '/login') {
    window.location.replace('/login')
  }
}

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

function unwrap<T>(result: Result<T>): T {
  if (result.code !== 200) {
    throw new Error(result.msg)
  }
  return result.data
}

export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const response = await request.get<Result<T>>(url, config)
  return unwrap(response.data)
}

export async function post<T>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig,
): Promise<T> {
  const response = await request.post<Result<T>>(url, data, config)
  return unwrap(response.data)
}

export default request
