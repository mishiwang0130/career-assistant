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
        <!--
          只展示正文：工具调用属于内部实现细节，不向终端用户暴露。
          用户消息按原文展示，用户自己输入的符号不该被当成 Markdown 语法解析。
        -->
        <p v-if="isUser" class="message-content">{{ message.content }}</p>
        <!--
          助手正文按 Markdown 渲染：先经 marked 转 HTML、再由 DOMPurify 白名单过滤（见 utils/markdown.ts）。
          思考内容与正文同段时，光标挂在最后一个块级元素上，避免正文以列表或标题结尾时光标另起一行；
          正文还没产出（空元素）时用 :empty 兜底显示光标。
        -->
        <div
          v-else-if="message.content || message.streaming"
          class="message-content message-content--markdown"
          :class="{ 'message-content--streaming': message.streaming }"
          v-html="renderedContent"
        ></div>
        <p v-else class="message-content message-empty">（未生成内容）</p>
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
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{
  /** 要展示的消息。 */
  message: ChatMessage
}>()

// 系统消息按助手气泡展示，避免出现第三种难以理解的样式。
const isUser = computed(() => props.message.role === 'USER')

// 助手正文按 Markdown 渲染；用户消息不解析，renderedContent 只服务于助手气泡。
const renderedContent = computed(() => renderMarkdown(props.message.content))

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
  color: #1f2d3d;
  word-break: break-word;
}

/* 用户消息与「（未生成内容）」占位按原文折行展示，不参与 Markdown 排版。 */
p.message-content {
  margin: 0;
  white-space: pre-wrap;
}

/*
 * 流式光标：挂在最后一个块级元素的行尾，正文是列表或标题时不会另起一行；
 * 正文还没产出时容器是空的，用 :empty 兜底。
 */
.message-content--streaming:empty::after,
.message-content--streaming > :last-child::after {
  margin-left: 2px;
  content: '▍';
  animation: message-blink 1s steps(2, start) infinite;
}

/* 以下为 Markdown 正文的排版：v-html 注入的节点不带 scoped 属性，必须用 :deep 命中。 */
.message-content--markdown :deep(:first-child) {
  margin-top: 0;
}

.message-content--markdown :deep(:last-child) {
  margin-bottom: 0;
}

.message-content--markdown :deep(p) {
  margin: 0 0 8px;
}

.message-content--markdown :deep(h1),
.message-content--markdown :deep(h2),
.message-content--markdown :deep(h3),
.message-content--markdown :deep(h4),
.message-content--markdown :deep(h5),
.message-content--markdown :deep(h6) {
  margin: 14px 0 8px;
  color: #1f2d3d;
  line-height: 1.5;
}

.message-content--markdown :deep(h1) {
  font-size: 19px;
}

.message-content--markdown :deep(h2) {
  font-size: 17px;
}

.message-content--markdown :deep(h3),
.message-content--markdown :deep(h4),
.message-content--markdown :deep(h5),
.message-content--markdown :deep(h6) {
  font-size: 15px;
}

.message-content--markdown :deep(ul),
.message-content--markdown :deep(ol) {
  margin: 0 0 8px;
  padding-left: 22px;
}

.message-content--markdown :deep(li) {
  margin: 2px 0;
}

.message-content--markdown :deep(li > p) {
  margin: 0;
}

.message-content--markdown :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f4f5f7;
  font-family: 'JetBrains Mono', Consolas, Monaco, monospace;
  font-size: 12px;
}

.message-content--markdown :deep(pre) {
  margin: 0 0 8px;
  padding: 10px 12px;
  overflow-x: auto;
  border-radius: 8px;
  background: #f4f5f7;
}

.message-content--markdown :deep(pre code) {
  padding: 0;
  background: transparent;
  line-height: 1.7;
}

.message-content--markdown :deep(blockquote) {
  margin: 0 0 8px;
  padding: 6px 12px;
  border-left: 3px solid #dcdfe6;
  border-radius: 0 6px 6px 0;
  background: #f7f8fa;
  color: #6b7280;
}

.message-content--markdown :deep(table) {
  width: 100%;
  margin: 0 0 8px;
  border-collapse: collapse;
  font-size: 13px;
}

.message-content--markdown :deep(th),
.message-content--markdown :deep(td) {
  padding: 6px 10px;
  border: 1px solid #ebeef5;
  text-align: left;
}

.message-content--markdown :deep(th) {
  background: #f7f8fa;
}

.message-content--markdown :deep(a) {
  color: #409eff;
  text-decoration: none;
}

.message-content--markdown :deep(a:hover) {
  text-decoration: underline;
}

.message-content--markdown :deep(hr) {
  margin: 12px 0;
  border: none;
  border-top: 1px solid #ebeef5;
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
