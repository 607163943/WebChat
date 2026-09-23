<script setup lang="ts">
import { defineAsyncComponent, onBeforeUnmount, ref } from 'vue'
import { Check, Copy, RefreshCw } from '@lucide/vue'

import MessageAttachments from '@/components/MessageAttachments.vue'
import { copyText } from '@/lib/clipboard'
import type { ChatMessage } from '@/api/types'

// Shiki 的语法定义体积不小（整包约 1MB），按需加载而不是挂在首屏：
// 只有真的出现助手消息时才会拉这个 chunk
const MarkdownContent = defineAsyncComponent(() => import('@/components/MarkdownContent.vue'))

const props = defineProps<{
  message: ChatMessage
  /** 是否可以重新生成：仅最后一条助手消息为真，更早的置灰 */
  canRegenerate: boolean
}>()

const emit = defineEmits<{ regenerate: [] }>()

const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

onBeforeUnmount(() => clearTimeout(timer))

async function copy(): Promise<void> {
  if (!(await copyText(props.message.content))) {
    return
  }
  copied.value = true
  clearTimeout(timer)
  timer = setTimeout(() => {
    copied.value = false
  }, 1600)
}
</script>

<template>
  <!-- 用户消息：附件在上、文字气泡在下，整体右对齐；保留换行，不做 Markdown 解析 -->
  <div v-if="props.message.role === 'user'" class="flex flex-col items-end gap-2.5">
    <MessageAttachments
      v-if="props.message.attachments.length > 0"
      :attachments="props.message.attachments"
    />
    <!-- 只发附件不打字时没有正文，别留一个空的胶囊 -->
    <div
      v-if="props.message.content"
      class="bg-muted max-w-[85%] rounded-[10px] px-3.5 py-2.5 text-[14px] leading-[1.6] wrap-break-word whitespace-pre-wrap"
    >
      {{ props.message.content }}
    </div>
  </div>

  <!-- 助手消息：通栏文本块，按 Markdown 渲染（设计稿里头像与名字行是隐藏节点，不渲染） -->
  <div v-else class="text-fg-secondary text-[14px] leading-[1.65]">
    <MarkdownContent :content="props.message.content" />

    <div class="mt-2.5 flex items-center gap-1.5">
      <button
        type="button"
        class="hover:bg-muted hover:text-fg-secondary text-muted-foreground flex h-[27px] items-center gap-[5px] rounded-md px-2 text-[12px] transition-colors"
        @click="copy"
      >
        <component :is="copied ? Check : Copy" class="size-[13px]" />
        {{ copied ? '已复制' : '复制' }}
      </button>

      <button
        type="button"
        class="hover:bg-muted hover:text-fg-secondary text-muted-foreground flex h-[27px] items-center gap-[5px] rounded-md px-2 text-[12px] transition-colors disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-inherit"
        :disabled="!props.canRegenerate"
        :title="props.canRegenerate ? '重新生成这条回复' : '只能重新生成最后一条回复'"
        @click="emit('regenerate')"
      >
        <RefreshCw class="size-[13px]" />
        重新生成
      </button>
    </div>
  </div>
</template>
