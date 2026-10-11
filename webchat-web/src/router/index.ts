import { createRouter, createWebHistory } from 'vue-router'

import ChatView from '@/views/ChatView.vue'
import NotFoundView from '@/views/NotFoundView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      // 会话 id 挂在末段（/123 是会话 123），没有 id 就是「新会话」草稿态（/）。
      // 末段限定成正整数：/foo、/0、/a/b 不是「打不开的会话」，而是根本不存在的地址，
      // 交给下面的兜底路由。
      // 分工要看清——路由只判「这像不像一个会话地址」，/400 这种像的照样放进来；
      // 「这个会话到底在不在」得问后端，问出来不在时由 ChatView 落回新会话并提示，
      // 仍停在这个页面上，不走 404
      path: '/:id([1-9]\\d*)?',
      name: 'chat',
      component: ChatView,
    },
    {
      // 兜底。通配路由的优先级最低，又排在 chat 之后，只有前面没人认领的地址才落到这里
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: NotFoundView,
    },
  ],
})

export default router
