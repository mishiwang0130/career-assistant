import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as userProfileApi from '@/api/userProfile'
import type { UserProfileRespVO, UserProfileSaveReqVO } from '@/types/userProfile'
import {
  clearProfilePrompted,
  getProfilePrompted,
  setProfilePrompted,
} from '@/utils/storage'

/**
 * 求职目标状态。
 *
 * 「是否已填写」是登录提醒窗、侧栏红点与空会话引导卡片共用的唯一判断来源：三处都读这份状态，
 * 不再各自发请求、不再各写一套判断逻辑。接口查询失败一律静默处理，不影响用户进入页面。
 */
export const useProfileStore = defineStore('profile', () => {
  /** 当前登录用户的求职目标，null 表示未填写。 */
  const profile = ref<UserProfileRespVO | null>(null)

  /** 是否已经向后端取过一次（无论填写与否），用于避免重复请求。 */
  const loaded = ref(false)

  /** 登录提醒窗是否可见。 */
  const promptDialogVisible = ref(false)

  /** 是否已填写求职目标。 */
  const filled = computed(() => profile.value !== null)

  /**
   * 拉取当前用户的求职目标并写回状态。
   */
  async function loadProfile(): Promise<void> {
    profile.value = await userProfileApi.getUserProfile()
    loaded.value = true
  }

  /**
   * 保证已加载过一次；探测失败只保留未加载状态，由下次进入应用壳时重试。
   */
  async function ensureLoaded(): Promise<void> {
    if (loaded.value) {
      return
    }
    try {
      await loadProfile()
    } catch {
      // 探测失败不影响进入页面，也不弹错误提示。
    }
  }

  /**
   * 登录进入应用壳后检查一次：只有未填写才弹提醒窗。
   *
   * 标记在弹窗出现时立即写入 sessionStorage，保证同一个登录会话内最多弹一次（弹窗期间刷新页面也不重复弹）；
   * 接口异常时静默跳过，不弹错误窗、不阻塞进入页面。
   */
  async function checkLoginPrompt(): Promise<void> {
    if (getProfilePrompted()) {
      return
    }
    await ensureLoaded()
    if (!loaded.value || filled.value) {
      return
    }
    setProfilePrompted()
    promptDialogVisible.value = true
  }

  /**
   * 保存求职目标，成功后置为已填写。
   *
   * @param reqVO 保存请求
   * @returns 保存后的求职目标
   */
  async function saveProfile(reqVO: UserProfileSaveReqVO): Promise<UserProfileRespVO> {
    const saved = await userProfileApi.saveUserProfile(reqVO)
    markFilled(saved)
    return saved
  }

  /**
   * 置为已填写：刷新侧栏红点、关闭提醒窗，并清掉本次登录的提醒标记。
   *
   * @param saved 保存后的求职目标
   */
  function markFilled(saved: UserProfileRespVO): void {
    profile.value = saved
    loaded.value = true
    promptDialogVisible.value = false
    clearProfilePrompted()
  }

  /**
   * 关闭登录提醒窗（「稍后再说」与「去填写」都是关闭，不阻塞任何操作）。
   */
  function closePrompt(): void {
    promptDialogVisible.value = false
  }

  /**
   * 退出登录或切换账号时重置，避免下一个账号看到上一个账号的档案状态。
   */
  function reset(): void {
    profile.value = null
    loaded.value = false
    promptDialogVisible.value = false
  }

  return {
    profile,
    loaded,
    promptDialogVisible,
    filled,
    loadProfile,
    ensureLoaded,
    checkLoginPrompt,
    saveProfile,
    markFilled,
    closePrompt,
    reset,
  }
})
