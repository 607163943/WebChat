<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

import { defineAsyncComponent } from 'vue'

import MessageBubble from '@/components/MessageBubble.vue'
import TypingIndicator from '@/components/TypingIndicator.vue'

// 同 MessageBubble：推迟 Shiki 的加载
const MarkdownContent = defineAsyncComponent(() => import('@/components/MarkdownContent.vue'))
import { dayKey, formatMessageDivider } from '@/lib/time'
import type { ChatMessage } from '@/api/types'

const props = defineProps<{
  messages: ChatMessage[]
  streamingText: string
  streaming: boolean
}>()

const emit = defineEmits<{ regenerate: [] }>()

/** 最后一条助手消息——只有它允许重新生成，更早的按钮置灰 */
const lastAssistantId = computed(() => {
  for (let index = props.messages.length - 1; index >= 0; index -= 1) {
    const message = props.messages[index]
    if (message?.role === 'assistant') {
      return message.id
    }
  }
  return null
})

type Row =
  | { kind: 'divider'; key: string; label: string }
  | { kind: 'message'; key: string; message: ChatMessage }

/** 按天插入时间分隔胶囊 */
const rows = computed<Row[]>(() => {
  const result: Row[] = []
  let lastDay = ''
  for (const message of props.messages) {
    const day = dayKey(message.createTime)
    if (day && day !== lastDay) {
      lastDay = day
      result.push({
        kind: 'divider',
        key: `divider-${day}`,
        label: formatMessageDivider(message.createTime),
      })
    }
    result.push({ kind: 'message', key: `message-${message.id}`, message })
  }
  return result
})

const container = ref<HTMLElement | null>(null)

/**
 * 自动贴底。仅当用户本来就停在底部附近时才滚动，
 * 避免用户向上翻看历史时被流式输出强行拽回底部。
 */
function stickToBottom(force = false): void {
  const element = container.value
  if (!element) {
    return
  }
  const distanceToBottom = element.scrollHeight - element.scrollTop - element.clientHeight
  if (force || distanceToBottom < 120) {
    element.scrollTop = element.scrollHeight
  }
}

let lastMessageCount = 0

watch(
  // 用 as const 元组，否则 Vue 会把源推断成 (number | boolean)[]
  () => [props.messages.length, props.streamingText.length, props.streaming] as const,
  async () => {
    await nextTick()
    // 会话刚载入 / 第一条消息出现时强制贴底，其余情况只在用户本就停在底部附近时才跟随
    stickToBottom(lastMessageCount === 0 && props.messages.length > 0)
    lastMessageCount = props.messages.length
  },
)

defineExpose({ stickToBottom })
</script>

<template>
  <div ref="container" class="min-h-0 flex-1 overflow-y-auto px-4 pt-7 pb-[110px]">
    <div class="mx-auto flex w-full max-w-[720px] flex-col gap-[26px]">
      <template v-for="row in rows" :key="row.key">
        <div v-if="row.kind === 'divider'" class="flex justify-center">
          <span class="bg-muted text-muted-foreground rounded-full px-2.5 py-1 text-[11.5px]">
            {{ row.label }}
          </span>
        </div>
        <MessageBubble
          v-else
          :message="row.message"
          :can-regenerate="
            !props.streaming &&
            row.message.role === 'assistant' &&
            row.message.id === lastAssistantId
          "
          @regenerate="emit('regenerate')"
        />
      </template>

      <div v-if="props.streaming" class="flex flex-col gap-3">
        <TypingIndicator v-if="!props.streamingText" />
        <!-- 流式期间也走 Markdown，未闭合的代码围栏由 renderMarkdown 补齐 -->
        <div v-else class="text-fg-secondary text-[14px] leading-[1.65]">
          <MarkdownContent :content="props.streamingText" />
        </div>
      </div>
    </div>
  </div>
</template>
