<template>
  <div class="evaluation">
    <div class="evaluation__head">
      <span class="evaluation__title">
        第 {{ evaluation.questionIndex }} 题点评{{ evaluation.roundNo === FOLLOW_UP_ROUND ? '（追问）' : '' }}
      </span>
      <el-tag :type="tagType(evaluation.outcome)" size="small">
        {{ evaluation.outcomeLabel ?? evaluation.outcome }}
      </el-tag>
      <span class="evaluation__meta">
        难度 {{ formatDifficulty(evaluation.difficulty) }}
        <template v-if="evaluation.score !== null"> · {{ evaluation.score }} 分</template>
      </span>
    </div>

    <p v-if="evaluation.comment" class="evaluation__comment">{{ evaluation.comment }}</p>

    <template v-if="evaluation.evaluated">
      <div v-if="evaluation.correctPoints.length" class="evaluation__block">
        <div class="evaluation__label">答对的点</div>
        <ul class="evaluation__list">
          <li v-for="(point, index) in evaluation.correctPoints" :key="index">{{ point }}</li>
        </ul>
      </div>
      <div v-if="evaluation.missingPoints.length" class="evaluation__block">
        <div class="evaluation__label">漏掉的点</div>
        <ul class="evaluation__list">
          <li v-for="(point, index) in evaluation.missingPoints" :key="index">{{ point }}</li>
        </ul>
      </div>
      <div v-if="evaluation.wrongPoints.length" class="evaluation__block">
        <div class="evaluation__label">说错的地方</div>
        <ul class="evaluation__list">
          <li v-for="(point, index) in evaluation.wrongPoints" :key="index">{{ point }}</li>
        </ul>
      </div>
      <div v-if="evaluation.expressionIssues.length" class="evaluation__block">
        <div class="evaluation__label">表达问题</div>
        <ul class="evaluation__list">
          <li v-for="(point, index) in evaluation.expressionIssues" :key="index">{{ point }}</li>
        </ul>
      </div>
      <div v-if="evaluation.suggestions.length" class="evaluation__block">
        <div class="evaluation__label">建议补充</div>
        <ul class="evaluation__list">
          <li v-for="(point, index) in evaluation.suggestions" :key="index">{{ point }}</li>
        </ul>
      </div>
      <!-- 标准答案默认折叠：不是每道题都要对着答案看，但需要时随手能展开 -->
      <el-collapse v-if="evaluation.referenceAnswer" class="evaluation__answer">
        <el-collapse-item title="看标准答案" name="answer">
          <p class="evaluation__text">{{ evaluation.referenceAnswer }}</p>
        </el-collapse-item>
      </el-collapse>
    </template>
    <el-alert
      v-else
      class="evaluation__missing"
      type="info"
      :closable="false"
      title="这道题没有拿到评分结论（评分服务不可用），只保留了判定结果"
    />
  </div>
</template>

<script setup lang="ts">
import type { InterviewEvaluationResult } from '@/types/interview'
import { formatDifficulty } from '@/utils/interview'

/** 追问轮次的编号，与后端 InterviewQa.ROUND_FOLLOW_UP 一致。 */
const FOLLOW_UP_ROUND = 2

defineProps<{
  /** 逐题点评。 */
  evaluation: InterviewEvaluationResult
}>()

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
.evaluation {
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  background: #ffffff;
}

.evaluation__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.evaluation__title {
  color: #1f2d3d;
  font-size: 13px;
  font-weight: 600;
}

.evaluation__meta {
  color: #909399;
  font-size: 12px;
}

.evaluation__comment {
  margin: 8px 0 0;
  color: #606266;
  font-size: 13px;
  line-height: 1.7;
}

.evaluation__block {
  margin-top: 10px;
}

.evaluation__label {
  color: #909399;
  font-size: 12px;
}

.evaluation__list {
  margin: 4px 0 0;
  padding-left: 18px;
  color: #1f2d3d;
  font-size: 13px;
  line-height: 1.8;
}

.evaluation__answer {
  margin-top: 10px;
}

.evaluation__text {
  margin: 0;
  color: #1f2d3d;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.evaluation__missing {
  margin-top: 10px;
}
</style>
