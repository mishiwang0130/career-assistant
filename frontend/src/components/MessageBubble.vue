<template>
  <div class="message-block">
    <div class="message-row" :class="isUser ? 'message-row--user' : 'message-row--assistant'">
      <div class="message-bubble">
        <!--
          思考过程：只在正文产出前展示，正文一出现（或本轮结束）就被 store 丢弃，
          因此这里既没有折叠、也没有回看入口。见 docs/技术约定.md「SSE 事件协议」展示边界。
        -->
        <div v-if="message.thinking" class="message-thinking">
          <span class="message-thinking__label">思考中…</span>
          <p class="message-thinking__text">{{ message.thinking }}</p>
        </div>
        <!-- 只展示正文：工具调用属于内部实现细节，不向终端用户暴露 -->
        <p class="message-content">
          <span>{{ message.content }}</span>
          <span v-if="message.streaming" class="message-cursor">▍</span>
          <span v-else-if="!message.content" class="message-empty">（未生成内容）</span>
        </p>
      </div>
    </div>
    <!-- 结构化产物挂在回答下方：当前是简历诊断卡片，F6 的点评与报告复用同一套渲染位置 -->
    <ResumeDiagnosisCard v-if="diagnosis" :diagnosis="diagnosis" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import ResumeDiagnosisCard from '@/components/chat/ResumeDiagnosisCard.vue'
import type { ChatMessage, ResumeDiagnosisResult } from '@/types/assistant'

const props = defineProps<{
  /** 要展示的消息。 */
  message: ChatMessage
}>()

// 系统消息按助手气泡展示，避免出现第三种难以理解的样式。
const isUser = computed(() => props.message.role === 'USER')

// 只有简历诊断结论才渲染卡片，其它结构化类型（后续 F6）在自己的组件落地前不渲染。
const diagnosis = computed<ResumeDiagnosisResult | null>(() => {
  const result = props.message.result
  return result && result.type === 'resume_diagnosis' ? result : null
})
</script>

<style scoped>
.message-block {
  display: flex;
  flex-direction: column;
  margin-bottom: 16px;
}

.message-row {
  display: flex;
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
}

.message-thinking__label {
  display: block;
  margin-bottom: 6px;
  color: #909399;
  font-size: 12px;
}

.message-thinking__text {
  margin: 0;
  padding: 8px 10px;
  border-left: 3px solid #dcdfe6;
  border-radius: 0 6px 6px 0;
  background: #f7f8fa;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
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
