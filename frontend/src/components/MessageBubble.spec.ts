import { beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus'

import MessageBubble from '@/components/MessageBubble.vue'
import type { ChatMessage } from '@/types/assistant'

/**
 * 构造一条助手消息。
 *
 * @param overrides 需要覆盖的字段
 * @returns 页面消息
 */
function createMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'message-1',
    role: 'ASSISTANT',
    content: '',
    thinking: '',
    tools: [],
    streaming: true,
    failed: false,
    result: null,
    ...overrides,
  }
}

/**
 * 挂载消息气泡，返回容器与可变的响应式消息。
 *
 * 项目没有引入组件测试库，这里用 createApp + jsdom 渲染；改动响应式消息即可模拟流式增量，
 * 用来验证气泡对思考内容的渲染与清除（丢弃时机由 store 决定，见 assistant.spec.ts）。
 *
 * @param message 初始消息
 * @returns 容器与响应式消息
 */
function mountBubble(message: ChatMessage): { container: HTMLElement; message: ChatMessage } {
  const reactiveMessage = reactive(message)
  const container = document.createElement('div')
  document.body.appendChild(container)
  const app = createApp(MessageBubble, { message: reactiveMessage })
  app.use(ElementPlus)
  app.mount(container)
  return { container, message: reactiveMessage }
}

/**
 * 取思考过程正文元素。
 *
 * @param container 气泡容器
 * @returns 思考过程正文元素，没有时返回 null
 */
function thinkingText(container: HTMLElement): HTMLElement | null {
  return container.querySelector<HTMLElement>('.message-thinking__text')
}

describe('消息气泡的思考过程', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('正文产出前直接显示思考内容', async () => {
    const { container } = mountBubble(createMessage({ thinking: '先看简历里有没有量化' }))
    await nextTick()

    expect(thinkingText(container)?.textContent).toContain('先看简历里有没有量化')
  })

  it('思考内容被丢弃后不再保留任何入口', async () => {
    const { container, message } = mountBubble(createMessage({ thinking: '先看简历' }))
    await nextTick()

    // 正文开始产出时 store 会清空思考内容，气泡上不留标题、不留折叠入口。
    message.content = '你的简历整体结构清楚'
    message.thinking = ''
    await nextTick()

    expect(thinkingText(container)).toBeNull()
    expect(container.textContent).not.toContain('思考')
  })
})
