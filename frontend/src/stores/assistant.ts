import { computed, reactive, ref, watch } from 'vue'
import { defineStore } from 'pinia'

import * as assistantApi from '@/api/assistant'
import { useSessionStore } from '@/stores/session'
import type {
  AssistantContentEvent,
  AssistantErrorEvent,
  AssistantMessageRespVO,
  AssistantResultEvent,
  AssistantToolEvent,
  ChatMessage,
} from '@/types/assistant'

/** 消息分页每页条数，与后端默认值保持一致。 */
const MESSAGE_PAGE_SIZE = 20

/** 单个会话的消息区状态。 */
interface SessionChatState {
  /** 消息区展示的消息，按时间正序（最新在最下）。 */
  messages: ChatMessage[]
  /** 该会话是否有在途的流式请求。 */
  streaming: boolean
  /** 首屏历史加载中。 */
  loading: boolean
  /** 更早的历史加载中。 */
  loadingMoreHistory: boolean
  /** 已加载到第几页历史，0 表示还没加载过。 */
  loadedPageNum: number
  /** 服务端返回的历史消息总数。 */
  total: number
  /** 该会话自己的错误提示。 */
  errorMessage: string
}

/**
 * 通用助手对话状态。
 *
 * 会话 ID 取自 session store（草稿态为 null，由页面先创建会话再发消息）；消息接口按 ID 倒序分页，
 * 第 1 页就是最新的若干条，这里统一反转为「旧在上、新在下」，向上滚动时向前追加更早的消息。
 *
 * 消息区按会话分别缓存：用户切到别的会话时，原来那一轮继续流式接收（不中断后端推理），
 * 切回来还能看到正在生成或刚生成完的内容，也不会把旧会话的增量渲染到当前会话里。
 */
export const useAssistantStore = defineStore('assistant', () => {
  const sessionStore = useSessionStore()

  /** 当前会话 ID，草稿态为 null。 */
  const sessionId = computed(() => sessionStore.currentSessionId)

  /** 各会话的消息区状态。 */
  const states = reactive<Record<string, SessionChatState>>({})

  /** 草稿态（尚未创建会话）的临时消息区。 */
  const draftState = reactive<SessionChatState>(createEmptyState())

  /** 会话状态缺失时的占位，避免在 computed 里写响应式对象。 */
  const emptyState = createEmptyState()

  /** 各会话在途的流式请求句柄：只有退出登录、删除会话时才主动中断。 */
  const controllers = new Map<string, AbortController>()

  /**
   * 取（必要时创建）指定会话的消息区状态。
   *
   * @param targetSessionId 会话 ID
   * @returns 该会话的消息区状态
   */
  function stateOf(targetSessionId: string): SessionChatState {
    if (!states[targetSessionId]) {
      states[targetSessionId] = createEmptyState()
    }
    return states[targetSessionId]
  }

  /** 从其它页面带过来的待发指令（例如简历列表的「诊断」按钮）。 */
  const pendingCommand = ref<string | null>(null)

  // 会话切换后立刻建立它自己的状态对象，后续读写都不会落到草稿态上。
  watch(sessionId, (current) => {
    if (current) {
      stateOf(current)
    }
  }, { immediate: true })

  /** 当前会话的状态对象。 */
  const currentState = computed(() =>
    sessionId.value ? states[sessionId.value] ?? emptyState : draftState,
  )

  /** 当前会话的消息区展示的消息。 */
  const messages = computed(() => currentState.value.messages)

  /** 当前会话是否正在流式接收回复。 */
  const streaming = computed(() => currentState.value.streaming)

  /** 当前会话首屏历史加载中。 */
  const loading = computed(() => currentState.value.loading)

  /** 当前会话更早的历史加载中。 */
  const loadingMoreHistory = computed(() => currentState.value.loadingMoreHistory)

  /** 当前会话服务端返回的历史消息总数。 */
  const messageTotal = computed(() => currentState.value.total)

  /** 当前会话的错误提示。 */
  const errorMessage = computed(() => currentState.value.errorMessage)

  /** 当前会话是否还有更早的历史消息。 */
  const hasMoreMessages = computed(() => messages.value.length < messageTotal.value)

  /**
   * 加载当前会话最新一页消息。
   */
  async function loadHistory(): Promise<void> {
    const currentSessionId = sessionId.value
    if (!currentSessionId) {
      resetDraft()
      return
    }
    const state = stateOf(currentSessionId)
    state.loading = true
    state.errorMessage = ''
    try {
      const page = await assistantApi.listMessages(currentSessionId, 1, MESSAGE_PAGE_SIZE)
      // 加载期间该会话可能已经开始新一轮流式（例如用户切回来时刚好在生成），此时不能冲掉内存里的消息。
      if (!state.streaming) {
        // 接口按消息 ID 倒序返回，反转后最新的消息落在最下方。
        state.messages = toChatMessages(page.records).reverse()
        state.loadedPageNum = 1
      }
      state.total = page.total
    } finally {
      state.loading = false
    }
  }

  /**
   * 向上滚动时加载更早的消息，结果插入到列表头部。
   */
  async function loadOlderMessages(): Promise<void> {
    const currentSessionId = sessionId.value
    if (!currentSessionId) {
      return
    }
    const state = stateOf(currentSessionId)
    if (state.loading || state.loadingMoreHistory || state.messages.length >= state.total) {
      return
    }
    state.loadingMoreHistory = true
    try {
      const nextPageNum = state.loadedPageNum + 1
      const page = await assistantApi.listMessages(currentSessionId, nextPageNum, MESSAGE_PAGE_SIZE)
      const older = toChatMessages(page.records).reverse()
      state.messages = dedupeMessages([...older, ...state.messages])
      state.loadedPageNum = nextPageNum
      state.total = page.total
    } finally {
      state.loadingMoreHistory = false
    }
  }

  /**
   * 发送一条消息并流式接收回复。
   *
   * @param content 用户输入
   */
  async function sendMessage(content: string): Promise<void> {
    const text = content.trim()
    const currentSessionId = sessionId.value
    if (!text || !currentSessionId) {
      return
    }
    const state = stateOf(currentSessionId)
    if (state.streaming) {
      return
    }
    state.errorMessage = ''
    state.messages.push({
      id: createMessageId(),
      role: 'USER',
      content: text,
      thinking: '',
      tools: [],
      streaming: false,
      failed: false,
      result: null,
    })
    const reply: ChatMessage = {
      id: createMessageId(),
      role: 'ASSISTANT',
      content: '',
      thinking: '',
      tools: [],
      streaming: true,
      failed: false,
      result: null,
    }
    state.messages.push(reply)
    // 必须取回数组中的响应式代理再写入：直接改 push 进去的原始对象不会触发 Vue 更新，
    // 会导致流式增量不渲染、界面上看不到任何结果。
    const replyRef = state.messages[state.messages.length - 1]
    state.streaming = true
    const controller = new AbortController()
    controllers.set(currentSessionId, controller)
    let terminated = false
    try {
      await assistantApi.chat(
        { sessionId: currentSessionId, content: text },
        (event, data) => {
          if (event === 'meta') {
            // meta 到达说明用户消息已落库，此时左侧标题已按首条消息改写。
            void sessionStore.refreshSession(currentSessionId)
          }
          if (event === 'done' || event === 'error') {
            terminated = true
          }
          // 事件写进该会话自己的消息区：即使用户已经切走，这一轮也会继续接收完。
          applyEvent(state, replyRef, event, data)
        },
        controller.signal,
      )
    } catch (error) {
      if (!controller.signal.aborted) {
        replyRef.failed = true
        state.errorMessage = error instanceof Error ? error.message : '对话失败'
        throw error
      }
      // 主动中断（退出登录、删除会话）不算失败：已经收到的内容留在该会话的消息区里。
    } finally {
      replyRef.streaming = false
      state.streaming = false
      if (controllers.get(currentSessionId) === controller) {
        controllers.delete(currentSessionId)
      }
      if (!terminated && !replyRef.failed && !controller.signal.aborted) {
        // 服务端没有给出 done / error 事件就断开，明确提示而不是让界面看起来"没有结果"。
        replyRef.failed = true
        state.errorMessage = '连接意外中断，请重试'
      }
    }
  }

  /**
   * 中断在途流式请求。
   *
   * 只在退出登录（全部中断）或删除某个会话（按会话中断）时调用；普通切换会话不中断，
   * 让原来那一轮继续跑完并落库，切回去还能看到完整回答。
   *
   * @param targetSessionId 指定会话 ID，不传表示中断全部在途请求
   */
  function abortStreaming(targetSessionId?: string): void {
    if (targetSessionId) {
      controllers.get(targetSessionId)?.abort()
      controllers.delete(targetSessionId)
      return
    }
    controllers.forEach((controller) => controller.abort())
    controllers.clear()
  }

  /**
   * 判断指定会话是否有在途的流式请求。
   *
   * @param targetSessionId 会话 ID
   */
  function isStreaming(targetSessionId: string): boolean {
    return states[targetSessionId]?.streaming === true
  }

  /**
   * 丢弃某个会话的本地缓存（删除会话时调用）。
   *
   * @param targetSessionId 会话 ID
   */
  function releaseSession(targetSessionId: string): void {
    abortStreaming(targetSessionId)
    delete states[targetSessionId]
  }

  /**
   * 清空草稿态消息区。
   */
  function resetDraft(): void {
    Object.assign(draftState, createEmptyState())
  }

  /**
   * 退出登录或切换账号时重置。
   */
  function reset(): void {
    abortStreaming()
    Object.keys(states).forEach((key) => delete states[key])
    resetDraft()
    clearPendingCommand()
  }

  /**
   * 排队一条待发指令。
   *
   * 简历列表点「诊断」时用：先建会话再跳转，等会话历史加载完成后由面板自动发出这条指令，
   * 避免在会话切换的异步间隙里把消息发到旧会话上。
   *
   * @param command 待发指令
   */
  function queuePendingCommand(command: string): void {
    pendingCommand.value = command
  }

  /**
   * 清空待发指令，避免同一条指令被重复发送。
   */
  function clearPendingCommand(): void {
    pendingCommand.value = null
  }

  /**
   * 按事件名把 SSE 事件应用到当前回复消息上。
   *
   * @param state 该回复所属会话的消息区状态
   * @param message 当前回复消息
   * @param event 事件名
   * @param data 事件数据
   */
  function applyEvent(state: SessionChatState, message: ChatMessage, event: string, data: string): void {
    try {
      switch (event) {
        case 'delta':
          message.content += (JSON.parse(data) as AssistantContentEvent).content
          break
        // 思考内容与工具调用只做记录，不在界面展示（产品要求不向终端用户暴露内部过程）。
        case 'thinking':
          message.thinking += (JSON.parse(data) as AssistantContentEvent).content
          break
        case 'tool': {
          const tool = JSON.parse(data) as AssistantToolEvent
          message.tools.push({ name: tool.name, status: tool.status, detail: tool.detail })
          break
        }
        case 'node':
          message.tools.push({
            name: (JSON.parse(data) as AssistantContentEvent).content,
            status: 'START',
            detail: '子智能体',
          })
          break
        case 'result': {
          // 结构化产物（当前是简历诊断结论）：挂在当前回复上，由消息气泡渲染诊断卡片。
          const resultEvent = JSON.parse(data) as AssistantResultEvent
          if (resultEvent.data) {
            message.result = resultEvent.data
          }
          break
        }
        case 'error': {
          const errorEvent = JSON.parse(data) as AssistantErrorEvent
          message.failed = true
          state.errorMessage = errorEvent.message
          break
        }
        default:
          // meta / done 事件不需要额外处理：流结束由 store 统一收尾。
          break
      }
    } catch {
      state.errorMessage = '解析流式响应失败'
      message.failed = true
    }
  }

  return {
    sessionId,
    messages,
    streaming,
    loading,
    loadingMoreHistory,
    messageTotal,
    errorMessage,
    pendingCommand,
    hasMoreMessages,
    isStreaming,
    loadHistory,
    loadOlderMessages,
    sendMessage,
    queuePendingCommand,
    clearPendingCommand,
    abortStreaming,
    releaseSession,
    resetDraft,
    reset,
  }
})

/**
 * 生成前端消息 ID。
 *
 * 历史消息使用后端消息 ID，流式消息只在前端临时存在，用随机串避免 key 冲突。
 */
function createMessageId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `message-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

/**
 * 把历史消息转换为页面消息。
 *
 * @param message 历史消息
 */
function toChatMessage(message: AssistantMessageRespVO): ChatMessage {
  return {
    id: String(message.id),
    role: message.role,
    content: message.content,
    thinking: '',
    tools: [],
    streaming: false,
    failed: false,
    result: null,
  }
}

/**
 * 把历史消息列表转换为页面消息列表。
 *
 * @param records 历史消息列表
 */
function toChatMessages(records: AssistantMessageRespVO[]): ChatMessage[] {
  return records.map(toChatMessage)
}

/**
 * 按消息 ID 去重，避免分页边界重复渲染同一条消息。
 *
 * @param records 页面消息列表
 */
function dedupeMessages(records: ChatMessage[]): ChatMessage[] {
  const seen = new Set<string>()
  return records.filter((item) => {
    if (seen.has(item.id)) {
      return false
    }
    seen.add(item.id)
    return true
  })
}

/**
 * 创建空的会话消息区状态。
 *
 * @returns 空的会话消息区状态
 */
function createEmptyState(): SessionChatState {
  return {
    messages: [],
    streaming: false,
    loading: false,
    loadingMoreHistory: false,
    loadedPageNum: 0,
    total: 0,
    errorMessage: '',
  }
}
