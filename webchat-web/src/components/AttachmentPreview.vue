<script setup lang="ts">
import { computed } from 'vue'
import { Video, X } from '@lucide/vue'

import { resolveAttachmentUrl } from '@/api/attachments'
import type { Attachment } from '@/api/types'
import { classify, kindLabel } from '@/lib/attachments'

const props = defineProps<{ attachment: Attachment }>()

const emit = defineEmits<{ remove: [id: number] }>()

const kind = computed(() => classify(props.attachment.mimeType))
const label = computed(() => kindLabel(kind.value ?? 'image'))
</script>

<template>
  <!-- 设计稿里没有删除按钮，但不加的话选错文件只能靠发送或刷新清掉，所以在 hover 时浮出一颗 -->
  <div class="group/attachment relative shrink-0">
    <!-- 图片：56×56 圆角 12 的缩略图 -->
    <img
      v-if="kind === 'image'"
      :src="resolveAttachmentUrl(props.attachment.url)"
      :alt="props.attachment.originalName"
      class="border-border size-14 rounded-xl border object-cover"
    />

    <!-- 视频：170×56 的卡片，与设计稿里音频那颗同构（图标砖 + 文件名 + 类型） -->
    <div
      v-else
      class="border-border bg-background flex h-14 w-[170px] items-center gap-2.5 rounded-xl border p-2"
    >
      <div class="flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-[#E11D481A]">
        <Video class="size-5 text-[#E11D48]" />
      </div>
      <div class="flex min-w-0 flex-col gap-0.5">
        <span class="text-foreground truncate text-[13px] font-medium">
          {{ props.attachment.originalName }}
        </span>
        <span class="text-muted-foreground text-[11.5px]">{{ label }}</span>
      </div>
    </div>

    <button
      type="button"
      class="bg-foreground text-background absolute -top-1.5 -right-1.5 flex size-[18px] items-center justify-center rounded-full opacity-0 transition-opacity group-hover/attachment:opacity-100 focus-visible:opacity-100"
      :title="`移除「${props.attachment.originalName}」`"
      @click="emit('remove', props.attachment.id)"
    >
      <X class="size-3" />
    </button>
  </div>
</template>
