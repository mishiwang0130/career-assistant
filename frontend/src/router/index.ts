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
    name: 'HomeView',
    component: () => import('@/views/HomeView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

router.beforeEach(async (to) => {
  const userStore = useUserStore()
  const requiresAuth = to.meta.requiresAuth !== false

  if (!requiresAuth) {
    if (to.path === '/login' && userStore.isLoggedIn) {
      return '/'
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
