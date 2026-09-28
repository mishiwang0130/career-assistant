import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'LoginView',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    meta: { requiresAuth: true },
    // 应用入口固定落到新建会话草稿态，历史会话只能从左侧栏打开。
    redirect: '/chat',
    children: [
      {
        path: 'chat',
        name: 'ChatView',
        component: () => import('@/views/ChatView.vue'),
      },
      {
        path: 'chat/:sessionId',
        name: 'ChatSessionView',
        component: () => import('@/views/ChatView.vue'),
      },
      {
        path: 'resumes',
        name: 'ResumeListView',
        component: () => import('@/views/ResumeListView.vue'),
      },
      {
        path: 'resumes/:id/edit',
        name: 'ResumeEditView',
        component: () => import('@/views/ResumeEditView.vue'),
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/chat',
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

// 路由守卫负责页面级登录态判断，接口级 401 由 request.ts 自动刷新处理。
router.beforeEach(async (to) => {
  const userStore = useUserStore()
  const requiresAuth = to.meta.requiresAuth !== false

  if (!requiresAuth) {
    if (to.path === '/login' && userStore.isLoggedIn) {
      // 已登录用户访问登录页时直接进入新建会话。
      return '/chat'
    }
    return true
  }

  if (!userStore.isLoggedIn) {
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    }
  }

  if (!userStore.user) {
    try {
      await userStore.fetchCurrentUser()
    } catch {
      userStore.clearAuth()
      return {
        path: '/login',
        query: { redirect: to.fullPath },
      }
    }
  }
  return true
})

export default router
