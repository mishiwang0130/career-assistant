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
      <!-- 面试结果：结束时由后端随流下发，回看历史面试时用结果接口补齐 -->
      <InterviewResultCard v-if="interviewResult" :result="interviewResult" />
      <!--
        面试报告：后台任务生成，面板先显示「报告生成中」，生成完成后自动刷新为完整内容；
        失败时显示原因与重试入口，不会停在一个不动的「生成中」。
      -->
      <InterviewReportCard
        v-if="report"
        :report="report"
        :retrying="reportRetrying"
        :polling-exhausted="reportPollingExhausted"
        @retry="handleReportRetry"
        @refresh="refreshReport"
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
        :disabled="assistantStore.streaming"
        :placeholder="inputPlaceholder"
        @keydown.enter.exact.prevent="handleSend"
      />
      <div v-else class="interview__finished">
        本场面试已结束，可以回看上面的问答记录；需要继续练就再开一场。
      </div>
      <el-button
        v-if="!finished"
        type="primary"
        :loading="assistantStore.streaming"
        :disabled="!draft.trim() || assistantStore.streaming"
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
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import {
  getInterviewReport,
  getInterviewResult,
  getInterviewState,
  retryInterviewReport,
} from '@/api/interview'
import { BizError } from '@/api/request'
import InterviewReportCard from '@/components/chat/InterviewReportCard.vue'
import InterviewResultCard from '@/components/chat/InterviewResultCard.vue'
import MessageBubble from '@/components/MessageBubble.vue'
import { useAssistantStore } from '@/stores/assistant'
import { useSessionStore } from '@/stores/session'
import { useUserStore } from '@/stores/user'
import type {
  InterviewProgressResult,
  InterviewReportResult,
  InterviewResult,
  InterviewStateRespVO,
} from '@/types/interview'
import {
  findLatestProgress,
  findLatestReport,
  findLatestResult,
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

/** 面试尚未结束、报告暂不可用的业务错误码，与后端 ErrorConstant.INTERVIEW_REPORT_NOT_READY 一致。 */
const REPORT_NOT_READY_CODE = 1601

/** 报告正在生成中的业务错误码，与后端 ErrorConstant.INTERVIEW_REPORT_GENERATING 一致。 */
const REPORT_GENERATING_CODE = 1602

/** 报告状态轮询间隔，单位毫秒。 */
const REPORT_POLL_INTERVAL_MS = 3000

/** 报告状态轮询的最大次数（约 2 分钟），超过后改为手动刷新。 */
const REPORT_POLL_MAX_ATTEMPTS = 40

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

/** 面试结果：流内下发或结果接口回放，结束时渲染在消息区下方。 */
const interviewResult = ref<InterviewResult | null>(null)

/** 面试报告：流内状态事件或报告接口获取，状态包含生成中 / 已完成 / 生成失败。 */
const report = ref<InterviewReportResult | null>(null)

/** 是否正在重试生成报告。 */
const reportRetrying = ref(false)

/** 轮询是否已经超时：超时后报告卡片给出手动刷新入口，避免一直转圈。 */
const reportPollingExhausted = ref(false)

/** 报告状态轮询定时器句柄。 */
let reportPollTimer: number | null = null

/** 已轮询次数。 */
let reportPollAttempts = 0

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

/** 输入框提示：生成期间明确告诉用户在等结果，避免以为还能继续输入。 */
const inputPlaceholder = computed(() =>
  assistantStore.streaming
    ? '正在处理本题，请稍候…'
    : '输入你的回答，Enter 提交，Shift + Enter 换行',
)

// 路由参数是切换会话的唯一入口：新建面试、点侧栏历史会话、刷新页面都走这里。
watch(
  () => route.params.sessionId,
  async () => {
    progress.value = null
    interviewResult.value = null
    report.value = null
    stopReportPolling()
    reportPollingExhausted.value = false
    await loadSnapshot()
    await loadResultIfFinished()
    await loadReportIfFinished()
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

// 面试结束时后端会在同一轮里下发结果事件，直接用它渲染结果卡片。
watch(
  () => findLatestResult(assistantStore.messages),
  (latest) => {
    if (latest) {
      interviewResult.value = latest
    }
  },
  { immediate: true },
)

// 面试结束时后端会在同一轮里下发报告状态（生成中或派发失败），据此渲染报告卡片并开始轮询。
watch(
  () => findLatestReport(assistantStore.messages),
  (latest) => {
    if (!latest) {
      return
    }
    report.value = latest
    if (latest.status === 'GENERATING') {
      startReportPolling()
    } else {
      stopReportPolling()
    }
  },
  { immediate: true },
)

// 切换会话或卸载面板时停掉轮询，避免在别的会话上继续请求上一场的报告。
onUnmounted(() => {
  stopReportPolling()
})

/**
 * 已结束的面试（含刷新页面、回看历史会话）用结果接口补齐结果卡片。
 */
async function loadResultIfFinished(): Promise<void> {
  const sessionId = sessionStore.currentSessionId
  if (!sessionId || interviewResult.value || snapshot.value?.finished !== true) {
    return
  }
  try {
    interviewResult.value = await getInterviewResult(sessionId)
  } catch {
    // 结果加载失败不影响其它内容，用户刷新后还会再试。
  }
}

/**
 * 已结束的面试（含刷新页面、回看历史会话）用报告接口补齐报告卡片。
 *
 * 面试没走到结束条件时接口返回 1601，这里按「还没有报告」处理，不打扰用户。
 */
async function loadReportIfFinished(): Promise<void> {
  const sessionId = sessionStore.currentSessionId
  if (!sessionId || snapshot.value?.finished !== true) {
    return
  }
  await refreshReport()
}

/**
 * 取一次报告最新状态。
 *
 * 生成中继续轮询，已完成或失败时停止：失败态由卡片给出重试入口。
 */
async function refreshReport(): Promise<void> {
  const sessionId = sessionStore.currentSessionId
  if (!sessionId) {
    return
  }
  try {
    const latest = await getInterviewReport(sessionId)
    report.value = latest
    if (latest.status === 'GENERATING') {
      reportPollAttempts += 1
      if (reportPollAttempts >= REPORT_POLL_MAX_ATTEMPTS) {
        stopReportPolling()
        reportPollingExhausted.value = true
      }
      return
    }
    stopReportPolling()
    reportPollingExhausted.value = false
  } catch (error) {
    stopReportPolling()
    if (error instanceof BizError && error.code === REPORT_NOT_READY_CODE) {
      // 面试尚未结束：报告本来就不该有，静默按没有报告处理。
      return
    }
    // 其它错误已由请求层提示，报告卡片保持当前状态，用户可手动刷新。
  }
}

/**
 * 开始轮询报告状态。
 */
function startReportPolling(): void {
  if (reportPollTimer !== null) {
    return
  }
  reportPollAttempts = 0
  reportPollingExhausted.value = false
  reportPollTimer = window.setInterval(() => {
    void refreshReport()
  }, REPORT_POLL_INTERVAL_MS)
}

/**
 * 停止轮询报告状态。
 */
function stopReportPolling(): void {
  if (reportPollTimer !== null) {
    window.clearInterval(reportPollTimer)
    reportPollTimer = null
  }
}

/**
 * 重试生成报告。
 *
 * 生成中（1602）不报错，直接继续轮询；已完成时接口幂等返回现有报告，界面照常渲染。
 */
async function handleReportRetry(): Promise<void> {
  const sessionId = sessionStore.currentSessionId
  if (!sessionId || reportRetrying.value) {
    return
  }
  reportRetrying.value = true
  try {
    const latest = await retryInterviewReport(sessionId)
    report.value = latest
    if (latest.status === 'GENERATING') {
      startReportPolling()
    } else {
      stopReportPolling()
    }
  } catch (error) {
    if (error instanceof BizError && error.code === REPORT_GENERATING_CODE) {
      startReportPolling()
      return
    }
    if (error instanceof BizError && error.code === REPORT_NOT_READY_CODE) {
      ElMessage.warning('本场面试还没结束，暂时不能生成报告')
      return
    }
    // 其它错误已由请求层统一提示。
  } finally {
    reportRetrying.value = false
  }
}

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
 *
 * 回答一交给后端就立刻清空输入框：内容已经作为用户消息发出，输入框里再留一份可编辑的副本没有意义，
 * 也容易让人以为还没发出去；生成期间输入框置灰，避免用户以为能继续输入。发送失败时把内容放回去，
 * 用户不用重新打一遍。
 */
async function handleSend(): Promise<void> {
  const content = draft.value.trim()
  if (!content || finished.value || assistantStore.streaming) {
    return
  }
  draft.value = ''
  const sent = await sendContent(content)
  if (!sent) {
    draft.value = content
  }
}

/**
 * 统一的发送链路，失败按业务码分流。
 *
 * @param content 待发送内容
 * @returns 是否成功发出
 */
async function sendContent(content: string): Promise<boolean> {
  if (!content || assistantStore.streaming) {
    return false
  }
  try {
    await assistantStore.sendMessage(content)
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
