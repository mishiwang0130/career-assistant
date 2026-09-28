<template>
  <div class="message-row" :class="isUser ? 'message-row--user' : 'message-row--assistant'">
    <div class="message-bubble">
      <!-- 只展示正文：工具调用与思考过程属于内部实现细节，不向终端用户暴露 -->
      <p class="message-content">
        <span>{{ message.content }}</span>
        <span v-if="message.streaming" class="message-cursor">▍</span>
        <span v-else-if="!message.content" class="message-empty">（未生成内容）</span>
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import type { ChatMessage } from '@/types/assistant'

const props = defineProps<{
  /** 要展示的消息。 */
  message: ChatMessage
}>()

// 系统消息按助手气泡展示，避免出现第三种难以理解的样式。
const isUser = computed(() => props.message.role === 'USER')
</script>

<style scoped>
.message-row {
  display: flex;
  margin-bottom: 16px;
}

.message-row--user {
  justify-content: flex-end;
}

.message-row--assistant {
  justify-content: flex-start;
}

.message-bubble {
  max-width: 78%;
  padding: 12px 16px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 2px 8px rgb(31 45 61 / 8%);
  line-height: 1.7;
}

.message-row--user .message-bubble {
  background: #ecf5ff;
}

.message-content {
  margin: 0;
  color: #1f2d3d;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-cursor {
  margin-left: 2px;
  animation: message-blink 1s steps(2, start) infinite;
}

.message-empty {
  color: #c0c4cc;
}

@keyframes message-blink {
  to {
    visibility: hidden;
  }
}
</style>
