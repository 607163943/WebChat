import { createRouter, createWebHistory } from 'vue-router'

import ChatView from '@/views/ChatView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      // 会话 id 挂在末段（/123 是会话 123），没有 id 就是「新会话」草稿态（/）。
      // 刻意不把末段限定成纯数字：/foo 这类地址也让它落在这个页面、由 ChatView 归一，
      // 否则它落到「没有任何路由匹配」的白屏上，比回落到新会话更难理解
      path: '/:id?',
      name: 'chat',
      component: ChatView,
    },
  ],
})

export default router
