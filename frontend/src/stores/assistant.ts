import { computed, ref } from 'vue'
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

/**
 * 通用助手对话状态。
 *
 * 会话 ID 取自 session store（草稿态为 null，由页面先创建会话再发消息）；消息接口按 ID 倒序分页，
 * 第 1 页就是最新的若干条，这里统一反转为「旧在上、新在下」，向上滚动时向前追加更早的消息。
 */
export const useAssistantStore = defineStore('assistant', () => {
  const sessionStore = useSessionStore()

  /** 当前会话 ID，草稿态为 null。 */
  const sessionId = computed(() => sessionStore.currentSessionId)

  /** 消息区展示的消息，按时间正序（最新在最下）。 */
  const messages = ref<ChatMessage[]>([])

  /** 是否正在流式接收回复。 */
  const streaming = ref(false)

  /** 首屏历史加载中。 */
  const loading = ref(false)

  /** 更早的历史加载中。 */
  const loadingMoreHistory = ref(false)

  /** 已加载到第几页历史，0 表示还没加载过。 */
  const loadedMessagePageNum = ref(0)

  /** 服务端返回的历史消息总数。 */
  const messageTotal = ref(0)

  /** 错误提示。 */
  const errorMessage = ref('')

  /** 从其它页面带过来的待发指令（例如简历列表的「诊断」按钮）。 */
  const pendingCommand = ref<string | null>(null)

  /** 是否还有更早的历史消息。 */
  const hasMoreMessages = computed(() => messages.value.length < messageTotal.value)

  /** 在途流式请求的取消句柄。 */
  let abortController: AbortController | null = null

  /** 在途流式请求所属的会话 ID，用于丢弃切换会话后到达的增量。 */
  let streamingSessionId: string | null = null

  /**
   * 加载当前会话最新一页消息。
   */
  async function loadHistory(): Promise<void> {
    const currentSessionId = sessionId.value
    if (!currentSessionId) {
      resetMessages()
      return
    }
    loading.value = true
    errorMessage.value = ''
    try {
      const page = await assistantApi.listMessages(currentSessionId, 1, MESSAGE_PAGE_SIZE)
      // 接口按消息 ID 倒序返回，反转后最新的消息落在最下方。
      messages.value = toChatMessages(page.records).reverse()
      loadedMessagePageNum.value = 1
      messageTotal.value = page.total
    } finally {
      loading.value = false
    }
  }

  /**
   * 向上滚动时加载更早的消息，结果插入到列表头部。
   */
  async function loadOlderMessages(): Promise<void> {
    const currentSessionId = sessionId.value
    if (!currentSessionId || loading.value || loadingMoreHistory.value || !hasMoreMessages.value) {
      return
    }
    loadingMoreHistory.value = true
    try {
      const nextPageNum = loadedMessagePageNum.value + 1
      const page = await assistantApi.listMessages(currentSessionId, nextPageNum, MESSAGE_PAGE_SIZE)
      const older = toChatMessages(page.records).reverse()
      messages.value = dedupeMessages([...older, ...messages.value])
      loadedMessagePageNum.value = nextPageNum
      messageTotal.value = page.total
    } finally {
      loadingMoreHistory.value = false
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
    if (!text || streaming.value || !currentSessionId) {
      return
    }
    errorMessage.value = ''
    messages.value.push({
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
    messages.value.push(reply)
    // 必须取回数组中的响应式代理再写入：直接改 push 进去的原始对象不会触发 Vue 更新，
    // 会导致流式增量不渲染、界面上看不到任何结果。
    const replyRef = messages.value[messages.value.length - 1]
    streaming.value = true
    streamingSessionId = currentSessionId
    abortController = new AbortController()
    const controller = abortController
    let terminated = false
    try {
      await assistantApi.chat(
        { sessionId: currentSessionId, content: text },
        (event, data) => {
          // 切换或删除会话后到达的事件必须丢弃，否则消息会串到别的会话。
          if (streamingSessionId !== currentSessionId) {
            return
          }
          if (event === 'meta') {
            // meta 到达说明用户消息已落库，此时左侧标题已按首条消息改写。
            void sessionStore.refreshSession(currentSessionId)
          }
          if (event === 'done' || event === 'error') {
            terminated = true
          }
          applyEvent(replyRef, event, data)
        },
        controller.signal,
      )
    } catch (error) {
      replyRef.failed = true
      if (streamingSessionId === currentSessionId) {
        errorMessage.value = controller.signal.aborted
          ? '已中断当前回复'
          : error instanceof Error
            ? error.message
            : '对话失败'
      }
      throw error
    } finally {
      replyRef.streaming = false
      streaming.value = false
      abortController = null
      if (streamingSessionId === currentSessionId && !terminated && !replyRef.failed) {
        // 服务端没有给出 done / error 事件就断开，明确提示而不是让界面看起来"没有结果"。
        replyRef.failed = true
        errorMessage.value = '连接意外中断，请重试'
      }
      streamingSessionId = null
    }
  }

  /**
   * 中断在途流式请求。
   *
   * 切换会话、删除会话或退出登录前调用，避免旧会话的增量继续渲染到新会话里。
   */
  function abortStreaming(): void {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    streaming.value = false
    streamingSessionId = null
  }

  /**
   * 清空消息区与分页状态。
   */
  function resetMessages(): void {
    messages.value = []
    errorMessage.value = ''
    loading.value = false
    loadingMoreHistory.value = false
    loadedMessagePageNum.value = 0
    messageTotal.value = 0
  }

  /**
   * 退出登录或切换账号时重置。
   */
  function reset(): void {
    abortStreaming()
    resetMessages()
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
   * @param message 当前回复消息
   * @param event 事件名
   * @param data 事件数据
   */
  function applyEvent(message: ChatMessage, event: string, data: string): void {
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
          errorMessage.value = errorEvent.message
          break
        }
        default:
          // meta / done 事件不需要额外处理：流结束由 store 统一收尾。
          break
      }
    } catch {
      errorMessage.value = '解析流式响应失败'
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
    loadHistory,
    loadOlderMessages,
    sendMessage,
    queuePendingCommand,
    clearPendingCommand,
    abortStreaming,
    resetMessages,
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
