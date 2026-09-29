import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'

import * as resumeApi from '@/api/resume'
import ResumeDiagnosisCard from '@/components/chat/ResumeDiagnosisCard.vue'
import type { ResumeDiagnosisResult } from '@/types/assistant'

// 接口层整体打桩，单测不依赖网络与后端。
vi.mock('@/api/resume')

/** 诊断结论桩数据。 */
const DIAGNOSIS: ResumeDiagnosisResult = {
  type: 'resume_diagnosis',
  resumeId: 5,
  resumeTitle: 'Java 开发简历',
  overallScore: 72,
  scoreSummary: '扣分主要来自项目成果缺少量化',
  dimensions: [
    { name: '结构与排版', score: 80, comment: '层级清楚' },
    { name: '内容完整度', score: 70, comment: '项目缺时间' },
    { name: '成果与量化', score: 60, comment: '几乎没有数字' },
    { name: '表达专业性', score: 78, comment: '动词偏笼统' },
  ],
  problems: [{ problem: '项目缺量化', location: '项目经历', reason: '看不出影响', suggestion: '补数字', severity: 'HIGH' }],
  highlights: [{ point: '技术栈集中', reason: '与目标岗位一致' }],
  suggestions: [{ priority: 1, content: '重写项目描述' }],
  optimizedResume: '优化后的简历正文',
  interviewFollowUps: ['点一', '点二', '点三'],
}

/**
 * 挂载诊断卡片，返回容器与路由。
 *
 * 项目没有引入组件测试库，这里直接用 createApp + jsdom 渲染，保持依赖不新增。
 *
 * @returns 容器与路由
 */
async function mountCard(): Promise<{ container: HTMLElement; router: ReturnType<typeof createRouter> }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'ChatView', component: { template: '<div />' } },
      { path: '/resumes/:id/edit', name: 'ResumeEditView', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const container = document.createElement('div')
  document.body.appendChild(container)
  const app = createApp(ResumeDiagnosisCard, { diagnosis: DIAGNOSIS })
  app.use(ElementPlus)
  app.use(router)
  app.mount(container)
  await nextTick()
  return { container, router }
}

/**
 * 等待挂起的 Promise 链跑完（不引入额外依赖）。
 */
async function flushPromises(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

describe('简历诊断卡片', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.resetAllMocks()
  })

  it('渲染综合得分、四个维度与优化后正文', async () => {
    const { container } = await mountCard()
    const text = container.textContent ?? ''

    expect(text).toContain('72')
    expect(text).toContain('结构与排版')
    expect(text).toContain('内容完整度')
    expect(text).toContain('成果与量化')
    expect(text).toContain('表达专业性')
    expect(text).toContain('优化后的简历正文')
    expect(text).toContain('另存为新简历')
  })

  it('点「另存为新简历」按 F1 接口创建 MANUAL 简历并跳到编辑页', async () => {
    vi.mocked(resumeApi.createManualResume).mockResolvedValue({
      id: 9,
      title: 'Java 开发简历-优化版',
      sourceType: 'MANUAL',
      fileName: null,
      fileSize: null,
      fileExt: null,
      parseStatus: 'SUCCESS',
      parseError: null,
      defaultFlag: false,
      createTime: '2026-09-29 10:00:00',
      updateTime: '2026-09-29 10:00:00',
      rawText: '优化后的简历正文',
    })
    const { container, router } = await mountCard()

    const button = Array.from(container.querySelectorAll('button')).find((item) =>
      (item.textContent ?? '').includes('另存为新简历'),
    )
    expect(button).toBeTruthy()
    button?.dispatchEvent(new MouseEvent('click'))
    await flushPromises()

    expect(resumeApi.createManualResume).toHaveBeenCalledWith({
      title: 'Java 开发简历-优化版',
      rawText: '优化后的简历正文',
    })
    expect(router.currentRoute.value.name).toBe('ResumeEditView')
    expect(router.currentRoute.value.params.id).toBe('9')
  })
})
