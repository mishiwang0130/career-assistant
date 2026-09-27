<template>
  <div class="login-page">
    <el-card class="login-card">
      <template #header>
        <div class="header">
          <h1>求职智能助手</h1>
          <p>账号与技术底座演示</p>
        </div>
      </template>

      <el-radio-group v-model="mode" class="mode-switch" @change="handleModeChange">
        <el-radio-button value="login">登录</el-radio-button>
        <el-radio-button value="register">注册</el-radio-button>
      </el-radio-group>

      <el-form
        ref="formRef"
        :model="form"
        :rules="currentRules"
        label-position="top"
        @keyup.enter="handleSubmit"
      >
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" maxlength="20" placeholder="4-20 位字母、数字或下划线" />
        </el-form-item>
        <el-form-item v-if="mode === 'register'" label="昵称" prop="nickname">
          <el-input v-model="form.nickname" maxlength="20" placeholder="请输入昵称" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            maxlength="32"
            placeholder="6-32 位密码"
          />
        </el-form-item>
        <el-button class="submit-button" type="primary" :loading="loading" @click="handleSubmit">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { useUserStore } from '@/stores/user'

type AuthMode = 'login' | 'register'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const mode = ref<AuthMode>('login')
const loading = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  username: '',
  password: '',
  nickname: '',
})

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]{4,20}$/,
      message: '用户名须为 4-20 位字母、数字或下划线',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度须为 6-32 位', trigger: 'blur' },
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 1, max: 20, message: '昵称长度须为 1-20 位', trigger: 'blur' },
  ],
}

const currentRules = computed(() => (mode.value === 'login' ? loginRules : registerRules))

function handleModeChange(): void {
  formRef.value?.clearValidate()
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }

  loading.value = true
  try {
    if (mode.value === 'login') {
      await userStore.login({
        username: form.username,
        password: form.password,
      })
    } else {
      await userStore.register({
        username: form.username,
        password: form.password,
        nickname: form.nickname,
      })
    }
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch {
    // 请求层已经展示错误信息。
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background: linear-gradient(135deg, #eef4ff 0%, #f7f9fc 55%, #e9fbf5 100%);
}

.login-card {
  width: min(440px, 100%);
  border-radius: 16px;
}

.header {
  text-align: center;
}

.header h1 {
  margin: 0;
  color: #1f2d3d;
  font-size: 24px;
}

.header p {
  margin: 8px 0 0;
  color: #909399;
  font-size: 13px;
}

.mode-switch {
  display: flex;
  justify-content: center;
  margin-bottom: 20px;
}

.submit-button {
  width: 100%;
}
</style>
