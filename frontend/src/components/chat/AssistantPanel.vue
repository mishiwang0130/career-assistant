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
      <el-empty
        v-if="!assistantStore.messages.length && !assistantStore.loading"
        description="发送一条消息开始对话"
      />
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
import { nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { BizError } from '@/api/request'
import MessageBubble from '@/components/MessageBubble.vue'
import { useAssistantStore } from '@/stores/assistant'
import { useSessionStore } from '@/stores/session'
import { useUserStore } from '@/stores/user'
import { SseRequestError } from '@/utils/sse'

/** 距离顶部多少像素时触发加载更早的消息。 */
const SCROLL_LOAD_THRESHOLD = 40

/** 会话不存在的业务错误码。 */
const SESSION_NOT_FOUND_CODE = 1051

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const assistantStore = useAssistantStore()
const userStore = useUserStore()

/** 输入框内容。 */
const draft = ref('')

/** 是否正在创建草稿会话。 */
const creating = ref(false)

/** 消息滚动容器。 */
const messageListRef = ref<HTMLElement | null>(null)

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
  if (!content || creating.value || assistantStore.streaming) {
    return
  }
  creating.value = true
  try {
    const sessionId = await sessionStore.ensureSession('ASSISTANT')
    if (route.params.sessionId !== sessionId) {
      await router.replace({ name: 'ChatSessionView', params: { sessionId } })
    }
    // 会话创建成功后再清空输入框：创建失败时保留用户已经写好的内容。
    draft.value = ''
    await assistantStore.sendMessage(content)
  } catch (error) {
    if (error instanceof SseRequestError && error.status === 401) {
      userStore.clearAuth()
      await router.replace('/login')
      return
    }
    if (error instanceof BizError) {
      // 业务错误（例如场景不支持、会话不存在）已由请求层提示，这里只保证不丢输入内容。
      if (error.code === SESSION_NOT_FOUND_CODE) {
        ElMessage.warning('会话不存在或已删除，已回到新建会话')
        sessionStore.setCurrentSession(null)
        await router.replace({ name: 'ChatView' })
      }
      return
    }
    ElMessage.error(error instanceof Error ? error.message : '对话失败')
  } finally {
    creating.value = false
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

.panel__input {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-top: 8px;
}
</style>
