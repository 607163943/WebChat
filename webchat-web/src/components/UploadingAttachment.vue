<script setup lang="ts">
import { computed } from 'vue'
import { Image } from '@lucide/vue'

import UploadProgressRing from '@/components/UploadProgressRing.vue'
import type { PendingUpload } from '@/stores/chat'
import { CARD_ICON, type CardKind } from '@/lib/attachments'

/**
 * 正在上传的那一格，尺寸与传完后的回显一一对应：
 * 图片是 56×56 的方框（与缩略图同尺寸），视频与文本是 170×56 的卡片（与卡片同尺寸），
 * 这样传完之后原位换内容，预览行不会跳。
 *
 * 设计稿里上传中的图片**不显示真实缩略图**，只有灰底 + 环 + 类型图标；
 * 卡片的环则顶掉图标砖的位置，副标题从类型名换成进度。环里那个小图标只换字形、
 * 不换强调色——设计稿里上传中一律是中性灰，强调色是「已就绪」之后才出现的。
 */
const props = defineProps<{ pending: PendingUpload }>()

const percent = computed(() => Math.round(props.pending.progress * 100))

/** 环里的图标只区分是不是图片，这里把种类收窄一下好去查图标表 */
const cardKind = computed<CardKind>(() => (props.pending.kind === 'text' ? 'text' : 'video'))

/**
 * 到 100% 不等于传完了：XHR 的进度只到「字节交给网络栈」为止，
 * 之后后端还要落盘、嗅探文件头、写库。这段继续报「上传中 100%」看着像卡死，
 * 所以满格后换文案，环留在满格。
 */
const status = computed(() =>
  props.pending.progress >= 1 ? '处理中…' : `上传中 ${percent.value}%`,
)
</script>

<template>
  <div
    v-if="pending.kind === 'image'"
    class="bg-muted border-border flex size-14 shrink-0 items-center justify-center rounded-xl border"
    :title="`${pending.name} · ${status}`"
  >
    <UploadProgressRing :size="38" :progress="pending.progress">
      <Image class="text-muted-foreground size-3.5" />
    </UploadProgressRing>
  </div>

  <div
    v-else
    class="border-border bg-background flex h-14 w-[170px] shrink-0 items-center gap-2.5 rounded-xl border p-2"
    :title="`${pending.name} · ${status}`"
  >
    <UploadProgressRing :size="40" :progress="pending.progress">
      <component :is="CARD_ICON[cardKind]" class="text-muted-foreground size-3.5" />
    </UploadProgressRing>

    <div class="flex min-w-0 flex-col gap-0.5">
      <span class="text-foreground truncate text-[13px] font-medium">{{ pending.name }}</span>
      <span class="text-[11.5px] font-medium text-[#0284C7]">{{ status }}</span>
    </div>
  </div>
</template>
