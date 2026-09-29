<template>
  <section class="diagnosis-card">
    <header class="diagnosis-card__header">
      <div class="diagnosis-card__score">
        <span class="diagnosis-card__score-value">{{ model.overallScore }}</span>
        <span class="diagnosis-card__score-unit">分</span>
      </div>
      <div class="diagnosis-card__summary">
        <h3 class="diagnosis-card__title">简历诊断结果</h3>
        <p class="diagnosis-card__text">{{ model.scoreSummary }}</p>
      </div>
    </header>

    <div class="diagnosis-card__section">
      <h4 class="diagnosis-card__section-title">维度评分</h4>
      <ul class="diagnosis-card__dimensions">
        <li v-for="dimension in model.dimensions" :key="dimension.name" class="diagnosis-card__dimension">
          <span class="diagnosis-card__dimension-name">{{ dimension.name }}</span>
          <span class="diagnosis-card__dimension-score">{{ dimension.score }}</span>
          <span class="diagnosis-card__dimension-comment">{{ dimension.comment }}</span>
        </li>
      </ul>
    </div>

    <div v-if="model.problems.length" class="diagnosis-card__section">
      <h4 class="diagnosis-card__section-title">问题清单</h4>
      <ol class="diagnosis-card__problems">
        <li v-for="(problem, index) in model.problems" :key="index">
          <p class="diagnosis-card__problem">
            <el-tag :type="severityTagType(problem.severity)" size="small">{{ severityLabel(problem.severity) }}</el-tag>
            {{ problem.problem }}
          </p>
          <p class="diagnosis-card__meta">出现在：{{ problem.location }}</p>
          <p class="diagnosis-card__meta">为什么是问题：{{ problem.reason }}</p>
          <p class="diagnosis-card__meta">怎么改：{{ problem.suggestion }}</p>
        </li>
      </ol>
    </div>

    <div v-if="model.highlights.length" class="diagnosis-card__section">
      <h4 class="diagnosis-card__section-title">亮点</h4>
      <ul class="diagnosis-card__list">
        <li v-for="(highlight, index) in model.highlights" :key="index">
          {{ highlight.point }}——{{ highlight.reason }}
        </li>
      </ul>
    </div>

    <div v-if="model.suggestions.length" class="diagnosis-card__section">
      <h4 class="diagnosis-card__section-title">优化建议</h4>
      <ul class="diagnosis-card__list">
        <li v-for="(suggestion, index) in model.suggestions" :key="index">
          P{{ suggestion.priority }}：{{ suggestion.content }}
        </li>
      </ul>
    </div>

    <div v-if="model.optimizedResume" class="diagnosis-card__section">
      <h4 class="diagnosis-card__section-title">优化后的简历正文</h4>
      <pre class="diagnosis-card__resume">{{ model.optimizedResume }}</pre>
    </div>

    <div v-if="model.interviewFollowUps.length" class="diagnosis-card__section">
      <h4 class="diagnosis-card__section-title">可能被追问的项目点</h4>
      <ul class="diagnosis-card__list">
        <li v-for="(item, index) in model.interviewFollowUps" :key="index">{{ item }}</li>
      </ul>
    </div>

    <footer class="diagnosis-card__footer">
      <!-- 优化后的正文只作为回答内容给出：这里另存为新简历，绝不覆盖原简历 -->
      <el-button type="primary" :loading="saving" @click="handleSaveAsNewResume">
        另存为新简历
      </el-button>
      <span class="diagnosis-card__hint">另存不会改动原简历，是否设为默认由你在简历列表决定。</span>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { createManualResume } from '@/api/resume'
import type { ResumeDiagnosisResult } from '@/types/assistant'
import { toDiagnosisCardModel } from '@/utils/diagnosis'

const props = defineProps<{
  /** 简历诊断结论。 */
  diagnosis: ResumeDiagnosisResult
}>()

const router = useRouter()

/** 是否正在保存新简历。 */
const saving = ref(false)

/** 卡片展示模型，统一兜底缺失字段。 */
const model = computed(() => toDiagnosisCardModel(props.diagnosis))

/**
 * 另存为新简历：按 F1 的在线填写接口创建一条 MANUAL 简历，成功后跳到编辑页。
 */
async function handleSaveAsNewResume(): Promise<void> {
  if (saving.value || !model.value.optimizedResume.trim()) {
    return
  }
  saving.value = true
  try {
    const resume = await createManualResume({
      title: model.value.saveAsTitle,
      rawText: model.value.optimizedResume,
    })
    ElMessage.success('已另存为新简历，原简历未改动')
    await router.push({ name: 'ResumeEditView', params: { id: resume.id } })
  } catch {
    // 请求层已经统一展示错误提示。
  } finally {
    saving.value = false
  }
}

/**
 * 严重程度展示文本。
 *
 * @param severity 严重程度
 */
function severityLabel(severity: string): string {
  if (severity === 'HIGH') {
    return '严重'
  }
  if (severity === 'LOW') {
    return '轻微'
  }
  return '一般'
}

/**
 * 严重程度对应的标签样式。
 *
 * @param severity 严重程度
 */
function severityTagType(severity: string): 'danger' | 'warning' | 'info' {
  if (severity === 'HIGH') {
    return 'danger'
  }
  if (severity === 'LOW') {
    return 'info'
  }
  return 'warning'
}
</script>

<style scoped>
.diagnosis-card {
  max-width: 78%;
  margin: 0 0 16px;
  padding: 16px 18px;
  border: 1px solid #e4e7ed;
  border-radius: 12px;
  background: #fbfdff;
  box-sizing: border-box;
}

.diagnosis-card__header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}

.diagnosis-card__score {
  display: flex;
  align-items: baseline;
  min-width: 72px;
  color: #409eff;
}

.diagnosis-card__score-value {
  font-size: 30px;
  font-weight: 600;
}

.diagnosis-card__score-unit {
  margin-left: 2px;
  font-size: 12px;
}

.diagnosis-card__title {
  margin: 0;
  color: #1f2d3d;
  font-size: 15px;
}

.diagnosis-card__text {
  margin: 4px 0 0;
  color: #606266;
  font-size: 13px;
  line-height: 1.7;
}

.diagnosis-card__section {
  margin-top: 14px;
}

.diagnosis-card__section-title {
  margin: 0 0 8px;
  color: #1f2d3d;
  font-size: 13px;
}

.diagnosis-card__dimensions,
.diagnosis-card__list,
.diagnosis-card__problems {
  margin: 0;
  padding-left: 18px;
  color: #606266;
  font-size: 13px;
  line-height: 1.8;
}

.diagnosis-card__dimension {
  list-style: none;
}

.diagnosis-card__dimension-name {
  display: inline-block;
  min-width: 96px;
  color: #1f2d3d;
}

.diagnosis-card__dimension-score {
  margin-right: 8px;
  color: #409eff;
  font-weight: 600;
}

.diagnosis-card__dimension-comment {
  color: #909399;
}

.diagnosis-card__problem {
  margin: 0;
  color: #1f2d3d;
}

.diagnosis-card__meta {
  margin: 0 0 6px;
  color: #909399;
}

.diagnosis-card__resume {
  max-height: 240px;
  margin: 0;
  padding: 10px 12px;
  overflow-y: auto;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #ffffff;
  color: #1f2d3d;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.diagnosis-card__footer {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}

.diagnosis-card__hint {
  color: #909399;
  font-size: 12px;
}
</style>
