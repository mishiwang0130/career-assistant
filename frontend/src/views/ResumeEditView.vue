<template>
  <div class="resume-edit-page">
    <el-card v-loading="loading" class="resume-edit-card">
      <template #header>
        <div class="edit-header">
          <div>
            <h1>编辑简历</h1>
            <p v-if="resume">来源：{{ sourceLabel(resume.sourceType) }}</p>
          </div>
          <el-button @click="goBack">返回列表</el-button>
        </div>
      </template>

      <template v-if="resume">
        <el-alert
          v-if="resume.parseStatus === 'FAILED'"
          class="parse-alert"
          type="warning"
          :closable="false"
          title="文件解析失败"
          :description="resume.parseError || '未获取到失败原因，可在下方手动补充简历正文。'"
        />

        <el-descriptions :column="2" border class="resume-meta">
          <el-descriptions-item label="解析状态">
            <el-tag :type="parseStatusType(resume.parseStatus)" size="small">
              {{ parseStatusLabel(resume.parseStatus) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="默认简历">
            {{ resume.defaultFlag ? '是' : '否' }}
          </el-descriptions-item>
          <el-descriptions-item label="文件名">
            {{ resume.fileName || '在线填写' }}
          </el-descriptions-item>
          <el-descriptions-item label="文件大小">
            {{ formatFileSize(resume.fileSize) }}
          </el-descriptions-item>
        </el-descriptions>

        <el-form label-position="top" class="resume-form">
          <el-form-item label="标题" required>
            <el-input v-model="form.title" maxlength="100" show-word-limit />
          </el-form-item>
          <el-form-item label="简历正文" required>
            <el-input v-model="form.rawText" type="textarea" :rows="22" />
          </el-form-item>
        </el-form>

        <div class="edit-actions">
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          <el-button @click="goBack">取消</el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { getResumeDetail, updateResume } from '@/api/resume'
import type { ResumeDetailRespVO, ResumeParseStatus } from '@/types/resume'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const resume = ref<ResumeDetailRespVO | null>(null)
const form = reactive({
  title: '',
  rawText: '',
})
const resumeId = computed(() => Number(route.params.id))

/**
 * 加载简历详情。
 */
async function loadResume(): Promise<void> {
  loading.value = true
  try {
    resume.value = await getResumeDetail(resumeId.value)
    form.title = resume.value.title
    form.rawText = resume.value.rawText || ''
  } catch {
    // 请求层已经统一展示错误提示。
  } finally {
    loading.value = false
  }
}

/**
 * 保存标题与正文，正文更新后后端会把解析状态修正为成功。
 */
async function handleSave(): Promise<void> {
  if (!form.title.trim() || !form.rawText.trim()) {
    ElMessage.warning('请填写标题和正文')
    return
  }
  saving.value = true
  try {
    await updateResume(resumeId.value, {
      title: form.title.trim(),
      rawText: form.rawText,
    })
    ElMessage.success('保存成功')
    await loadResume()
  } catch {
    // 请求层已经统一展示错误提示。
  } finally {
    saving.value = false
  }
}

/**
 * 返回简历列表。
 */
async function goBack(): Promise<void> {
  await router.push({ name: 'ResumeListView' })
}

/**
 * 返回来源类型展示文本。
 */
function sourceLabel(sourceType: string): string {
  return sourceType === 'UPLOAD' ? '文件上传' : '在线填写'
}

/**
 * 返回解析状态展示文本。
 */
function parseStatusLabel(parseStatus: ResumeParseStatus): string {
  if (parseStatus === 'SUCCESS') {
    return '解析成功'
  }
  if (parseStatus === 'FAILED') {
    return '解析失败'
  }
  return '待解析'
}

/**
 * 返回解析状态对应的 Element Plus 标签类型。
 */
function parseStatusType(parseStatus: ResumeParseStatus): 'success' | 'warning' | 'danger' {
  if (parseStatus === 'SUCCESS') {
    return 'success'
  }
  if (parseStatus === 'FAILED') {
    return 'danger'
  }
  return 'warning'
}

/**
 * 格式化文件大小。
 */
function formatFileSize(fileSize: number | null): string {
  if (!fileSize) {
    return '-'
  }
  if (fileSize < 1024) {
    return `${fileSize} B`
  }
  if (fileSize < 1024 * 1024) {
    return `${(fileSize / 1024).toFixed(1)} KB`
  }
  return `${(fileSize / 1024 / 1024).toFixed(1)} MB`
}

onMounted(loadResume)
</script>

<style scoped>
.resume-edit-page {
  min-height: 100vh;
  padding: 32px 24px;
  background: #f5f7fa;
}

.resume-edit-card {
  max-width: 1080px;
  margin: 0 auto;
  border-radius: 16px;
}

.edit-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.edit-header h1 {
  margin: 0;
  color: #1f2d3d;
  font-size: 22px;
}

.edit-header p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 13px;
}

.parse-alert,
.resume-meta,
.resume-form {
  margin-bottom: 20px;
}

.edit-actions {
  display: flex;
  gap: 12px;
}
</style>
