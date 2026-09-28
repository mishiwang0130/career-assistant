import { ref } from 'vue'
import { defineStore } from 'pinia'

import * as assistantApi from '@/api/assistant'
import type {
  AssistantContentEvent,
  AssistantErrorEvent,
  AssistantMessageRespVO,
  AssistantToolEvent,
  ChatMessage,
} from '@/types/assistant'
import { getAssistantSessionId, setAssistantSessionId } from '@/utils/storage'

/**
 * 生成会话 ID。
 *
 * 浏览器支持 crypto.randomUUID，降级时用随机串兜底，保证会话 ID 唯一且长度不超过 64。
 */
function createSessionId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `session-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

/**
 * 生成前端消息 ID。
 */
function createMessageId(): string {
  return createSessionId()
}

/**
 * 通用助手对话状态。
 */
export const useAssistantStore = defineStore('assistant', () => {
  const sessionId = ref<string>(getAssistantSessionId() ?? createSessionId())
  const messages = ref<ChatMessage[]>([])
  const streaming = ref(false)
  const loading = ref(false)
  const errorMessage = ref('')

  setAssistantSessionId(sessionId.value)

  /**
   * 加载当前会话的历史消息。
   */
  async function loadHistory(): Promise<void> {
    loading.value = true
    errorMessage.value = ''
    try {
      const page = await assistantApi.listMessages(sessionId.value)
      messages.value = page.records.map(toChatMessage)
    } finally {
      loading.value = false
    }
  }

  /**
   * 发送一条消息并流式接收回复。
   *
   * @param content 用户输入
   */
  async function sendMessage(content: string): Promise<void> {
    const text = content.trim()
    if (!text || streaming.value) {
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
    })
    const reply: ChatMessage = {
      id: createMessageId(),
      role: 'ASSISTANT',
      content: '',
      thinking: '',
      tools: [],
      streaming: true,
      failed: false,
    }
    messages.value.push(reply)
    streaming.value = true
    try {
      await assistantApi.chat({ sessionId: sessionId.value, content: text }, (event, data) =>
        applyEvent(reply, event, data),
      )
    } catch (error) {
      reply.failed = true
      errorMessage.value = error instanceof Error ? error.message : '对话失败'
      throw error
    } finally {
      reply.streaming = false
      streaming.value = false
    }
  }

  /**
   * 清空当前会话并重置消息列表。
   */
  async function clearSession(): Promise<void> {
    await assistantApi.clearSession(sessionId.value)
    messages.value = []
    errorMessage.value = ''
  }

  /**
   * 新建会话：换一个新的 sessionId 并清空页面消息。
   */
  function startNewSession(): void {
    sessionId.value = createSessionId()
    setAssistantSessionId(sessionId.value)
    messages.value = []
    errorMessage.value = ''
  }

  /**
   * 按事件名把 SSE 事件应用到当前回复消息上。
   */
  function applyEvent(message: ChatMessage, event: string, data: string): void {
    try {
      switch (event) {
        case 'delta':
          message.content += (JSON.parse(data) as AssistantContentEvent).content
          break
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
        case 'error': {
          const errorEvent = JSON.parse(data) as AssistantErrorEvent
          message.failed = true
          errorMessage.value = errorEvent.message
          break
        }
        default:
          // meta / result / done 事件不需要额外处理：流结束由 store 统一收尾。
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
    errorMessage,
    loadHistory,
    sendMessage,
    clearSession,
    startNewSession,
  }
})

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
  }
}
