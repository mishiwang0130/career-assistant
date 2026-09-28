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
    // 必须取回数组中的响应式代理再写入：直接改 push 进去的原始对象不会触发 Vue 更新，
    // 会导致流式增量不渲染、界面上看不到任何结果。
    const replyRef = messages.value[messages.value.length - 1]
    streaming.value = true
    let terminated = false
    try {
      await assistantApi.chat({ sessionId: sessionId.value, content: text }, (event, data) => {
        if (event === 'done' || event === 'error') {
          terminated = true
        }
        applyEvent(replyRef, event, data)
      })
    } catch (error) {
      replyRef.failed = true
      errorMessage.value = error instanceof Error ? error.message : '对话失败'
      throw error
    } finally {
      replyRef.streaming = false
      streaming.value = false
      if (!terminated && !replyRef.failed) {
        // 服务端没有给出 done / error 事件就断开，明确提示而不是让界面看起来"没有结果"。
        replyRef.failed = true
        errorMessage.value = '连接意外中断，请重试'
      }
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
