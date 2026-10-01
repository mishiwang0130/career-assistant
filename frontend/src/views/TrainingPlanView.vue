<template>
  <div class="plan-page">
    <el-card v-loading="planStore.loading" class="plan-card">
      <template #header>
        <div class="plan-header">
          <div>
            <h1>训练计划</h1>
            <p>说清还有几天、每天能练多久，系统按薄弱点排出每天练什么。</p>
          </div>
          <el-button :disabled="generating" @click="handleGenerate">生成 / 重新规划</el-button>
        </div>
      </template>

      <el-alert
        v-if="planStore.plan?.todayReminder"
        class="plan-reminder"
        type="info"
        :closable="false"
        show-icon
        :title="`今日提醒：${planStore.plan.todayReminder.content}`"
      />

      <!-- 生成说明卡片：默认只露几行 Markdown，点「展开全文」看完整说明（需求方要的「卡片 + 可打开」形态） -->
      <el-card v-if="summaryText" class="plan-summary-card">
        <template #header>
          <div class="plan-summary-card__header">
            <span>本次计划说明</span>
            <el-button link type="primary" @click="summaryExpanded = !summaryExpanded">
              {{ summaryExpanded ? '收起' : '展开全文' }}
            </el-button>
          </div>
        </template>
        <div class="plan-summary-card__body" v-html="renderedSummary"></div>
      </el-card>

      <el-empty
        v-if="!planStore.plan?.hasPlan"
        description="还没有训练计划，输入天数和每天可练时长生成一份"
      />

      <template v-else>
        <div class="plan-overview">
          <div class="plan-overview__item">
            <span class="plan-overview__label">目标岗位</span>
            <span class="plan-overview__value">{{ planStore.plan.targetPosition ?? '未填写' }}</span>
          </div>
          <div class="plan-overview__item">
            <span class="plan-overview__label">截止日期</span>
            <span class="plan-overview__value">{{ planStore.plan.endDate }}</span>
          </div>
          <div class="plan-overview__item">
            <span class="plan-overview__label">剩余天数</span>
            <span class="plan-overview__value">{{ remainingDays }} 天</span>
          </div>
          <div class="plan-overview__item">
            <span class="plan-overview__label">每日时长</span>
            <span class="plan-overview__value">{{ planStore.plan.dailyMinutes }} 分钟</span>
          </div>
          <div class="plan-overview__item">
            <span class="plan-overview__label">完成进度</span>
            <span class="plan-overview__value">
              {{ planStore.progress.finished }}/{{ planStore.progress.total }}（{{ planStore.progress.percent }}%）
            </span>
          </div>
        </div>

        <el-alert
          v-if="planStore.plan.adjustmentReason"
          class="plan-adjustment"
          type="warning"
          :closable="false"
          show-icon
          :title="`本次调整：${planStore.plan.adjustmentReason}`"
        />

        <p v-if="planStore.plan.summary" class="plan-summary">{{ planStore.plan.summary }}</p>

        <div v-for="day in planStore.plan.days" :key="day.dayIndex" class="plan-day">
          <div class="plan-day__header">
            <span class="plan-day__title">第 {{ day.dayIndex }} 天</span>
            <span class="plan-day__meta">
              {{ day.taskDate }} · 共 {{ day.totalMinutes }} 分钟 ·
              已完成 {{ day.finishedCount }}/{{ day.tasks.length }}
            </span>
          </div>
          <div v-for="task in day.tasks" :key="task.id" class="plan-task">
            <el-checkbox
              :model-value="task.finished"
              :disabled="generating"
              @change="(checked: boolean) => handleToggle(task.id, checked)"
            />
            <div class="plan-task__body">
              <div class="plan-task__title" :class="{ 'plan-task__title--done': task.finished }">
                {{ task.topic }}
              </div>
              <div class="plan-task__meta">
                {{ task.questionType }} · 难度 {{ task.difficulty }} · {{ task.durationMinutes }} 分钟
                <span v-if="task.knowledgePoint">· {{ task.knowledgePoint }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </el-card>

    <el-dialog
      v-model="generationDialogVisible"
      title="生成训练计划"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item label="还有几天">
          <el-input-number v-model="generationForm.days" :min="1" :max="365" :step="1" step-strictly />
        </el-form-item>
        <el-form-item label="每天多长时间（分钟）">
          <el-input-number
            v-model="generationForm.dailyMinutes"
            :min="10"
            :max="600"
            :step="10"
            step-strictly
          />
        </el-form-item>
      </el-form>
      <div v-if="progressText || thinkingText || answerText || errorText" class="plan-stream">
        <p v-if="progressText" class="plan-stream__hint">{{ progressText }}</p>
        <div ref="streamRef" class="plan-stream__body">
          <p v-if="thinkingText" class="plan-stream__thinking">{{ thinkingText }}</p>
          <div v-if="answerText" class="plan-stream__answer" v-html="renderedAnswer"></div>
        </div>
        <p v-if="errorText" class="plan-stream__error">{{ errorText }}</p>
      </div>
      <template #footer>
        <el-button :disabled="generating" @click="generationDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="handleSubmitGeneration">
          {{ planStore.plan?.hasPlan ? '重新规划' : '生成计划' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as planApi from '@/api/plan'
import { BizError } from '@/api/request'
import { usePlanStore } from '@/stores/plan'
import { useProfileStore } from '@/stores/profile'
import type { TrainingPlanConfirmRequiredResult } from '@/types/plan'
import {
  buildPlanCardPreview,
  countRemainingDays,
  describeExistingPlan,
  describeGenerationProgress,
  isGenerationTerminalEvent,
  nextStreamText,
  parsePlanResultEvent,
} from '@/utils/plan'
import { renderMarkdown } from '@/utils/markdown'

/** 求职目标未填写的业务错误码，与后端 ErrorConstant.USER_PROFILE_REQUIRED 一致。 */
const PROFILE_REQUIRED_CODE = 1101

/**
 * 客户端等待上限（毫秒）。
 *
 * 后端流超时是 300 秒，这里留一点余量：超时后主动断开并复位按钮，避免连接异常时一直转圈。
 */
const GENERATION_TIMEOUT_MS = 6 * 60 * 1000

const planStore = usePlanStore()
const profileStore = useProfileStore()
const route = useRoute()
const router = useRouter()

/** 生成参数弹窗是否可见。 */
const generationDialogVisible = ref(false)

/** 是否正在生成（生成中禁用勾选与重复触发）。 */
const generating = ref(false)

/** 生成参数：天数与每天可练时长。 */
const generationForm = reactive({
  days: 7,
  dailyMinutes: 60,
})

/** 流式过程中的文本，用于给用户即时反馈。 */
const progressText = ref('')

/** 生成过程里的思考文本（正文产出后清空）。 */
const thinkingText = ref('')

/** 生成过程里的正文文本（计划说明）。 */
const answerText = ref('')

/** 本轮生成的失败原因，展示在弹窗里而不是只弹一个 toast。 */
const errorText = ref('')

/** 计划说明卡片是否展开全文。 */
const summaryExpanded = ref(false)

/** 生成过程区域的滚动容器。 */
const streamRef = ref<HTMLElement | null>(null)

/** 计划说明正文：优先用落库的概要，没有时用本次流式产出的正文。 */
const summaryText = computed(() => {
  const summary = planStore.plan?.hasPlan ? planStore.plan.summary : ''
  return summary?.trim() ? summary : answerText.value.trim() ? answerText.value : ''
})

/** 折叠预览（默认只露几行）。 */
const renderedSummary = computed(() =>
  renderMarkdown(summaryExpanded.value ? summaryText.value : buildPlanCardPreview(summaryText.value)),
)

/** 生成过程正文的 Markdown 渲染结果。 */
const renderedAnswer = computed(() => renderMarkdown(answerText.value))

// 生成过程中让过程区自动滚到底部，用户能一直看到最新产出。
watch([thinkingText, answerText, progressText], async () => {
  await nextTick()
  if (streamRef.value) {
    streamRef.value.scrollTop = streamRef.value.scrollHeight
  }
})

/** 本次生成的取消句柄，超时或用户关闭弹窗时中断等待。 */
let generationAbort: AbortController | null = null

/** 剩余天数：按截止日期与今天实时算出，刷新页面会随日期变化。 */
const remainingDays = computed(() => {
  const plan = planStore.plan
  if (!plan?.hasPlan) {
    return 0
  }
  return countRemainingDays(plan.endDate)
})

onMounted(async () => {
  // 求职目标未填写时按 F4 口径跳设置页，填完回到本页。
  await profileStore.ensureLoaded()
  if (!profileStore.filled) {
    ElMessage.warning('先填好目标岗位和工作年限，训练计划才有依据')
    await router.replace({ name: 'ProfileView', query: { redirect: route.fullPath } })
    return
  }
  try {
    await planStore.loadPlan()
    // 进入计划页即清零角标：提醒已经展示在页面上，不需要再占角标。
    await planStore.markAllRead()
  } catch (error) {
    if (error instanceof BizError && error.code === PROFILE_REQUIRED_CODE) {
      await router.replace({ name: 'ProfileView', query: { redirect: route.fullPath } })
    }
    // 其它错误已由请求层统一提示。
  }
})

/**
 * 打开生成参数弹窗。
 */
function handleGenerate(): void {
  progressText.value = ''
  generationDialogVisible.value = true
}

/**
 * 提交生成参数并按两阶段链路处理 SSE 事件。
 */
async function handleSubmitGeneration(): Promise<void> {
  generating.value = true
  progressText.value = '正在按你的薄弱点排计划…'
  thinkingText.value = ''
  answerText.value = ''
  errorText.value = ''
  generationAbort = new AbortController()
  const timer = window.setTimeout(() => {
    generationAbort?.abort()
  }, GENERATION_TIMEOUT_MS)
  try {
    await planApi.generatePlan(
      { days: generationForm.days, dailyMinutes: generationForm.dailyMinutes },
      onGenerationEvent,
      generationAbort.signal,
    )
  } catch (error) {
    handleGenerationError(error)
  } finally {
    window.clearTimeout(timer)
    generationAbort = null
    generating.value = false
  }
}

/**
 * 处理生成流的 SSE 事件。
 *
 * @param event 事件名
 * @param data 事件数据（单行 JSON）
 */
function onGenerationEvent(event: string, data: string): void {
  // 思考与正文按会话里的同一套展示口径累积：正文一出现就丢弃思考。
  const streamed = nextStreamText({ thinking: thinkingText.value, answer: answerText.value }, event, data)
  if (streamed) {
    thinkingText.value = streamed.thinking
    answerText.value = streamed.answer
    return
  }
  // 工具事件只用来给一个「正在做什么」的进度，不展示工具名；thinking 属于内部过程，不展示。
  const progress = describeGenerationProgress(event, data)
  if (progress) {
    progressText.value = progress
    return
  }
  if (isGenerationTerminalEvent(event)) {
    // 收到终态事件就复位按钮：不再等连接关闭，避免异常情况下一直转圈。
    generating.value = false
  }
  if (event === 'error') {
    progressText.value = ''
    errorText.value = readMessage(data) ?? '计划生成失败，请稍后重试'
    ElMessage.error(errorText.value)
    return
  }
  if (event !== 'result') {
    return
  }
  // 解析与写回状态统一交给 store：data 行是 `{"data": 结构化产物}`，解析口径有单测固定。
  const payload = planStore.applyGenerationEvent(event, data)
  if (!payload) {
    return
  }
  if (payload.type === 'plan_confirm_required') {
    progressText.value = '等待你确认是否覆盖当前计划…'
    void handleConfirmRequired(payload)
    return
  }
  if (payload.type === 'training_plan') {
    generationDialogVisible.value = false
    progressText.value = ''
    ElMessage.success('训练计划已生成')
    return
  }
  if (payload.type === 'plan_confirm_rejected') {
    generationDialogVisible.value = false
    progressText.value = ''
    ElMessage.info(payload.message)
  }
}

/**
 * 覆盖确认：只有用户确认后才继续生成，未确认时后端不会覆盖已有计划。
 *
 * @param payload 确认请求载荷
 */
async function handleConfirmRequired(payload: TrainingPlanConfirmRequiredResult): Promise<void> {
  const summary = describeExistingPlan(payload.existingPlan)
  let approved = false
  try {
    await ElMessageBox.confirm(
      `${summary}。确认后会按新输入生成一份计划，原计划会保留为已结束状态。`,
      '覆盖当前计划？',
      { type: 'warning', confirmButtonText: '覆盖并生成', cancelButtonText: '保留当前计划' },
    )
    approved = true
  } catch {
    approved = false
  }
  progressText.value = approved ? '正在重新规划…' : ''
  generating.value = approved
  generationAbort = new AbortController()
  const timer = window.setTimeout(() => {
    generationAbort?.abort()
  }, GENERATION_TIMEOUT_MS)
  try {
    await planApi.confirmGeneration({ approved }, onGenerationEvent, generationAbort.signal)
  } catch (error) {
    handleGenerationError(error)
  } finally {
    window.clearTimeout(timer)
    generationAbort = null
    generating.value = false
    if (!approved) {
      generationDialogVisible.value = false
    }
  }
}

/**
 * 勾选 / 取消勾选任务。
 *
 * @param taskId 任务 ID
 * @param finished 目标状态
 */
async function handleToggle(taskId: number, finished: boolean): Promise<void> {
  const success = await planStore.toggleTask(taskId, finished)
  if (!success) {
    ElMessage.error('任务状态保存失败，请重试')
  }
}

/**
 * 处理进流前后的失败：求职目标缺失跳设置页，其它按统一提示。
 *
 * @param error 异常
 */
function handleGenerationError(error: unknown): void {
  // 客户端主动断开（等待超时）走这里：给一句可理解的说明，而不是笼统的失败提示。
  if (error instanceof DOMException && error.name === 'AbortError') {
    ElMessage.warning('生成用时过长，已停止等待；可以稍后重试')
    return
  }
  if (error instanceof BizError) {
    if (error.code === PROFILE_REQUIRED_CODE) {
      void router.replace({ name: 'ProfileView', query: { redirect: route.fullPath } })
      return
    }
    ElMessage.error(error.message)
    return
  }
  ElMessage.error('计划生成失败，请稍后重试')
}

/**
 * 读取 error 事件里的提示文字。
 *
 * @param data 单行 JSON
 * @returns 提示文字，解析失败时返回 null
 */
function readMessage(data: string): string | null {
  try {
    const parsed = JSON.parse(data) as { message?: string }
    return parsed.message ?? null
  } catch {
    return null
  }
}
</script>

<style scoped>
.plan-page {
  height: 100%;
  padding: 24px;
  overflow-y: auto;
  box-sizing: border-box;
}

.plan-card {
  border-radius: 12px;
}

.plan-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.plan-header h1 {
  margin: 0;
  color: #1f2d3d;
  font-size: 18px;
}

.plan-header p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 13px;
}

.plan-reminder,
.plan-adjustment {
  margin-bottom: 16px;
}

.plan-overview {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  margin-bottom: 16px;
}

.plan-overview__item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.plan-overview__label {
  color: #909399;
  font-size: 12px;
}

.plan-overview__value {
  color: #1f2d3d;
  font-size: 15px;
}

.plan-summary {
  margin: 0 0 16px;
  color: #606266;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.plan-day {
  margin-bottom: 18px;
}

.plan-day__header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}

.plan-day__title {
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 600;
}

.plan-day__meta {
  color: #909399;
  font-size: 12px;
}

.plan-task {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed #f2f3f5;
}

.plan-task__body {
  flex: 1;
}

.plan-task__title {
  color: #1f2d3d;
  font-size: 14px;
}

.plan-task__title--done {
  color: #a8abb2;
  text-decoration: line-through;
}

.plan-task__meta {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
}

.plan-dialog__hint {
  margin: 0;
  color: #909399;
  font-size: 12px;
}

.plan-summary-card {
  margin-bottom: 16px;
  border-radius: 12px;
}

.plan-summary-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 600;
  color: #1f2d3d;
}

.plan-summary-card__body {
  color: #606266;
  font-size: 13px;
  line-height: 1.7;
}

.plan-summary-card__body :deep(h1),
.plan-summary-card__body :deep(h2),
.plan-summary-card__body :deep(h3) {
  margin: 8px 0;
  font-size: 14px;
}

.plan-summary-card__body :deep(pre) {
  overflow-x: auto;
  padding: 8px;
  background: #f7f8fa;
  border-radius: 6px;
}

.plan-stream {
  margin-top: 12px;
}

.plan-stream__hint {
  margin: 0 0 6px;
  color: #909399;
  font-size: 12px;
}

.plan-stream__body {
  max-height: 220px;
  padding: 10px;
  overflow-y: auto;
  background: #f7f8fa;
  border-radius: 8px;
}

.plan-stream__thinking {
  margin: 0;
  color: #a8abb2;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.plan-stream__answer {
  color: #303133;
  font-size: 13px;
  line-height: 1.7;
}

.plan-stream__error {
  margin: 8px 0 0;
  color: #f56c6c;
  font-size: 12px;
  line-height: 1.6;
}
</style>
