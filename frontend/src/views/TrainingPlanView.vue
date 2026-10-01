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

    <el-dialog v-model="generationDialogVisible" title="生成训练计划" width="420px">
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
      <p class="plan-dialog__hint">{{ progressText }}</p>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as planApi from '@/api/plan'
import { BizError } from '@/api/request'
import { usePlanStore } from '@/stores/plan'
import { useProfileStore } from '@/stores/profile'
import type { TrainingPlanConfirmRequiredResult } from '@/types/plan'
import { countRemainingDays, describeExistingPlan, parsePlanResultEvent } from '@/utils/plan'

/** 求职目标未填写的业务错误码，与后端 ErrorConstant.USER_PROFILE_REQUIRED 一致。 */
const PROFILE_REQUIRED_CODE = 1101

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
  try {
    await planApi.generatePlan(
      { days: generationForm.days, dailyMinutes: generationForm.dailyMinutes },
      onGenerationEvent,
    )
  } catch (error) {
    handleGenerationError(error)
  } finally {
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
  if (event === 'delta') {
    return
  }
  if (event === 'error') {
    progressText.value = ''
    ElMessage.error(readMessage(data) ?? '计划生成失败，请稍后重试')
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
  try {
    await planApi.confirmGeneration({ approved }, onGenerationEvent)
  } catch (error) {
    handleGenerationError(error)
  } finally {
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
</style>
