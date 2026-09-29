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
 * 从而验证「生成期间展开、正文产出后自动折叠」这套纯前端行为。
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

/**
 * 取思考过程的标题按钮。
 *
 * @param container 气泡容器
 * @returns 标题按钮，没有时返回 null
 */
function thinkingToggle(container: HTMLElement): HTMLButtonElement | null {
  return container.querySelector<HTMLButtonElement>('.message-thinking__toggle')
}

describe('消息气泡的思考过程', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('生成期间直接展开显示思考内容', async () => {
    const { container } = mountBubble(createMessage({ thinking: '先看简历里有没有量化' }))
    await nextTick()

    expect(thinkingText(container)?.textContent).toContain('先看简历里有没有量化')
    expect(thinkingText(container)?.style.display).not.toBe('none')
    expect(thinkingToggle(container)?.textContent).toContain('思考中')
  })

  it('正文开始产出后自动折叠，仍可手动展开', async () => {
    const { container, message } = mountBubble(createMessage({ thinking: '先看简历' }))
    await nextTick()

    message.content = '你的简历整体结构清楚'
    await nextTick()

    expect(thinkingText(container)?.style.display).toBe('none')
    expect(thinkingToggle(container)?.textContent).toContain('思考过程')

    thinkingToggle(container)?.dispatchEvent(new MouseEvent('click'))
    await nextTick()

    expect(thinkingText(container)?.style.display).not.toBe('none')
    expect(thinkingText(container)?.textContent).toContain('先看简历')
  })

  it('没有思考内容的历史消息不显示思考过程', async () => {
    const { container } = mountBubble(createMessage({ content: '历史回答', streaming: false }))
    await nextTick()

    expect(thinkingText(container)).toBeNull()
    expect(thinkingToggle(container)).toBeNull()
  })
})
