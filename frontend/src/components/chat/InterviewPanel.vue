<template>
  <div class="interview">
    <!-- 顶部进度：题序与当前难度来自状态接口与流内进度事件，用户答到哪、难度到哪一眼可见 -->
    <div class="interview__bar">
      <span class="interview__progress">{{ progressText }}</span>
      <span class="interview__difficulty">难度 {{ difficultyText }}</span>
      <el-tag v-if="finished" type="info" size="small">本场已结束</el-tag>
      <el-tag v-else-if="roundNo === FOLLOW_UP_ROUND" type="warning" size="small">追问</el-tag>
    </div>

    <el-alert
      v-if="assistantStore.errorMessage"
      class="interview__error"
      type="error"
      :closable="false"
      show-icon
      :title="assistantStore.errorMessage"
    />

    <div
      ref="messageListRef"
      v-loading="assistantStore.loading"
      class="interview__messages"
      @scroll.passive="handleScroll"
    >
      <div class="interview__history">
        <span v-if="assistantStore.loadingMoreHistory">正在加载更早的消息…</span>
        <span v-else-if="assistantStore.hasMoreMessages">向上滚动加载更早的消息</span>
        <span v-else>没有更早的消息了</span>
      </div>
      <!--
        消息气泡下方就是结构化卡片的渲染位（MessageBubble 内按 result.type 分流）：
        本批已承载面试进度，F6 的逐题点评与报告卡片直接在同一位置新增分支即可。
      -->
      <MessageBubble
        v-for="message in assistantStore.messages"
        :key="message.id"
        :message="message"
      />
    </div>

    <div class="interview__input">
      <el-input
        v-if="!finished"
        v-model="draft"
        type="textarea"
        :rows="3"
        maxlength="4000"
        show-word-limit
        resize="none"
        placeholder="输入你的回答，Enter 提交，Shift + Enter 换行"
        @keydown.enter.exact.prevent="handleSend"
      />
      <div v-else class="interview__finished">
        本场面试已结束，可以回看上面的问答记录；需要继续练就再开一场。
      </div>
      <el-button
        v-if="!finished"
        type="primary"
        :loading="assistantStore.streaming"
        :disabled="!draft.trim()"
        @click="handleSend"
      >
        提交回答
      </el-button>
      <el-button v-else type="primary" :loading="restarting" @click="handleRestart">
        再开一场
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { getInterviewState } from '@/api/interview'
import { BizError } from '@/api/request'
import MessageBubble from '@/components/MessageBubble.vue'
import { useAssistantStore } from '@/stores/assistant'
import { useSessionStore } from '@/stores/session'
import { useUserStore } from '@/stores/user'
import type { InterviewProgressResult, InterviewStateRespVO } from '@/types/interview'
import {
  findLatestProgress,
  formatDifficulty,
  formatInterviewProgress,
  INTERVIEW_KICKOFF_COMMAND,
} from '@/utils/interview'
import { SseRequestError } from '@/utils/sse'

/** 距顶部多少像素时触发加载更早的消息。 */
const SCROLL_LOAD_THRESHOLD = 40

/** 会话不存在的业务错误码。 */
const SESSION_NOT_FOUND_CODE = 1051

/** 求职目标未填写的业务错误码，与后端 ErrorConstant.USER_PROFILE_REQUIRED 一致。 */
const PROFILE_REQUIRED_CODE = 1101

/** 本场面试已结束的业务错误码。 */
const INTERVIEW_FINISHED_CODE = 1501

/** 追问轮次的编号，与后端 InterviewQa.ROUND_FOLLOW_UP 一致。 */
const FOLLOW_UP_ROUND = 2

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const assistantStore = useAssistantStore()
const userStore = useUserStore()

/** 输入框内容。 */
const draft = ref('')

/** 是否正在新建下一场面试。 */
const restarting = ref(false)

/** 消息滚动容器。 */
const messageListRef = ref<HTMLElement | null>(null)

/** 状态接口返回的快照（含起始难度与建议题型），进入与刷新会话时恢复进度用。 */
const snapshot = ref<InterviewStateRespVO | null>(null)

/** 流内最新下发的进度：比快照新，因此顶部进度以它为准。 */
const progress = ref<InterviewProgressResult | null>(null)

/** 当前生效的进度：优先用流内进度，没有则用状态快照。 */
const active = computed(() => progress.value ?? snapshot.value)

/** 顶部进度文案。 */
const progressText = computed(() =>
  active.value ? formatInterviewProgress(active.value) : '模拟面试',
)

/** 当前难度文案。 */
const difficultyText = computed(() =>
  active.value ? formatDifficulty(active.value.difficulty) : formatDifficulty(1),
)

/** 当前轮次。 */
const roundNo = computed(() => active.value?.roundNo ?? 1)

/** 本场是否已结束：结束后输入区只读，只能回看或再开一场。 */
const finished = computed(() => active.value?.finished === true)

// 路由参数是切换会话的唯一入口：新建面试、点侧栏历史会话、刷新页面都走这里。
watch(
  () => route.params.sessionId,
  async () => {
    progress.value = null
    await loadSnapshot()
    await nextTick()
    scrollToBottom()
  },
  { immediate: true },
)

// 消息区里出现新的面试进度就刷新顶部；看历史会话时进度事件不会再来，顶部靠快照。
watch(
  () => findLatestProgress(assistantStore.messages),
  (latest) => {
    if (latest) {
      progress.value = latest
    }
  },
  { immediate: true },
)

// 流式增量与新消息都追加在末尾，需要跟随滚动到底部。
watch(
  () => assistantStore.messages.map((message) => message.content).join(''),
  async () => {
    await nextTick()
    scrollToBottom()
  },
)

// 会话就绪、历史加载完成且没有在途请求时才发出开场指令。
watch(
  [() => sessionStore.currentSessionId, () => assistantStore.loading, () => assistantStore.streaming],
  async () => {
    await sendKickoffIfPending()
  },
  { immediate: true },
)

/**
 * 读取面试进度快照。
 *
 * 会话不存在时回到新建会话；不是面试会话（1502）时提示并回到新建会话，避免把助手会话当面试渲染。
 */
async function loadSnapshot(): Promise<void> {
  const sessionId = sessionStore.currentSessionId
  if (!sessionId) {
    snapshot.value = null
    return
  }
  try {
    snapshot.value = await getInterviewState(sessionId)
  } catch (error) {
    snapshot.value = null
    if (error instanceof BizError) {
      if (error.code === SESSION_NOT_FOUND_CODE) {
        ElMessage.warning('会话不存在或已删除，已回到新建会话')
        sessionStore.setCurrentSession(null)
        await router.replace({ name: 'ChatView' })
        return
      }
      if (error.code === INTERVIEW_FINISHED_CODE) {
        return
      }
    }
    // 其它错误已由请求层提示，界面保持可输入状态。
  }
}

/**
 * 消费开场指令：只在当前路由的面试会话已经就绪、历史加载完成时发出，避免发到旧会话上。
 */
async function sendKickoffIfPending(): Promise<void> {
  if (assistantStore.pendingCommand !== INTERVIEW_KICKOFF_COMMAND) {
    return
  }
  const currentSessionId = sessionStore.currentSessionId
  if (!currentSessionId || route.params.sessionId !== currentSessionId) {
    return
  }
  if (assistantStore.loading || assistantStore.streaming) {
    return
  }
  assistantStore.clearPendingCommand()
  await sendContent(INTERVIEW_KICKOFF_COMMAND)
}

/**
 * 提交回答。
 */
async function handleSend(): Promise<void> {
  const content = draft.value.trim()
  if (!content || finished.value) {
    return
  }
  await sendContent(content, () => {
    draft.value = ''
  })
}

/**
 * 统一的发送链路：发送成功后清空输入框，失败按业务码分流。
 *
 * @param content 待发送内容
 * @param afterSent 发送成功后的回调，例如清空输入框
 * @returns 是否成功发出
 */
async function sendContent(content: string, afterSent?: () => void): Promise<boolean> {
  if (!content || assistantStore.streaming) {
    return false
  }
  try {
    await assistantStore.sendMessage(content)
    afterSent?.()
    return true
  } catch (error) {
    await handleSendError(error)
    return false
  }
}

/**
 * 发送失败分流：未登录跳登录页，面试已结束切只读，求职目标未填跳设置页。
 *
 * @param error 发送异常
 */
async function handleSendError(error: unknown): Promise<void> {
  if (error instanceof SseRequestError && error.status === 401) {
    userStore.clearAuth()
    await router.replace('/login')
    return
  }
  if (error instanceof BizError) {
    if (error.code === INTERVIEW_FINISHED_CODE) {
      markFinished()
      return
    }
    if (error.code === PROFILE_REQUIRED_CODE) {
      ElMessage.warning('请先填写求职目标')
      await router.push({ name: 'ProfileView', query: { redirect: route.fullPath } })
      return
    }
    if (error.code === SESSION_NOT_FOUND_CODE) {
      ElMessage.warning('会话不存在或已删除，已回到新建会话')
      sessionStore.setCurrentSession(null)
      await router.replace({ name: 'ChatView' })
    }
  }
  // 其它错误已由请求层统一提示。
}

/**
 * 本地标记本场结束：后端已经拒绝继续作答，界面同步切成只读，避免用户反复提交。
 */
function markFinished(): void {
  const base = active.value
  progress.value = {
    type: 'interview_progress',
    sessionId: base?.sessionId ?? sessionStore.currentSessionId ?? '',
    questionIndex: base?.questionIndex ?? 1,
    questionCount: base?.questionCount ?? 1,
    difficulty: base?.difficulty ?? 1,
    roundNo: base?.roundNo ?? 1,
    finished: true,
  }
}

/**
 * 再开一场：新建 INTERVIEW 会话并排队开场指令，用户不需要自己回侧栏点一次。
 */
async function handleRestart(): Promise<void> {
  if (restarting.value) {
    return
  }
  restarting.value = true
  try {
    const sessionId = await sessionStore.createNewSession('INTERVIEW')
    assistantStore.queuePendingCommand(INTERVIEW_KICKOFF_COMMAND)
    await router.push({ name: 'ChatSessionView', params: { sessionId } })
  } catch (error) {
    if (error instanceof BizError && error.code === PROFILE_REQUIRED_CODE) {
      ElMessage.warning('请先填写求职目标')
      await router.push({ name: 'ProfileView', query: { redirect: route.fullPath } })
    }
    // 其它错误已由请求层统一提示。
  } finally {
    restarting.value = false
  }
}

/**
 * 消息区滚动到接近顶部时加载更早的消息，并保持当前视口位置不跳动。
 */
async function handleScroll(): Promise<void> {
  const element = messageListRef.value
  if (!element || element.scrollTop > SCROLL_LOAD_THRESHOLD) {
    return
  }
  if (
    assistantStore.loading ||
    assistantStore.loadingMoreHistory ||
    !assistantStore.hasMoreMessages
  ) {
    return
  }
  const previousHeight = element.scrollHeight
  const previousTop = element.scrollTop
  try {
    await assistantStore.loadOlderMessages()
  } catch {
    ElMessage.error('更早的消息加载失败')
    return
  }
  await nextTick()
  element.scrollTop = previousTop + (element.scrollHeight - previousHeight)
}

/**
 * 滚动消息区到底部。
 */
function scrollToBottom(): void {
  const element = messageListRef.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}
</script>

<style scoped>
.interview {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  padding: 12px 20px 16px;
  box-sizing: border-box;
}

.interview__bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  background: #fafcff;
}

.interview__progress {
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 600;
}

.interview__difficulty {
  color: #409eff;
  font-size: 13px;
}

.interview__error {
  margin-top: 8px;
}

.interview__messages {
  flex: 1;
  min-height: 0;
  padding: 12px 4px;
  overflow-y: auto;
}

.interview__history {
  padding: 4px 0 12px;
  color: #c0c4cc;
  font-size: 12px;
  text-align: center;
}

.interview__input {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 8px;
}

.interview__finished {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  color: #909399;
  font-size: 13px;
  line-height: 1.7;
}
</style>
