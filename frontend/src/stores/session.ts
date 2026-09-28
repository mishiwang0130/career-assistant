import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as sessionApi from '@/api/session'
import { SESSION_PAGE_SIZE } from '@/api/session'
import type { ChatScene, ChatSessionRespVO } from '@/types/session'

/**
 * 会话中心状态。
 *
 * 负责会话列表的滚动分页、当前会话、草稿态与增删改。会话 ID 全部来自后端，
 * 这里不做任何本地持久化：刷新页面后默认回到新建会话草稿态，历史会话从接口重新拉取。
 */
export const useSessionStore = defineStore('session', () => {
  /** 已加载的会话列表，第 1 页永远是最新会话。 */
  const sessions = ref<ChatSessionRespVO[]>([])

  /** 服务端返回的会话总数，用于判断是否还有下一页。 */
  const total = ref(0)

  /** 已经加载到第几页，0 表示还没有加载过。 */
  const loadedPageNum = ref(0)

  /** 首屏列表加载中。 */
  const loading = ref(false)

  /** 加载下一页中。 */
  const loadingMore = ref(false)

  /** 当前会话 ID，null 表示新建会话的草稿态。 */
  const currentSessionId = ref<string | null>(null)

  /** 是否还有更早的会话可以加载。 */
  const hasMore = computed(() => sessions.value.length < total.value)

  /** 当前会话在列表中的元数据，草稿态或不在已加载分页时为 null。 */
  const currentSession = computed(
    () => sessions.value.find((item) => item.sessionId === currentSessionId.value) ?? null,
  )

  /**
   * 重新加载第 1 页会话列表。
   */
  async function loadSessions(): Promise<void> {
    loading.value = true
    try {
      const page = await sessionApi.listSessions(1, SESSION_PAGE_SIZE)
      sessions.value = dedupe(page.records)
      total.value = page.total
      loadedPageNum.value = 1
    } finally {
      loading.value = false
    }
  }

  /**
   * 向下滚动时加载更早的会话。
   */
  async function loadMoreSessions(): Promise<void> {
    if (loading.value || loadingMore.value || !hasMore.value) {
      return
    }
    // 列表还没加载过时先补首屏，避免出现第 1 页缺失的空档。
    if (loadedPageNum.value === 0) {
      await loadSessions()
      return
    }
    loadingMore.value = true
    try {
      const nextPageNum = loadedPageNum.value + 1
      const page = await sessionApi.listSessions(nextPageNum, SESSION_PAGE_SIZE)
      sessions.value = dedupe([...sessions.value, ...page.records])
      total.value = page.total
      loadedPageNum.value = nextPageNum
    } finally {
      loadingMore.value = false
    }
  }

  /**
   * 确保存在一个可用的会话：草稿态时创建新会话，已有会话时直接返回当前 ID。
   *
   * @param scene 会话场景
   * @returns 可用的会话 ID
   */
  async function ensureSession(scene: ChatScene): Promise<string> {
    if (currentSessionId.value) {
      return currentSessionId.value
    }
    const created = await sessionApi.createSession({ scene })
    applyCreated(created)
    currentSessionId.value = created.sessionId
    return created.sessionId
  }

  /**
   * 重命名会话，成功后用服务端返回的归一化标题就地替换列表项。
   *
   * @param sessionId 会话 ID
   * @param title 新标题
   */
  async function renameSession(sessionId: string, title: string): Promise<ChatSessionRespVO> {
    const updated = await sessionApi.renameSession(sessionId, { title })
    sessions.value = sessions.value.map((item) =>
      item.sessionId === sessionId ? updated : item,
    )
    return updated
  }

  /**
   * 删除会话，并从列表中移除。
   *
   * @param sessionId 会话 ID
   */
  async function deleteSession(sessionId: string): Promise<void> {
    await sessionApi.deleteSession(sessionId)
    removeLocally(sessionId)
  }

  /**
   * 首条消息落库后刷新会话元数据。
   *
   * 标题与活跃时间都会变化，该会话需要回到列表最前面，因此直接重拉第 1 页。
   *
   * @param sessionId 触发刷新的会话 ID，仅用于日志定位
   */
  async function refreshSession(sessionId: string): Promise<void> {
    try {
      await loadSessions()
    } catch {
      // 列表刷新失败不影响正在进行的对话，下一次加载会自行纠正。
      console.warn('会话列表刷新失败', sessionId)
    }
  }

  /**
   * 进入草稿态：清空当前会话 ID，不删除任何服务端数据。
   */
  function startDraft(): void {
    currentSessionId.value = null
  }

  /**
   * 设置当前会话 ID，null 表示草稿态。
   *
   * @param sessionId 会话 ID
   */
  function setCurrentSession(sessionId: string | null): void {
    currentSessionId.value = sessionId
  }

  /**
   * 退出登录或切换账号时重置，避免下一个账号看到上一个账号的会话列表。
   */
  function reset(): void {
    sessions.value = []
    total.value = 0
    loadedPageNum.value = 0
    loading.value = false
    loadingMore.value = false
    currentSessionId.value = null
  }

  /**
   * 把新建的会话插入列表头部并同步总数。
   *
   * @param session 新建的会话
   */
  function applyCreated(session: ChatSessionRespVO): void {
    if (sessions.value.some((item) => item.sessionId === session.sessionId)) {
      return
    }
    sessions.value = [session, ...sessions.value]
    total.value = Math.max(total.value + 1, sessions.value.length)
  }

  /**
   * 从本地列表移除会话并同步总数与当前会话。
   *
   * @param sessionId 会话 ID
   */
  function removeLocally(sessionId: string): void {
    const previousLength = sessions.value.length
    sessions.value = sessions.value.filter((item) => item.sessionId !== sessionId)
    if (sessions.value.length < previousLength) {
      total.value = Math.max(0, total.value - 1)
    }
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
    }
  }

  return {
    sessions,
    total,
    loading,
    loadingMore,
    currentSessionId,
    hasMore,
    currentSession,
    loadSessions,
    loadMoreSessions,
    ensureSession,
    renameSession,
    deleteSession,
    refreshSession,
    startDraft,
    setCurrentSession,
    reset,
  }
})

/**
 * 按会话 ID 去重，避免分页边界重复返回同一条会话。
 *
 * @param records 会话列表
 */
function dedupe(records: ChatSessionRespVO[]): ChatSessionRespVO[] {
  const seen = new Set<string>()
  return records.filter((item) => {
    if (seen.has(item.sessionId)) {
      return false
    }
    seen.add(item.sessionId)
    return true
  })
}
