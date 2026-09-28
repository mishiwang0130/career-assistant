import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import * as authApi from '@/api/auth'
import { useUserStore } from '@/stores/user'

// 接口层整体打桩，单测不依赖网络与后端。
vi.mock('@/api/auth')

/** 本次登录是否已提示过求职目标，与技术约定中的 key 保持一致。 */
const PROFILE_PROMPTED_KEY = 'career_profile_prompted'

/** 登录响应桩数据。 */
const AUTH_RESPONSE = {
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  tokenType: 'Bearer',
  expiresIn: 7200,
  user: { id: 1, username: 'alice', nickname: 'Alice' },
}

describe('登录态与求职目标提示标记', () => {
  beforeEach(() => {
    window.localStorage.clear()
    window.sessionStorage.clear()
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('登录成功清除上一次登录留下的提示标记，保证本次登录重新提醒', async () => {
    window.sessionStorage.setItem(PROFILE_PROMPTED_KEY, '1')
    vi.mocked(authApi.login).mockResolvedValue(AUTH_RESPONSE)

    await useUserStore().login({ username: 'alice', password: 'secret1' })

    expect(window.sessionStorage.getItem(PROFILE_PROMPTED_KEY)).toBeNull()
  })

  it('注册并登录成功后同样清除提示标记', async () => {
    window.sessionStorage.setItem(PROFILE_PROMPTED_KEY, '1')
    vi.mocked(authApi.register).mockResolvedValue(AUTH_RESPONSE)

    await useUserStore().register({ username: 'alice', password: 'secret1', nickname: 'Alice' })

    expect(window.sessionStorage.getItem(PROFILE_PROMPTED_KEY)).toBeNull()
  })

  it('退出登录清理提示标记，避免同标签页切换账号后串状态', async () => {
    vi.mocked(authApi.login).mockResolvedValue(AUTH_RESPONSE)
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
    const userStore = useUserStore()
    await userStore.login({ username: 'alice', password: 'secret1' })
    window.sessionStorage.setItem(PROFILE_PROMPTED_KEY, '1')

    await userStore.logout()

    expect(window.sessionStorage.getItem(PROFILE_PROMPTED_KEY)).toBeNull()
  })
})
