<template>
  <div class="home-page">
    <el-card class="home-card">
      <template #header>
        <div class="home-header">
          <div>
            <h1>首页</h1>
            <p>M1 账号与技术底座已就绪</p>
          </div>
          <el-button type="danger" plain @click="handleLogout">退出登录</el-button>
        </div>
      </template>

      <el-descriptions v-if="user" :column="1" border>
        <el-descriptions-item label="用户 ID">{{ user.id }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ user.username }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ user.nickname }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const user = computed(() => userStore.user)

// 刷新页面后业务 store 可能没有用户信息，需要向后端补齐。
onMounted(async () => {
  if (!userStore.user) {
    try {
      await userStore.fetchCurrentUser()
    } catch {
      userStore.clearAuth()
      await router.replace('/login')
    }
  }
})

/**
 * 退出登录并返回登录页。
 */
async function handleLogout(): Promise<void> {
  await userStore.logout()
  ElMessage.success('已退出登录')
  await router.replace('/login')
}
</script>

<style scoped>
.home-page {
  min-height: 100vh;
  padding: 40px 24px;
  background: #f5f7fa;
}

.home-card {
  max-width: 760px;
  margin: 0 auto;
  border-radius: 16px;
}

.home-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.home-header h1 {
  margin: 0;
  color: #1f2d3d;
  font-size: 22px;
}

.home-header p {
  margin: 6px 0 0;
  color: #909399;
  font-size: 13px;
}
</style>
