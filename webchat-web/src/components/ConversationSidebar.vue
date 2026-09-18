<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  EllipsisVertical,
  MessageSquare,
  PanelLeft,
  Plus,
  Search,
  Settings,
  Trash2,
  User,
} from '@lucide/vue'

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { formatRelativeTime } from '@/lib/time'
import type { Conversation } from '@/api/types'

const props = defineProps<{
  conversations: Conversation[]
  currentId: number | null
  loading: boolean
  collapsed: boolean
}>()

const emit = defineEmits<{
  select: [id: number]
  create: []
  remove: [id: number]
  toggle: []
}>()

const keyword = ref('')

/** 会话搜索是纯前端本地过滤——列表本来就一次性全量加载，不新增后端接口 */
const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) {
    return props.conversations
  }
  return props.conversations.filter((item) => item.title.toLowerCase().includes(text))
})

/**
 * 相对时间（刚刚 / 14:20 / 昨天）不会自己变化，
 * 靠这个定时器定期触发重算，免得页面开着久了时间停在「刚刚」。
 */
const tick = ref(0)
let timer: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  timer = setInterval(() => {
    tick.value += 1
  }, 30_000)
})

onBeforeUnmount(() => {
  clearInterval(timer)
})

function timeText(conversation: Conversation): string {
  // 依赖 tick 以便定时重算
  void tick.value
  return formatRelativeTime(conversation.updateTime)
}
</script>

<template>
  <aside
    class="bg-sidebar-bg border-border flex shrink-0 overflow-hidden transition-[width] duration-200 ease-out"
    :class="props.collapsed ? 'w-0' : 'border-r w-72'"
  >
    <!-- 内层固定 288px：收拢时是被裁切，而不是内容跟着挤压重排 -->
    <div class="flex h-full w-72 shrink-0 flex-col">
      <!-- 头部：品牌 + 折叠按钮 -->
      <header class="flex h-16 shrink-0 items-center gap-2.5 px-4">
        <div class="bg-primary flex size-7 items-center justify-center rounded-lg">
          <MessageSquare class="text-primary-foreground size-[15px]" />
        </div>
        <span class="text-[15.5px] font-semibold">WebChat</span>
        <button
          type="button"
          class="text-muted-foreground hover:text-foreground ml-auto transition-colors"
          title="收起侧边栏"
          @click="emit('toggle')"
        >
          <PanelLeft class="size-4" />
        </button>
      </header>
      <div class="border-border border-b" />

      <!-- 主体：新建 / 搜索 / 列表 -->
      <div class="flex min-h-0 flex-1 flex-col gap-3.5 px-3 py-3.5">
        <button
          type="button"
          class="bg-primary text-primary-foreground hover:bg-primary/90 flex h-9 shrink-0 items-center justify-center gap-2 rounded-lg text-[13.5px] font-medium transition-colors"
          @click="emit('create')"
        >
          <Plus class="size-4" />
          新建对话
        </button>

        <div
          class="bg-background border-border flex h-9 shrink-0 items-center gap-2 rounded-lg border px-2.5"
        >
          <Search class="text-muted-foreground size-[15px] shrink-0" />
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索对话"
            class="placeholder:text-subtle-fg min-w-0 flex-1 bg-transparent text-[13px] outline-none"
          />
        </div>

        <div
          class="text-muted-foreground flex h-[17px] shrink-0 items-center px-1 text-[11.5px] font-semibold tracking-[0.6px]"
        >
          <span>对话列表</span>
          <span class="ml-auto font-normal">{{ filtered.length }}</span>
        </div>

        <div class="-mx-1 min-h-0 flex-1 space-y-0.5 overflow-y-auto px-1">
          <p
            v-if="loading && !conversations.length"
            class="text-muted-foreground px-2 py-1 text-[13px]"
          >
            加载中…
          </p>
          <p v-else-if="!filtered.length" class="text-muted-foreground px-2 py-1 text-[13px]">
            {{ conversations.length ? '没有匹配的对话' : '还没有对话' }}
          </p>

          <div
            v-for="conversation in filtered"
            :key="conversation.id"
            class="group hover:bg-muted/70 flex h-[38px] cursor-pointer items-center gap-2 rounded-lg px-2.5 transition-colors"
            :class="{ 'bg-muted-active hover:bg-muted-active': conversation.id === currentId }"
            role="button"
            tabindex="0"
            @click="emit('select', conversation.id)"
            @keydown.enter.prevent="emit('select', conversation.id)"
            @keydown.space.prevent="emit('select', conversation.id)"
          >
            <span
              class="min-w-0 flex-1 truncate text-[13.5px]"
              :class="conversation.id === currentId ? 'font-semibold' : 'font-medium'"
            >
              {{ conversation.title }}
            </span>
            <span class="text-muted-foreground shrink-0 text-[11px]">
              {{ timeText(conversation) }}
            </span>

            <DropdownMenu>
              <DropdownMenuTrigger
                class="text-fg-secondary hover:text-foreground flex size-5 shrink-0 items-center justify-center rounded opacity-0 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100 focus-visible:opacity-100"
                :class="{ 'opacity-100': conversation.id === currentId }"
                title="更多操作"
                @click.stop
                @pointerdown.stop
              >
                <EllipsisVertical class="size-4" />
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" :side-offset="4" @click.stop>
                <DropdownMenuItem
                  class="text-destructive focus:text-destructive text-[13px]"
                  @select="emit('remove', conversation.id)"
                >
                  <Trash2 class="size-3.5" />
                  删除
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
      </div>

      <!-- 底部用户区。登录功能未开发，user_id 目前是固定值，这里如实说明而不是伪造用户信息 -->
      <div class="border-border shrink-0 border-t">
        <div class="flex h-[62px] items-center gap-2.5 px-3.5">
          <div
            class="bg-muted text-fg-secondary flex size-[30px] shrink-0 items-center justify-center rounded-full"
          >
            <User class="size-4" />
          </div>
          <div class="min-w-0 flex-1">
            <p class="truncate text-[13px] font-medium">未登录用户</p>
            <p class="text-muted-foreground truncate text-[11.5px]">登录功能待开发</p>
          </div>
          <Settings class="text-muted-foreground size-4 shrink-0" />
        </div>
      </div>
    </div>
  </aside>
</template>
