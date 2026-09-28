<template>
  <div class="sidebar">
    <div class="sidebar__brand">求职智能助手</div>

    <el-button class="sidebar__create" type="primary" @click="handleCreateSession">
      + 新建会话
    </el-button>

    <div class="sidebar__section">会话</div>
    <div
      v-infinite-scroll="handleLoadMore"
      class="sidebar__sessions"
      :infinite-scroll-distance="40"
      :infinite-scroll-disabled="infiniteScrollDisabled"
      :infinite-scroll-immediate="false"
    >
      <el-skeleton v-if="sessionStore.loading && !sessionStore.sessions.length" :rows="4" animated />
      <el-empty
        v-else-if="!sessionStore.sessions.length"
        :image-size="60"
        description="还没有会话，点击新建会话开始"
      />
      <template v-else>
        <div
          v-for="session in sessionStore.sessions"
          :key="session.sessionId"
          class="session"
          :class="{ 'session--active': isSessionActive(session.sessionId) }"
          @click="handleOpenSession(session.sessionId)"
        >
          <div class="session__body">
            <div class="session__title" :title="session.title">{{ session.title }}</div>
            <div class="session__time">{{ formatRelativeTime(session.lastMessageAt) }}</div>
          </div>
          <div class="session__actions">
            <el-button link :icon="EditPen" title="重命名" @click.stop="handleRename(session)" />
            <el-button link :icon="Delete" title="删除" @click.stop="handleDelete(session)" />
          </div>
        </div>
      </template>
      <div v-if="sessionStore.sessions.length" class="sidebar__hint">
        {{
          sessionStore.loadingMore
            ? '正在加载…'
            : sessionStore.hasMore
              ? '向下滚动加载更多'
              : '没有更多了'
        }}
      </div>
    </div>

    <div class="sidebar__section">资料库</div>
    <div class="sidebar__nav">
      <div
        class="nav-item"
        :class="{ 'nav-item--active': isResumeRoute }"
        @click="handleOpenResumes"
      >
        我的简历
      </div>
    </div>

    <div class="sidebar__user">
      <div class="sidebar__user-text">
        <div class="sidebar__user-name">{{ userStore.user?.nickname ?? '未登录' }}</div>
        <div class="sidebar__user-account">{{ userStore.user?.username ?? '' }}</div>
      </div>
      <el-button link type="primary" @click="handleLogout">退出登录</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Delete, EditPen } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { useAssistantStore } from '@/stores/assistant'
import { useSessionStore } from '@/stores/session'
import { useUserStore } from '@/stores/user'
import type { ChatSessionRespVO } from '@/types/session'

/** 点击会话或资料库后通知外层关闭窄屏抽屉。 */
const emit = defineEmits<{ (event: 'navigate'): void }>()

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const assistantStore = useAssistantStore()
const userStore = useUserStore()

/** 滚动分页关闭条件：正在加载或已经没有更多会话。 */
const infiniteScrollDisabled = computed(() => sessionStore.loadingMore || !sessionStore.hasMore)

/** 资料库是否处于选中态，用于高亮导航。 */
const isResumeRoute = computed(
  () => route.name === 'ResumeListView' || route.name === 'ResumeEditView',
)

/** 是否处于会话路由：切到资料库时会话项不再保持选中态。 */
const isChatRoute = computed(
  () => route.name === 'ChatView' || route.name === 'ChatSessionView',
)

/**
 * 判断会话项是否为当前选中项。
 *
 * @param sessionId 会话 ID
 */
function isSessionActive(sessionId: string): boolean {
  return isChatRoute.value && sessionId === sessionStore.currentSessionId
}

// 进入应用壳时加载第 1 页会话，第 1 页永远是最新会话。
onMounted(async () => {
  try {
    await sessionStore.loadSessions()
  } catch {
    // 请求层已统一提示，这里只保证界面不空白。
  }
})

/**
 * 新建会话：回到草稿态并清空消息区，草稿态下不重复创建会话。
 */
async function handleCreateSession(): Promise<void> {
  emit('navigate')
  assistantStore.abortStreaming()
  assistantStore.resetMessages()
  sessionStore.startDraft()
  if (route.name !== 'ChatView') {
    await router.push({ name: 'ChatView' })
  }
}

/**
 * 打开历史会话，切换与消息加载由 ChatView 监听路由统一处理。
 *
 * @param sessionId 会话 ID
 */
async function handleOpenSession(sessionId: string): Promise<void> {
  emit('navigate')
  if (route.name === 'ChatSessionView' && route.params.sessionId === sessionId) {
    return
  }
  await router.push({ name: 'ChatSessionView', params: { sessionId } })
}

/**
 * 滚动到底部时加载更早的会话。
 */
async function handleLoadMore(): Promise<void> {
  if (infiniteScrollDisabled.value) {
    return
  }
  try {
    await sessionStore.loadMoreSessions()
  } catch {
    // 请求层已统一提示。
  }
}

/**
 * 重命名会话。
 *
 * @param session 目标会话
 */
async function handleRename(session: ChatSessionRespVO): Promise<void> {
  let newTitle: string | null = null
  try {
    const result = await ElMessageBox.prompt('请输入新的会话标题', '重命名会话', {
      inputValue: session.title,
      inputValidator: (value: string) => (value?.trim() ? true : '标题不能为空'),
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    const trimmed = result.value?.trim() ?? ''
    newTitle = trimmed ? trimmed : null
  } catch {
    // 用户取消，不做任何改动。
    return
  }
  if (!newTitle) {
    return
  }
  try {
    await sessionStore.renameSession(session.sessionId, newTitle)
    ElMessage.success('已重命名')
  } catch {
    // 请求层已统一提示。
  }
}

/**
 * 删除会话：删除的是当前会话时回到新建会话草稿态。
 *
 * @param session 目标会话
 */
async function handleDelete(session: ChatSessionRespVO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除会话「${session.title}」吗？删除后不可恢复。`, '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    // 用户取消。
    return
  }
  const isCurrent = session.sessionId === sessionStore.currentSessionId
  // 先中断当前会话的在途流式，避免删除后旧回复继续渲染。
  if (isCurrent) {
    assistantStore.abortStreaming()
  }
  try {
    await sessionStore.deleteSession(session.sessionId)
    if (isCurrent) {
      assistantStore.resetMessages()
      if (route.name !== 'ChatView') {
        await router.push({ name: 'ChatView' })
      }
    }
    ElMessage.success('会话已删除')
  } catch {
    // 请求层已统一提示。
  }
}

/**
 * 打开资料库中的简历页。
 */
async function handleOpenResumes(): Promise<void> {
  emit('navigate')
  if (isResumeRoute.value) {
    return
  }
  await router.push({ name: 'ResumeListView' })
}

/**
 * 退出登录：先清空会话与消息状态，避免同标签页切换账号后看到上一个账号的数据。
 */
async function handleLogout(): Promise<void> {
  assistantStore.reset()
  sessionStore.reset()
  await userStore.logout()
  ElMessage.success('已退出登录')
  await router.replace('/login')
}

/**
 * 把最近消息时间格式化成群聊列表式的相对时间。
 *
 * @param value 后端返回的时间字符串
 * @returns 今天为 HH:mm，昨天为「昨天」，今年为 MM-DD，更早为 YYYY-MM-DD
 */
function formatRelativeTime(value: string): string {
  const target = new Date(value)
  if (Number.isNaN(target.getTime())) {
    return ''
  }
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const startOfTarget = new Date(target.getFullYear(), target.getMonth(), target.getDate())
  const dayDiff = Math.round((startOfToday.getTime() - startOfTarget.getTime()) / 86400000)
  if (dayDiff <= 0) {
    return `${pad(target.getHours())}:${pad(target.getMinutes())}`
  }
  if (dayDiff === 1) {
    return '昨天'
  }
  if (target.getFullYear() === now.getFullYear()) {
    return `${pad(target.getMonth() + 1)}-${pad(target.getDate())}`
  }
  return `${target.getFullYear()}-${pad(target.getMonth() + 1)}-${pad(target.getDate())}`
}

/**
 * 两位补零。
 *
 * @param value 数字
 */
function pad(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}
</script>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 16px 12px;
  box-sizing: border-box;
}

.sidebar__brand {
  padding: 0 4px 12px;
  color: #1f2d3d;
  font-size: 16px;
  font-weight: 600;
}

.sidebar__create {
  width: 100%;
}

.sidebar__section {
  padding: 16px 4px 8px;
  color: #909399;
  font-size: 12px;
}

.sidebar__sessions {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.session {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 8px;
  border-radius: 8px;
  cursor: pointer;
}

.session:hover,
.session--active {
  background: #ecf5ff;
}

.session__body {
  flex: 1;
  min-width: 0;
}

.session__title {
  overflow: hidden;
  color: #1f2d3d;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session__time {
  margin-top: 2px;
  color: #a8abb2;
  font-size: 12px;
}

.session__actions {
  display: none;
  flex-shrink: 0;
}

.session:hover .session__actions {
  display: flex;
}

.sidebar__hint {
  padding: 10px 4px;
  color: #c0c4cc;
  font-size: 12px;
  text-align: center;
}

.sidebar__nav {
  display: flex;
  flex-direction: column;
}

.nav-item {
  padding: 8px;
  border-radius: 8px;
  color: #1f2d3d;
  font-size: 13px;
  cursor: pointer;
}

.nav-item:hover,
.nav-item--active {
  background: #ecf5ff;
}

.sidebar__user {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 12px;
  padding: 12px 4px 0;
  border-top: 1px solid #ebeef5;
}

.sidebar__user-text {
  min-width: 0;
}

.sidebar__user-name {
  overflow: hidden;
  color: #1f2d3d;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar__user-account {
  color: #a8abb2;
  font-size: 12px;
}
</style>
