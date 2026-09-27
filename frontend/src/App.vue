<template>
  <div class="page">
    <el-card>
      <template #header>
        <b>career-assistant</b>
      </template>
      <p>前端骨架已就绪，页面从这里开始写。</p>
      <p class="tip">后端接口地址：/api（开发环境已代理到 http://localhost:8084）</p>
    </el-card>
  </div>
</template>

<script setup lang="ts"></script>

<style scoped>
.page {
  padding: 24px;
}

.tip {
  color: #909399;
  font-size: 13px;
}
</style>
