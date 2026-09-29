<template>
  <div class="report">
    <div class="report__header">
      <h3 class="report__title">本场面试报告</h3>
      <el-tag :type="statusTagType" size="small">{{ report.statusLabel ?? report.status }}</el-tag>
      <span v-if="report.generatedAt" class="report__time">生成于 {{ report.generatedAt }}</span>
    </div>

    <!-- 生成中：报告由后台任务生成，先给状态与进度感，不阻塞用户继续操作 -->
    <div v-if="report.status === 'GENERATING'" class="report__pending" v-loading="true" element-loading-text="报告生成中…">
      <p class="report__pending-text">
        报告正在后台生成，你可以先看上面的逐题点评，生成完成后这里会自动刷新。
      </p>
      <div v-if="pollingExhausted" class="report__pending-actions">
        <span class="report__pending-hint">等了有点久，可以点这里再取一次最新状态。</span>
        <el-button size="small" @click="emit('refresh')">刷新</el-button>
      </div>
    </div>

    <!-- 失败：给明确原因与重试入口，不卡在「生成中」 -->
    <el-alert
      v-else-if="report.status === 'FAILED'"
      class="report__failed"
      type="error"
      :closable="false"
      show-icon
      :title="report.errorMessage ?? '报告生成失败'"
    >
      <div class="report__failed-actions">
        <el-button size="small" type="primary" :loading="retrying" @click="emit('retry')">重试生成</el-button>
      </div>
    </el-alert>

    <!-- 已完成：错题清单、薄弱点清单、知识点掌握度、面试总结 -->
    <template v-else>
      <section v-if="report.wrongItems.length" class="report__section">
        <div class="report__section-title">错题清单</div>
        <div v-for="item in report.wrongItems" :key="`${item.questionIndex}-${item.roundNo}`" class="report__item">
          <div class="report__item-head">
            <span class="report__item-index">第 {{ item.questionIndex }} 题</span>
            <el-tag type="danger" size="small">{{ item.outcomeLabel ?? '完全不会或答错' }}</el-tag>
          </div>
          <p class="report__question">{{ item.question }}</p>
          <p v-if="item.comment" class="report__comment">{{ item.comment }}</p>
          <p v-if="item.knowledgePoints.length" class="report__points">
            涉及知识点：{{ item.knowledgePoints.join('、') }}
          </p>
        </div>
      </section>

      <section v-if="report.weaknesses.length" class="report__section">
        <div class="report__section-title">薄弱点清单</div>
        <ul class="report__list">
          <li v-for="(item, index) in report.weaknesses" :key="index">
            <span class="report__point">{{ item.knowledgePoint }}</span>
            <span class="report__level">{{ item.masteryLevelLabel ?? '待补强' }}</span>
            <span v-if="item.comment" class="report__point-comment">{{ item.comment }}</span>
          </li>
        </ul>
      </section>

      <section v-if="report.mastery.length" class="report__section">
        <div class="report__section-title">知识点掌握度</div>
        <div v-for="item in report.mastery" :key="item.knowledgePoint" class="report__mastery">
          <div class="report__mastery-head">
            <span class="report__point">{{ item.knowledgePoint }}</span>
            <span class="report__level">{{ item.masteryLevelLabel ?? '—' }} · {{ item.masteryScore }} 分</span>
          </div>
          <el-progress
            :percentage="item.masteryScore"
            :stroke-width="10"
            :show-text="false"
            :color="masteryColor(item)"
          />
          <div class="report__mastery-meta">
            证据 {{ item.evidenceCount }} 条{{ item.weak ? ' · 已列入薄弱点' : '' }}
          </div>
        </div>
      </section>

      <section class="report__section">
        <div class="report__section-title">面试总结</div>
        <p class="report__summary">{{ report.summary }}</p>
        <div v-if="report.highlights.length" class="report__block">
          <div class="report__label">亮点</div>
          <ul class="report__list">
            <li v-for="(item, index) in report.highlights" :key="index">{{ item }}</li>
          </ul>
        </div>
        <div v-if="report.suggestions.length" class="report__block">
          <div class="report__label">下一步建议</div>
          <ul class="report__list">
            <li v-for="(item, index) in report.suggestions" :key="index">{{ item }}</li>
          </ul>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import type { InterviewReportResult, KnowledgeMasteryItem } from '@/types/interview'

const props = defineProps<{
  /** 面试报告（生成中 / 已完成 / 生成失败三态共用同一份结构）。 */
  report: InterviewReportResult
  /** 是否正在重试生成。 */
  retrying: boolean
  /** 轮询是否已经超时（超时后给手动刷新入口）。 */
  pollingExhausted: boolean
}>()

const emit = defineEmits<{
  /** 请求重试生成报告。 */
  (event: 'retry'): void
  /** 请求再取一次最新状态。 */
  (event: 'refresh'): void
}>()

/** 报告状态对应的标签样式。 */
const statusTagType = computed<'warning' | 'success' | 'danger' | 'info'>(() => {
  if (props.report.status === 'SUCCEEDED') {
    return 'success'
  }
  if (props.report.status === 'FAILED') {
    return 'danger'
  }
  return 'warning'
})

/**
 * 掌握度进度条颜色：薄弱红、待补强橙、其余绿。
 *
 * @param item 掌握度条目
 */
function masteryColor(item: KnowledgeMasteryItem): string {
  if (item.masteryScore >= 75) {
    return '#67c23a'
  }
  return item.masteryScore >= 60 ? '#409eff' : '#e6a23c'
}
</script>

<style scoped>
.report {
  margin-top: 16px;
  padding: 16px 18px;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  background: #fafcff;
}

.report__header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.report__title {
  margin: 0;
  color: #1f2d3d;
  font-size: 15px;
}

.report__time {
  color: #909399;
  font-size: 12px;
}

.report__pending {
  min-height: 80px;
  margin-top: 12px;
}

.report__pending-text {
  margin: 0;
  color: #909399;
  font-size: 13px;
  line-height: 1.8;
}

.report__pending-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.report__pending-hint {
  color: #909399;
  font-size: 12px;
}

.report__failed {
  margin-top: 12px;
}

.report__failed-actions {
  margin-top: 8px;
}

.report__section {
  margin-top: 14px;
}

.report__section-title {
  margin-bottom: 6px;
  color: #1f2d3d;
  font-size: 13px;
  font-weight: 600;
}

.report__item {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #ffffff;
}

.report__item-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.report__item-index {
  color: #1f2d3d;
  font-size: 13px;
  font-weight: 600;
}

.report__question {
  margin: 6px 0 0;
  color: #1f2d3d;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.report__comment {
  margin: 4px 0 0;
  color: #606266;
  font-size: 13px;
  line-height: 1.7;
}

.report__points {
  margin: 4px 0 0;
  color: #909399;
  font-size: 12px;
}

.report__list {
  margin: 4px 0 0;
  padding-left: 18px;
  color: #1f2d3d;
  font-size: 13px;
  line-height: 1.8;
}

.report__mastery {
  margin-top: 8px;
}

.report__mastery-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.report__point {
  color: #1f2d3d;
  font-size: 13px;
}

.report__level {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}

.report__point-comment {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}

.report__mastery-meta {
  color: #c0c4cc;
  font-size: 12px;
}

.report__summary {
  margin: 0;
  color: #1f2d3d;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.report__block {
  margin-top: 10px;
}

.report__label {
  color: #909399;
  font-size: 12px;
}
</style>
