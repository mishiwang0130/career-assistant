<template>
  <div class="profile-page">
    <el-card v-loading="loading" class="profile-card">
      <template #header>
        <div class="profile-header">
          <h1>求职目标</h1>
          <p>填好目标岗位和当前工作年限，模拟面试与训练计划才有依据。</p>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="目标岗位" prop="targetPosition">
          <el-input
            v-model="form.targetPosition"
            maxlength="100"
            show-word-limit
            placeholder="例如：Java 后端开发"
          />
        </el-form-item>
        <el-form-item label="当前工作年限" prop="workYears">
          <div class="profile-years">
            <el-input-number v-model="form.workYears" :min="0" :max="60" :step="1" step-strictly />
            <span class="profile-years__hint">0 表示应届或不足一年</span>
          </div>
        </el-form-item>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { useProfileStore } from '@/stores/profile'

const profileStore = useProfileStore()

/** 首屏回填中。 */
const loading = ref(false)

/** 保存中。 */
const saving = ref(false)

/** 表单实例。 */
const formRef = ref<FormInstance>()

/** 表单数据，两个字段都是必填。 */
const form = reactive({
  targetPosition: '',
  workYears: 0,
})

const rules: FormRules = {
  targetPosition: [
    { required: true, message: '请输入目标岗位', trigger: 'blur' },
    { max: 100, message: '目标岗位不能超过 100 个字符', trigger: 'blur' },
  ],
  workYears: [{ required: true, type: 'number', message: '请输入当前工作年限', trigger: 'change' }],
}

onMounted(async () => {
  loading.value = true
  try {
    await profileStore.loadProfile()
    const current = profileStore.profile
    if (current) {
      // 已填写时回填原值，未填写时保持空表单让用户直接填。
      form.targetPosition = current.targetPosition
      form.workYears = current.workYears
    }
  } catch {
    // 请求层已统一提示，这里保持空表单，用户仍可直接填写并保存。
  } finally {
    loading.value = false
  }
})

/**
 * 保存求职目标：成功后状态里立即置为已填写，侧栏红点与登录提醒窗随之收敛。
 */
async function handleSave(): Promise<void> {
  const targetPosition = form.targetPosition.trim()
  if (!targetPosition) {
    ElMessage.warning('请先填写目标岗位')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    await profileStore.saveProfile({ targetPosition, workYears: form.workYears })
    form.targetPosition = targetPosition
    ElMessage.success('求职目标已保存')
  } catch {
    // 请求层已统一提示。
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.profile-page {
  display: flex;
  justify-content: center;
  height: 100%;
  padding: 24px;
  overflow-y: auto;
  box-sizing: border-box;
}

.profile-card {
  width: min(560px, 100%);
  align-self: flex-start;
  border-radius: 12px;
}

.profile-header h1 {
  margin: 0;
  color: #1f2d3d;
  font-size: 18px;
}

.profile-header p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 13px;
}

.profile-years {
  display: flex;
  align-items: center;
  gap: 12px;
}

.profile-years__hint {
  color: #909399;
  font-size: 13px;
}
</style>
