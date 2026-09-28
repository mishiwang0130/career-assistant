<template>
  <el-dialog
    v-model="visible"
    title="先把求职目标填上"
    width="520px"
    :close-on-click-modal="false"
    append-to-body
  >
    <p class="prompt__text">
      填好目标岗位和当前工作年限，模拟面试才能按你的方向出题，训练计划才有依据。
    </p>
    <p class="prompt__hint">
      两个字段都是必填，之后可以随时修改；不填也不影响你继续用其它功能。
    </p>
    <template #footer>
      <el-button @click="handleLater">稍后再说</el-button>
      <el-button type="primary" @click="handleGoProfile">去填写</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import { useProfileStore } from '@/stores/profile'

const router = useRouter()
const profileStore = useProfileStore()

/**
 * 弹窗可见性直接绑状态：本次登录是否已提示过的标记在弹窗出现时写入，关闭后不再打开。
 */
const visible = computed({
  get: () => profileStore.promptDialogVisible,
  set: (value: boolean) => {
    if (value) {
      profileStore.promptDialogVisible = true
      return
    }
    profileStore.closePrompt()
  },
})

/**
 * 稍后再说：只关闭弹窗，不阻塞任何操作。
 */
function handleLater(): void {
  profileStore.closePrompt()
}

/**
 * 去填写：关闭弹窗并进入求职目标设置页。
 */
async function handleGoProfile(): Promise<void> {
  profileStore.closePrompt()
  await router.push({ name: 'ProfileView' })
}
</script>

<style scoped>
.prompt__text {
  margin: 0;
  color: #1f2d3d;
  font-size: 14px;
  line-height: 1.7;
}

.prompt__hint {
  margin: 8px 0 0;
  color: #909399;
  font-size: 13px;
  line-height: 1.7;
}
</style>
