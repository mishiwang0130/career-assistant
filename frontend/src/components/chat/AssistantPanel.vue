<template>
  <div class="panel">
    <el-alert
      v-if="assistantStore.errorMessage"
      class="panel__error"
      type="error"
      :closable="false"
      show-icon
      :title="assistantStore.errorMessage"
    />

    <div
      ref="messageListRef"
      v-loading="assistantStore.loading"
      class="panel__messages"
      @scroll.passive="handleScroll"
    >
      <div class="panel__history">
        <span v-if="assistantStore.loadingMoreHistory">正在加载更早的消息…</span>
        <span v-else-if="assistantStore.hasMoreMessages">向上滚动加载更早的消息</span>
        <span v-else>没有更早的消息了</span>
      </div>
      <!-- 空会话引导卡片：按求职目标与简历的完成度给出下一步，或展示快捷指令 -->
      <div
        v-if="!assistantStore.messages.length && !assistantStore.loading"
        class="panel__guide"
      >
        <template v-if="!profileStore.filled">
          <h3 class="panel__guide-title">先填目标岗位和工作年限</h3>
          <p class="panel__guide-text">
            填好求职目标，模拟面试才能按你的方向出题，训练计划也有依据。
          </p>
          <el-button type="primary" @click="handleGoProfile">去填写求职目标</el-button>
        </template>
        <template v-else-if="!hasResume">
          <h3 class="panel__guide-title">上传一份简历</h3>
          <p class="panel__guide-text">有了简历，助手才能帮你诊断问题、匹配岗位。</p>
          <el-button type="primary" @click="handleGoResumes">去上传简历</el-button>
        </template>
        <template v-else>
          <h3 class="panel__guide-title">可以这样开始</h3>
          <div class="panel__shortcuts">
            <el-button
              v-for="command in QUICK_COMMANDS"
              :key="command"
              plain
              :disabled="assistantStore.streaming || creating"
              @click="handleQuickCommand(command)"
            >
              {{ command }}
            </el-button>
          </div>
        </template>
      </div>
      <MessageBubble
        v-for="message in assistantStore.messages"
        :key="message.id"
        :message="message"
      />
    </div>

    <div class="panel__input">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="3"
        maxlength="4000"
        show-word-limit
        resize="none"
        placeholder="输入消息，Enter 发送，Shift + Enter 换行"
        @keydown.enter.exact.prevent="handleSend"
      />
      <el-button
        type="primary"
        :loading="assistantStore.streaming || creating"
        :disabled="!draft.trim()"
        @click="handleSend"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { getResumeList } from '@/api/resume'
import { BizError } from '@/api/request'
import MessageBubble from '@/components/MessageBubble.vue'
import { useAssistantStore } from '@/stores/assistant'
import { useProfileStore } from '@/stores/profile'
import { useSessionStore } from '@/stores/session'
import { useUserStore } from '@/stores/user'
import { SseRequestError } from '@/utils/sse'

/** 距离顶部多少像素时触发加载更早的消息。 */
const SCROLL_LOAD_THRESHOLD = 40

/** 会话不存在的业务错误码。 */
const SESSION_NOT_FOUND_CODE = 1051

/**
 * 四个快捷指令：只是往当前会话发一句话，不新建场景、不建 Agent。
 */
const QUICK_COMMANDS = [
  '诊断我的简历',
  '分析这个 JD 和我的简历',
  '我上次面试哪里最差',
  '讲讲我的薄弱点',
]

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const assistantStore = useAssistantStore()
const userStore = useUserStore()
const profileStore = useProfileStore()

/** 输入框内容。 */
const draft = ref('')

/** 是否正在创建草稿会话。 */
const creating = ref(false)

/** 消息滚动容器。 */
const messageListRef = ref<HTMLElement | null>(null)

/** 是否已有简历，用于空会话引导卡片的完成度判断。 */
const hasResume = ref(false)

// 引导卡片只在空会话展示，进入面板时探测一次求职目标与简历的完成度。
onMounted(async () => {
  void profileStore.ensureLoaded()
  try {
    hasResume.value = (await getResumeList()).length > 0
  } catch {
    // 简历列表探测失败时按「没上传」引导，用户点进去还能重试。
  }
})

// 流式增量与新消息都追加在末尾，需要跟随滚动到底部。
watch(
  () => assistantStore.messages.map((message) => message.content).join(''),
  async () => {
    await nextTick()
    scrollToBottom()
  },
)

// 首屏历史加载完成后直接定位到最新一条消息。
watch(
  () => assistantStore.loading,
  async (loading, previousLoading) => {
    if (previousLoading && !loading) {
      await nextTick()
      scrollToBottom()
    }
  },
)

/**
 * 发送消息：草稿态先创建会话拿到后端生成的 ID，再发消息并替换 URL。
 */
async function handleSend(): Promise<void> {
  const content = draft.value.trim()
  if (!content) {
    return
  }
  // 会话创建成功后再清空输入框：创建失败时保留用户已经写好的内容。
  await sendContent(content, () => {
    draft.value = ''
  })
}

/**
 * 快捷指令：与手动输入走同一条发送链路，成功后按用户消息落库。
 *
 * @param command 快捷指令文案
 */
async function handleQuickCommand(command: string): Promise<void> {
  await sendContent(command)
}

/**
 * 统一的发送链路：必要时先创建会话，再流式发送并处理错误分流。
 *
 * @param content 待发送内容
 * @param afterSessionReady 会话就绪后的回调，例如清空输入框
 * @returns 是否成功发出
 */
async function sendContent(content: string, afterSessionReady?: () => void): Promise<boolean> {
  if (!content || creating.value || assistantStore.streaming) {
    return false
  }
  creating.value = true
  try {
    const sessionId = await sessionStore.ensureSession('ASSISTANT')
    if (route.params.sessionId !== sessionId) {
      await router.replace({ name: 'ChatSessionView', params: { sessionId } })
    }
    afterSessionReady?.()
    await assistantStore.sendMessage(content)
    return true
  } catch (error) {
    if (error instanceof SseRequestError && error.status === 401) {
      userStore.clearAuth()
      await router.replace('/login')
      return false
    }
    if (error instanceof BizError) {
      // 业务错误（例如场景不支持、会话不存在）已由请求层提示，这里只保证不丢输入内容。
      if (error.code === SESSION_NOT_FOUND_CODE) {
        ElMessage.warning('会话不存在或已删除，已回到新建会话')
        sessionStore.setCurrentSession(null)
        await router.replace({ name: 'ChatView' })
      }
      return false
    }
    ElMessage.error(error instanceof Error ? error.message : '对话失败')
    return false
  } finally {
    creating.value = false
  }
}

/**
 * 引导去填写求职目标。
 */
async function handleGoProfile(): Promise<void> {
  await router.push({ name: 'ProfileView' })
}

/**
 * 引导去上传简历。
 */
async function handleGoResumes(): Promise<void> {
  await router.push({ name: 'ResumeListView' })
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
  // 头部插入更早的消息会把内容整体下推，按高度差补偿滚动位置以保持视觉位置。
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
.panel {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  padding: 12px 20px 16px;
  box-sizing: border-box;
}

.panel__error {
  margin-bottom: 8px;
}

.panel__messages {
  flex: 1;
  min-height: 0;
  padding: 4px 4px 12px;
  overflow-y: auto;
}

.panel__history {
  padding: 4px 0 12px;
  color: #c0c4cc;
  font-size: 12px;
  text-align: center;
}

.panel__guide {
  margin: 24px auto 0;
  max-width: 520px;
  padding: 20px 24px;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  background: #fafcff;
  text-align: center;
}

.panel__guide-title {
  margin: 0;
  color: #1f2d3d;
  font-size: 16px;
}

.panel__guide-text {
  margin: 8px 0 16px;
  color: #909399;
  font-size: 13px;
  line-height: 1.7;
}

.panel__shortcuts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.panel__input {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 8px;
}
</style>
