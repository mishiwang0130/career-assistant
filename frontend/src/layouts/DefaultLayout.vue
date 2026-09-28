<template>
  <el-container class="app-shell">
    <!-- 宽屏固定左侧栏；窄屏收进抽屉，避免挤压消息区 -->
    <el-aside v-if="!isNarrow" class="app-shell__aside" width="264px">
      <AppSidebar />
    </el-aside>
    <el-drawer
      v-else
      v-model="drawerVisible"
      direction="ltr"
      size="264px"
      :with-header="false"
      class="app-shell__drawer"
    >
      <AppSidebar @navigate="drawerVisible = false" />
    </el-drawer>

    <el-container class="app-shell__main">
      <header v-if="isNarrow" class="app-shell__bar">
        <el-button link :icon="Menu" @click="drawerVisible = true">会话与资料库</el-button>
        <span class="app-shell__bar-title">求职智能助手</span>
      </header>
      <el-main class="app-shell__content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Menu } from '@element-plus/icons-vue'

import AppSidebar from '@/layouts/AppSidebar.vue'

/** 窄屏断点，与样式中的 768px 保持一致。 */
const NARROW_BREAKPOINT = '(max-width: 768px)'

/** 是否处于窄屏布局。 */
const isNarrow = ref(false)

/** 窄屏抽屉是否展开。 */
const drawerVisible = ref(false)

/** 断点监听句柄，组件卸载时释放。 */
let mediaQuery: MediaQueryList | null = null

/**
 * 断点变化时切换布局；回到宽屏时关闭抽屉，避免残留遮罩。
 */
function handleBreakpointChange(event: MediaQueryListEvent | MediaQueryList): void {
  isNarrow.value = event.matches
  if (!event.matches) {
    drawerVisible.value = false
  }
}

onMounted(() => {
  mediaQuery = window.matchMedia(NARROW_BREAKPOINT)
  handleBreakpointChange(mediaQuery)
  mediaQuery.addEventListener('change', handleBreakpointChange)
})

onBeforeUnmount(() => {
  mediaQuery?.removeEventListener('change', handleBreakpointChange)
})
</script>

<style scoped>
.app-shell {
  height: 100vh;
}

.app-shell__aside {
  border-right: 1px solid #e4e7ed;
  background: #ffffff;
}

.app-shell__main {
  min-width: 0;
  background: #f5f7fa;
}

.app-shell__bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  border-bottom: 1px solid #e4e7ed;
  background: #ffffff;
}

.app-shell__bar-title {
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 600;
}

.app-shell__content {
  padding: 0;
  overflow: hidden;
}

.app-shell__drawer :deep(.el-drawer__body) {
  padding: 0;
}
</style>
