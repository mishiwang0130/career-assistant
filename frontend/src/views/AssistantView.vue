<template>
  <div class="assistant-page">
    <el-card class="assistant-card">
      <template #header>
        <div class="assistant-header">
          <div>
            <h1>通用助手</h1>
            <p>会话 ID：{{ assistantStore.sessionId }}</p>
          </div>
          <div class="assistant-actions">
            <el-button :disabled="assistantStore.streaming" @click="handleNewSession">新会话</el-button>
            <el-button
              type="danger"
              plain
              :disabled="assistantStore.streaming || !assistantStore.messages.length"
              @click="handleClearSession"
            >
              清空会话
            </el-button>
            <el-button plain @click="handleBack">返回首页</el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="assistantStore.errorMessage"
        class="assistant-error"
        type="error"
        :closable="false"
        show-icon
        :title="assistantStore.errorMessage"
      />

      <div ref="messageListRef" v-loading="assistantStore.loading" class="assistant-messages">
        <el-empty v-if="!assistantStore.messages.length" description="发送一条消息开始对话" />
        <MessageBubble
          v-for="message in assistantStore.messages"
          :key="message.id"
          :message="message"
        />
      </div>

      <div class="assistant-input">
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
          :loading="assistantStore.streaming"
          :disabled="!draft.trim()"
          @click="handleSend"
        >
          发送
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import MessageBubble from '@/components/MessageBubble.vue'
import { useAssistantStore } from '@/stores/assistant'
import { useUserStore } from '@/stores/user'
import { SseRequestError } from '@/utils/sse'

const router = useRouter()
const assistantStore = useAssistantStore()
const userStore = useUserStore()

const draft = ref('')
const messageListRef = ref<HTMLElement | null>(null)

// 刷新页面后从后端补齐当前会话的历史消息。
onMounted(async () => {
  await loadHistoryQuietly()
})

// 流式增量会不断追加内容，需要跟随滚动到底部。
watch(
  () => assistantStore.messages.map((message) => message.content).join(''),
  async () => {
    await nextTick()
    scrollToBottom()
  },
)

/**
 * 加载历史消息，401 时回到登录页。
 */
async function loadHistoryQuietly(): Promise<void> {
  try {
    await assistantStore.loadHistory()
  } catch {
    ElMessage.error('历史消息加载失败')
  }
}

/**
 * 发送消息。
 */
async function handleSend(): Promise<void> {
  const content = draft.value.trim()
  if (!content || assistantStore.streaming) {
    return
  }
  draft.value = ''
  try {
    await assistantStore.sendMessage(content)
  } catch (error) {
    // 进流前失败：401 直接回登录页，其余错误已在 store 中提示。
    if (error instanceof SseRequestError && error.status === 401) {
      userStore.clearAuth()
      await router.replace('/login')
      return
    }
    ElMessage.error(error instanceof Error ? error.message : '对话失败')
  }
}

/**
 * 清空当前会话。
 */
async function handleClearSession(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认清空当前会话的历史消息与上下文？', '清空会话', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await assistantStore.clearSession()
    ElMessage.success('会话已清空')
  } catch {
    ElMessage.error('清空会话失败')
  }
}

/**
 * 切换到一个新的会话 ID。
 */
function handleNewSession(): void {
  assistantStore.startNewSession()
  draft.value = ''
  ElMessage.success('已切换到新会话')
}

/**
 * 返回首页。
 */
async function handleBack(): Promise<void> {
  await router.push('/')
}

/**
 * 滚动消息列表到底部。
 */
function scrollToBottom(): void {
  const element = messageListRef.value
  if (element) {
    element.scrollTop = element.scrollHeight
  }
}
</script>

<style scoped>
.assistant-page {
  min-height: 100vh;
  padding: 24px;
  background: #f5f7fa;
}

.assistant-card {
  display: flex;
  flex-direction: column;
  max-width: 920px;
  height: calc(100vh - 48px);
  margin: 0 auto;
  border-radius: 16px;
}

.assistant-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.assistant-header h1 {
  margin: 0;
  color: #1f2d3d;
  font-size: 20px;
}

.assistant-header p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 12px;
}

.assistant-actions {
  display: flex;
  gap: 8px;
}

.assistant-error {
  margin-bottom: 12px;
}

.assistant-messages {
  overflow-y: auto;
  height: calc(100% - 190px);
  padding: 4px 4px 12px;
}

.assistant-input {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 8px;
}
</style>
