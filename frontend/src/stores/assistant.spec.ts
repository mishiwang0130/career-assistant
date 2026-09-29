import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import * as assistantApi from '@/api/assistant'
import * as sessionApi from '@/api/session'
import { useAssistantStore } from '@/stores/assistant'
import { useSessionStore } from '@/stores/session'
import type { ResumeDiagnosisResult } from '@/types/assistant'

// 接口层整体打桩，单测不依赖网络、后端与登录态。
vi.mock('@/api/assistant')
vi.mock('@/api/session')

/** 会话列表桩数据。 */
const SESSION_PAGE = {
  total: 1,
  pageNum: 1,
  pageSize: 20,
  records: [
    { sessionId: '100', title: '新会话', scene: 'ASSISTANT' as const, lastMessageAt: '2026-09-29 10:00:00' },
  ],
}

/** 诊断结论桩数据。 */
const DIAGNOSIS: ResumeDiagnosisResult = {
  type: 'resume_diagnosis',
  resumeId: 5,
  resumeTitle: 'Java 开发简历',
  overallScore: 72,
  scoreSummary: '说明',
  dimensions: [
    { name: '结构与排版', score: 80, comment: 'a' },
    { name: '内容完整度', score: 70, comment: 'b' },
    { name: '成果与量化', score: 60, comment: 'c' },
    { name: '表达专业性', score: 78, comment: 'd' },
  ],
  problems: [],
  highlights: [],
  suggestions: [],
  optimizedResume: '优化后的正文',
  interviewFollowUps: ['一', '二', '三'],
}

describe('助手对话状态', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.resetAllMocks()
    vi.mocked(sessionApi.listSessions).mockResolvedValue(SESSION_PAGE)
    useSessionStore().setCurrentSession('100')
  })

  it('收到 result 事件时把结构化诊断结论挂在当前回复上', async () => {
    vi.mocked(assistantApi.chat).mockImplementation(async (_data, onEvent) => {
      onEvent('delta', JSON.stringify({ content: '这是诊断结论' }))
      onEvent('result', JSON.stringify({ data: DIAGNOSIS }))
      onEvent('done', '{}')
    })
    const assistantStore = useAssistantStore()

    await assistantStore.sendMessage('帮我诊断简历《Java 开发简历》')

    const reply = assistantStore.messages[assistantStore.messages.length - 1]
    expect(reply.content).toBe('这是诊断结论')
    expect(reply.result).toEqual(DIAGNOSIS)
  })

  it('纯文本回答不产生结构化结果', async () => {
    vi.mocked(assistantApi.chat).mockImplementation(async (_data, onEvent) => {
      onEvent('delta', JSON.stringify({ content: '普通回答' }))
      onEvent('done', '{}')
    })
    const assistantStore = useAssistantStore()

    await assistantStore.sendMessage('你好')

    expect(assistantStore.messages[assistantStore.messages.length - 1].result).toBeNull()
  })

  it('待发指令可排队、可清空，重置登录态时一并清掉', () => {
    const assistantStore = useAssistantStore()

    assistantStore.queuePendingCommand('帮我诊断简历')
    expect(assistantStore.pendingCommand).toBe('帮我诊断简历')

    assistantStore.clearPendingCommand()
    expect(assistantStore.pendingCommand).toBeNull()

    assistantStore.queuePendingCommand('帮我诊断简历')
    assistantStore.reset()
    expect(assistantStore.pendingCommand).toBeNull()
  })

  it('切换会话不中断在途的那一轮，切回来还能看到完整回复', async () => {
    let emit: ((event: string, data: string) => void) | null = null
    let finishChat: (() => void) | null = null
    vi.mocked(assistantApi.chat).mockImplementation(async (_data, onEvent) => {
      emit = onEvent
      await new Promise<void>((resolve) => {
        finishChat = resolve
      })
    })
    const assistantStore = useAssistantStore()
    const sessionStore = useSessionStore()

    const sending = assistantStore.sendMessage('帮我诊断简历')
    await flushPromises()
    // 用户切到另一个会话：当前会话没有在途请求，但旧会话仍在生成。
    sessionStore.setCurrentSession('200')

    expect(assistantStore.streaming).toBe(false)
    expect(assistantStore.isStreaming('100')).toBe(true)
    expect(assistantStore.messages).toHaveLength(0)

    emitWith(emit, 'delta', { content: '正在生成' })
    emitWith(emit, 'result', { data: DIAGNOSIS })
    emitWith(emit, 'done', {})
    finishChatWith(finishChat)
    await sending

    // 切回旧会话：这一轮的内容完整保留，结构化结果也还在。
    sessionStore.setCurrentSession('100')
    const reply = assistantStore.messages[assistantStore.messages.length - 1]
    expect(reply.content).toBe('正在生成')
    expect(reply.result).toEqual(DIAGNOSIS)
    expect(assistantStore.isStreaming('100')).toBe(false)
  })

  it('主动中断某个会话的流式请求不算失败', async () => {
    vi.mocked(assistantApi.chat).mockImplementation(async (_data, _onEvent, signal) => {
      await new Promise<void>((resolve) => {
        signal?.addEventListener('abort', () => resolve())
      })
      // 模拟 fetch 被中断时的行为：抛 AbortError。
      throw Object.assign(new Error('aborted'), { name: 'AbortError' })
    })
    const assistantStore = useAssistantStore()

    const sending = assistantStore.sendMessage('你好')
    await flushPromises()
    assistantStore.abortStreaming('100')

    await expect(sending).resolves.toBeUndefined()
    expect(assistantStore.isStreaming('100')).toBe(false)
    expect(assistantStore.errorMessage).toBe('')
  })
})

/**
 * 触发流式回调（测试辅助，避免在断言里写非空断言链）。
 *
 * @param emit 事件回调
 * @param event 事件名
 * @param payload 事件数据
 */
function emitWith(
  emit: ((event: string, data: string) => void) | null,
  event: string,
  payload: unknown,
): void {
  if (!emit) {
    throw new Error('流式回调尚未就绪')
  }
  emit(event, JSON.stringify(payload))
}

/**
 * 结束被挂起的流式请求。
 *
 * @param finish 结束回调
 */
function finishChatWith(finish: (() => void) | null): void {
  if (!finish) {
    throw new Error('流式请求尚未开始')
  }
  finish()
}

/**
 * 等待挂起的 Promise 链跑完。
 */
async function flushPromises(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}
