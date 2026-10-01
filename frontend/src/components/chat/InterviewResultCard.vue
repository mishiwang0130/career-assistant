<template>
  <div class="result">
    <div class="result__header">
      <h3 class="result__title">本场面试结果</h3>
      <p class="result__summary">{{ summary }}</p>
    </div>

    <div
      v-for="item in result.items"
      :key="`${item.questionIndex}-${item.roundNo}`"
      class="item"
    >
      <div class="item__head">
        <span class="item__index">
          第 {{ item.questionIndex }} 题{{ item.roundNo === FOLLOW_UP_ROUND ? '（追问）' : '' }}
        </span>
        <el-tag :type="tagType(item.outcome)" size="small">
          {{ item.outcomeLabel ?? item.outcome }}
        </el-tag>
        <span class="item__meta">
          难度 {{ formatDifficulty(item.difficulty) }}
          <template v-if="item.score !== null"> · {{ item.score }} 分</template>
        </span>
      </div>

      <p class="item__question">{{ item.question }}</p>
      <p v-if="item.comment" class="item__comment">{{ item.comment }}</p>

      <template v-if="item.evaluated">
        <div v-if="item.correctPoints.length" class="item__block">
          <div class="item__label">答对的点</div>
          <ul class="item__list">
            <li v-for="(point, index) in item.correctPoints" :key="index">{{ point }}</li>
          </ul>
        </div>
        <div v-if="item.missingPoints.length" class="item__block">
          <div class="item__label">漏掉的点</div>
          <ul class="item__list">
            <li v-for="(point, index) in item.missingPoints" :key="index">{{ point }}</li>
          </ul>
        </div>
        <div v-if="item.wrongPoints.length" class="item__block">
          <div class="item__label">说错的地方</div>
          <ul class="item__list">
            <li v-for="(point, index) in item.wrongPoints" :key="index">{{ point }}</li>
          </ul>
        </div>
        <div v-if="item.expressionIssues.length" class="item__block">
          <div class="item__label">表达问题</div>
          <ul class="item__list">
            <li v-for="(point, index) in item.expressionIssues" :key="index">{{ point }}</li>
          </ul>
        </div>
        <div v-if="item.referenceAnswer" class="item__block item__block--answer">
          <div class="item__label">标准答案</div>
          <p class="item__text">{{ item.referenceAnswer }}</p>
        </div>
        <div v-if="item.suggestions.length" class="item__block">
          <div class="item__label">下次这样答</div>
          <ul class="item__list">
            <li v-for="(point, index) in item.suggestions" :key="index">{{ point }}</li>
          </ul>
        </div>
      </template>
      <el-alert
        v-else
        class="item__missing"
        type="info"
        :closable="false"
        title="这道题没有拿到评分结论（评分服务不可用），只保留了判定结果"
      />

      <div class="item__block">
        <div class="item__label">你的回答</div>
        <p class="item__text item__text--answer">{{ item.answer }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import type { InterviewResult } from '@/types/interview'
import { formatDifficulty } from '@/utils/interview'

/** 追问轮次的编号，与后端 InterviewQa.ROUND_FOLLOW_UP 一致。 */
const FOLLOW_UP_ROUND = 2

const props = defineProps<{
  /** 面试结果。 */
  result: InterviewResult
}>()

/** 顶部统计：题量、作答轮数、三档判定数量与平均分。 */
const summary = computed(() => {
  const parts = [
    `共 ${props.result.questionCount} 题`,
    `作答 ${props.result.answeredCount} 轮`,
    `答到要点 ${props.result.correctCount}`,
    `有遗漏 ${props.result.partialCount}`,
    `错题 ${props.result.wrongCount}`,
  ]
  if (props.result.averageScore !== null) {
    parts.push(`平均 ${props.result.averageScore} 分`)
  }
  return parts.join(' · ')
})

/**
 * 判定结果对应的标签样式。
 *
 * @param outcome 判定结果
 */
function tagType(outcome: string): 'success' | 'warning' | 'danger' | 'info' {
  if (outcome === 'CORRECT') {
    return 'success'
  }
  if (outcome === 'PARTIAL') {
    return 'warning'
  }
  return outcome === 'WRONG' ? 'danger' : 'info'
}
</script>

<style scoped>
.result {
  margin-top: 16px;
  padding: 16px 18px;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  background: #fafcff;
}

.result__title {
  margin: 0;
  color: #1f2d3d;
  font-size: 15px;
}

.result__summary {
  margin: 6px 0 0;
  color: #909399;
  font-size: 12px;
}

.item {
  margin-top: 14px;
  padding: 12px 14px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  background: #ffffff;
}

.item__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.item__index {
  color: #1f2d3d;
  font-size: 13px;
  font-weight: 600;
}

.item__meta {
  color: #909399;
  font-size: 12px;
}

.item__question {
  margin: 8px 0 0;
  color: #1f2d3d;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.item__comment {
  margin: 6px 0 0;
  color: #606266;
  font-size: 13px;
  line-height: 1.7;
}

.item__block {
  margin-top: 10px;
}

.item__block--answer {
  padding: 10px 12px;
  border-radius: 8px;
  background: #f0f9eb;
}

.item__label {
  color: #909399;
  font-size: 12px;
}

.item__list {
  margin: 4px 0 0;
  padding-left: 18px;
  color: #1f2d3d;
  font-size: 13px;
  line-height: 1.8;
}

.item__text {
  margin: 4px 0 0;
  color: #1f2d3d;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.item__text--answer {
  color: #909399;
}

.item__missing {
  margin-top: 10px;
}
</style>
