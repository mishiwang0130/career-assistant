<template>
  <div class="chat-view">
    <header class="chat-view__header">
      <h1 class="chat-view__title">{{ title }}</h1>
      <el-button plain @click="handleLogout">退出登录</el-button>
    </header>
    <div class="chat-view__body">
      <component :is="panelComponent" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { BizError } from '@/api/request'
import AssistantPanel from '@/components/chat/AssistantPanel.vue'
import { useAssistantStore } from '@/stores/assistant'
import { useSessionStore } from '@/stores/session'
import { useUserStore } from '@/stores/user'
import type { ChatScene } from '@/types/session'

/** 会话不存在的业务错误码，与后端 ErrorConstant.CHAT_SESSION_NOT_FOUND 保持一致。 */
const SESSION_NOT_FOUND_CODE = 1051

/**
 * 场景 → 面板组件映射。
 *
 * 新增场景（M7 的 DIAGNOSIS/MATCH、M8 的 INTERVIEW、M14 的 TUTOR）时只需在 ChatScene
 * 联合类型与这里各补一项，ChatView 的顶部栏、路由与消息切换逻辑都不需要改动。
 */
const SCENE_PANELS: Record<ChatScene, Component> = {
  ASSISTANT: AssistantPanel,
}

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const assistantStore = useAssistantStore()
const userStore = useUserStore()

/** 当前会话场景，草稿态按通用助手渲染。 */
const currentScene = computed<ChatScene>(() => sessionStore.currentSession?.scene ?? 'ASSISTANT')

/** 当前会话对应的面板组件。 */
const panelComponent = computed(() => SCENE_PANELS[currentScene.value])

/** 顶部栏标题：草稿态是新会话，已加载到列表的会话用服务端标题。 */
const title = computed(() => {
  if (!sessionStore.currentSessionId) {
    return '新会话'
  }
  return sessionStore.currentSession?.title ?? '会话'
})

// 路由参数是切换会话的唯一入口：既覆盖侧栏点击，也覆盖深链接、刷新与草稿态首次建会话。
watch(
  () => route.params.sessionId,
  async (value) => {
    const target = typeof value === 'string' && value ? value : null
    // 草稿态发出第一条消息后会把 URL 替换成 /chat/:sessionId，此时目标与 store 一致，
    // 说明消息已经在新会话里，不能再拉一次历史把刚发的内容冲掉。
    if (target === sessionStore.currentSessionId) {
      return
    }
    await openSession(target)
  },
  { immediate: true },
)

/**
 * 切换当前会话：先中断旧会话的流式渲染，再按新会话拉取最新一页历史。
 *
 * @param target 目标会话 ID，null 表示草稿态
 */
async function openSession(target: string | null): Promise<void> {
  assistantStore.abortStreaming()
  assistantStore.resetMessages()
  sessionStore.setCurrentSession(target)
  if (!target) {
    return
  }
  try {
    await assistantStore.loadHistory()
  } catch (error) {
    // 消息接口在会话不存在、已删除或跨账号时返回 1051，据此回到草稿态。
    if (error instanceof BizError && error.code === SESSION_NOT_FOUND_CODE) {
      ElMessage.warning('会话不存在或已删除，已回到新建会话')
      sessionStore.setCurrentSession(null)
      await router.replace({ name: 'ChatView' })
      return
    }
    ElMessage.error('历史消息加载失败')
  }
}

/**
 * 退出登录：清空会话与消息状态后回到登录页。
 */
async function handleLogout(): Promise<void> {
  assistantStore.reset()
  sessionStore.reset()
  await userStore.logout()
  ElMessage.success('已退出登录')
  await router.replace('/login')
}
</script>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #ffffff;
}

.chat-view__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 20px;
  border-bottom: 1px solid #ebeef5;
}

.chat-view__title {
  overflow: hidden;
  margin: 0;
  color: #1f2d3d;
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-view__body {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
}
</style>
