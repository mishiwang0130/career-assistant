<template>
  <div class="message-row" :class="isUser ? 'message-row--user' : 'message-row--assistant'">
    <div class="message-bubble">
      <!-- 思考增量：模型支持思考时展示，采用弱化样式避免干扰正文 -->
      <div v-if="message.thinking" class="message-thinking">
        <span class="message-thinking__label">思考</span>
        <span class="message-thinking__text">{{ message.thinking }}</span>
      </div>

      <!-- 工具调用提示：START 为进行中，END 为已完成 -->
      <div v-if="message.tools.length" class="message-tools">
        <el-tag
          v-for="(tool, index) in message.tools"
          :key="`${tool.name}-${index}`"
          size="small"
          effect="plain"
          :type="tool.status === 'END' ? 'success' : 'warning'"
        >
          {{ tool.status === 'END' ? `工具 ${tool.name} 已完成` : `正在调用工具 ${tool.name}` }}
        </el-tag>
      </div>

      <p class="message-content">
        <span>{{ message.content }}</span>
        <span v-if="message.streaming" class="message-cursor">▍</span>
        <span v-else-if="!message.content && message.failed" class="message-empty">（未生成内容）</span>
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

.message-thinking {
  margin-bottom: 8px;
  padding: 8px 10px;
  border-left: 3px solid #dcdfe6;
  color: #909399;
  font-size: 13px;
  white-space: pre-wrap;
}

.message-thinking__label {
  margin-right: 6px;
  font-weight: 600;
}

.message-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
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
