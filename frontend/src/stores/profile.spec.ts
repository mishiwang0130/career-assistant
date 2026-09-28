import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import * as userProfileApi from '@/api/userProfile'
import { useProfileStore } from '@/stores/profile'

// 接口层整体打桩，单测不依赖网络、后端与登录态。
vi.mock('@/api/userProfile')

/** 本次登录是否已提示过求职目标，与技术约定中的 key 保持一致。 */
const PROFILE_PROMPTED_KEY = 'career_profile_prompted'

describe('求职目标状态与登录提醒窗', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.localStorage.clear()
    setActivePinia(createPinia())
    vi.resetAllMocks()
  })

  it('未填写时弹窗可见，并写入本次登录的提示标记', async () => {
    vi.mocked(userProfileApi.getUserProfile).mockResolvedValue(null)
    const profileStore = useProfileStore()

    await profileStore.checkLoginPrompt()

    expect(profileStore.filled).toBe(false)
    expect(profileStore.promptDialogVisible).toBe(true)
    expect(window.sessionStorage.getItem(PROFILE_PROMPTED_KEY)).toBe('1')
  })

  it('同一次登录已经提示过时不再弹窗，也不再重复请求接口', async () => {
    window.sessionStorage.setItem(PROFILE_PROMPTED_KEY, '1')
    const profileStore = useProfileStore()

    await profileStore.checkLoginPrompt()

    expect(profileStore.promptDialogVisible).toBe(false)
    expect(userProfileApi.getUserProfile).not.toHaveBeenCalled()
  })

  it('接口探测失败时静默跳过，既不弹窗也不写标记', async () => {
    vi.mocked(userProfileApi.getUserProfile).mockRejectedValue(new Error('网络异常'))
    const profileStore = useProfileStore()

    await expect(profileStore.checkLoginPrompt()).resolves.toBeUndefined()

    expect(profileStore.promptDialogVisible).toBe(false)
    expect(window.sessionStorage.getItem(PROFILE_PROMPTED_KEY)).toBeNull()
  })

  it('已填写时不弹窗', async () => {
    vi.mocked(userProfileApi.getUserProfile).mockResolvedValue({
      targetPosition: '测试开发',
      workYears: 2,
    })
    const profileStore = useProfileStore()

    await profileStore.checkLoginPrompt()

    expect(profileStore.filled).toBe(true)
    expect(profileStore.promptDialogVisible).toBe(false)
    expect(window.sessionStorage.getItem(PROFILE_PROMPTED_KEY)).toBeNull()
  })

  it('保存成功后置为已填写、关闭弹窗并清掉提示标记', async () => {
    vi.mocked(userProfileApi.getUserProfile).mockResolvedValue(null)
    vi.mocked(userProfileApi.saveUserProfile).mockResolvedValue({
      targetPosition: '后端开发',
      workYears: 3,
    })
    const profileStore = useProfileStore()
    await profileStore.checkLoginPrompt()
    expect(profileStore.promptDialogVisible).toBe(true)

    await profileStore.saveProfile({ targetPosition: '后端开发', workYears: 3 })

    expect(profileStore.filled).toBe(true)
    expect(profileStore.profile).toEqual({ targetPosition: '后端开发', workYears: 3 })
    expect(profileStore.promptDialogVisible).toBe(false)
    expect(window.sessionStorage.getItem(PROFILE_PROMPTED_KEY)).toBeNull()
  })

  it('退出登录重置后，下次登录会重新检查并弹窗', async () => {
    vi.mocked(userProfileApi.getUserProfile).mockResolvedValue(null)
    const profileStore = useProfileStore()
    await profileStore.checkLoginPrompt()

    profileStore.reset()
    window.sessionStorage.clear()
    await profileStore.checkLoginPrompt()

    expect(profileStore.promptDialogVisible).toBe(true)
  })
})
