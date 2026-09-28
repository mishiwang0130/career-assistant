<template>
  <div class="resume-page">
    <el-card class="resume-card">
      <template #header>
        <div class="resume-header">
          <div>
            <h1>简历管理</h1>
            <p>上传或在线填写简历，系统会自动解析为可编辑的文本。</p>
          </div>
          <div class="resume-actions">
            <el-upload
              :show-file-list="false"
              :before-upload="beforeUpload"
              :http-request="handleUpload"
              accept=".pdf,.doc,.docx,.txt,.md"
            >
              <el-button type="primary" :loading="uploading">上传简历</el-button>
            </el-upload>
            <el-button @click="openManualDialog">新建简历</el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="resumes" stripe>
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            {{ sourceLabel(row.sourceType) }}
          </template>
        </el-table-column>
        <el-table-column label="解析状态" width="110">
          <template #default="{ row }">
            <el-tag :type="parseStatusType(row.parseStatus)" size="small">
              {{ parseStatusLabel(row.parseStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.defaultFlag" type="success" size="small">默认</el-tag>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="文件" min-width="180">
          <template #default="{ row }">
            <span v-if="row.fileName">{{ row.fileName }}（{{ formatFileSize(row.fileSize) }}）</span>
            <span v-else class="muted">在线填写</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row.id)">编辑</el-button>
            <el-button
              link
              type="primary"
              :disabled="row.defaultFlag"
              @click="handleSetDefault(row.id)"
            >
              设为默认
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无简历" />
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="manualDialogVisible" title="新建简历" width="640px">
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="manualForm.title" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="简历正文" required>
          <el-input
            v-model="manualForm.rawText"
            type="textarea"
            :rows="14"
            placeholder="可直接粘贴简历文本"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateManual">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRawFile, UploadRequestOptions } from 'element-plus'

import {
  createManualResume,
  deleteResume,
  getResumeList,
  setDefaultResume,
  uploadResume,
} from '@/api/resume'
import type {
  ResumeListRespVO,
  ResumeManualReqVO,
  ResumeParseStatus,
  ResumeSourceType,
} from '@/types/resume'

/** 单文件最大字节数，与后端 10MB 限制保持一致。 */
const MAX_FILE_SIZE = 10 * 1024 * 1024

/** 允许上传的扩展名，与后端白名单保持一致。 */
const ALLOWED_EXTENSIONS = ['pdf', 'doc', 'docx', 'txt', 'md']

const router = useRouter()
const loading = ref(false)
const uploading = ref(false)
const submitting = ref(false)
const manualDialogVisible = ref(false)
const resumes = ref<ResumeListRespVO[]>([])
const manualForm = reactive<ResumeManualReqVO>({
  title: '',
  rawText: '',
})

/**
 * 加载当前用户简历列表。
 */
async function loadResumes(): Promise<void> {
  loading.value = true
  try {
    resumes.value = await getResumeList()
  } catch {
    // 请求层已经统一展示错误提示。
  } finally {
    loading.value = false
  }
}

/**
 * 上传前按扩展名和大小做前端预校验。
 */
function beforeUpload(file: UploadRawFile): boolean {
  const fileName = file.name.toLowerCase()
  if (!ALLOWED_EXTENSIONS.some((extension) => fileName.endsWith(`.${extension}`))) {
    ElMessage.error('文件类型不支持')
    return false
  }
  if (file.size > MAX_FILE_SIZE) {
    ElMessage.error('文件过大')
    return false
  }
  return true
}

/**
 * 执行自定义上传请求。
 */
async function handleUpload(options: UploadRequestOptions): Promise<void> {
  uploading.value = true
  try {
    const resume = await uploadResume(options.file)
    if (resume.parseStatus === 'FAILED') {
      ElMessage.warning('文件已上传，但解析失败，可在编辑页手动补充正文')
    } else {
      ElMessage.success('上传并解析成功')
    }
    await loadResumes()
  } catch {
    // 请求层已经统一展示错误提示。
  } finally {
    uploading.value = false
  }
}

/**
 * 打开在线新建弹窗。
 */
function openManualDialog(): void {
  manualForm.title = ''
  manualForm.rawText = ''
  manualDialogVisible.value = true
}

/**
 * 创建在线简历。
 */
async function handleCreateManual(): Promise<void> {
  if (!manualForm.title.trim() || !manualForm.rawText.trim()) {
    ElMessage.warning('请填写标题和正文')
    return
  }
  submitting.value = true
  try {
    await createManualResume({
      title: manualForm.title.trim(),
      rawText: manualForm.rawText,
    })
    ElMessage.success('简历已创建')
    manualDialogVisible.value = false
    await loadResumes()
  } catch {
    // 请求层已经统一展示错误提示。
  } finally {
    submitting.value = false
  }
}

/**
 * 跳转到简历编辑页。
 */
async function openEdit(id: number): Promise<void> {
  await router.push({ name: 'ResumeEditView', params: { id } })
}

/**
 * 设置默认简历。
 */
async function handleSetDefault(id: number): Promise<void> {
  try {
    await setDefaultResume(id)
    ElMessage.success('已设为默认简历')
    await loadResumes()
  } catch {
    // 请求层已经统一展示错误提示。
  }
}

/**
 * 删除简历。
 */
async function handleDelete(resume: ResumeListRespVO): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除简历“${resume.title}”吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteResume(resume.id)
    ElMessage.success('删除成功')
    await loadResumes()
  } catch {
    // 请求层已经统一展示错误提示。
  }
}

/**
 * 返回来源类型展示文本。
 */
function sourceLabel(sourceType: ResumeSourceType): string {
  return sourceType === 'UPLOAD' ? '上传' : '在线填写'
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

onMounted(loadResumes)
</script>

<style scoped>
.resume-page {
  /* 页面挂在应用壳主内容区里：自己撑满高度并内部滚动，避免出现第二层整页滚动 */
  height: 100%;
  padding: 20px 24px;
  overflow-y: auto;
  box-sizing: border-box;
}

.resume-card {
  max-width: 1280px;
  margin: 0 auto;
  border-radius: 16px;
}

.resume-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.resume-header h1 {
  margin: 0;
  color: #1f2d3d;
  font-size: 22px;
}

.resume-header p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 13px;
}

.resume-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.muted {
  color: #a8abb2;
}
</style>
