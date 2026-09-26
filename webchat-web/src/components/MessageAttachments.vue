<script setup lang="ts">
import { resolveAttachmentUrl } from '@/api/attachments'
import type { Attachment } from '@/api/types'
import { CARD_ACCENT, CARD_ICON, cardKindOf, classify, kindLabel } from '@/lib/attachments'

/**
 * 消息里回显的附件组。
 *
 * 尺寸取自设计稿「已发送附件」帧：附件组右对齐、宽 280，图片 280×190 圆角 16、
 * 非图片附件是 280×56 的卡片。比输入框里那份大一圈——发出去之后它们是内容的一部分了。
 *
 * 视频用原生播放器而不是卡片：图片给的是真实预览，视频对应的预览就是能播。
 */
defineProps<{ attachments: Attachment[] }>()

/** 供模板调用：把后端给的相对路径补成可用地址 */
const resolve = resolveAttachmentUrl

/** 供模板调用：把种类收窄成卡片种类，好去查图标与配色那两张表 */
const cardKind = cardKindOf

/** 卡片副标题；图片走缩略图用不到，其余按 MIME 顶层类型给名字 */
function labelOf(attachment: Attachment): string {
  return kindLabel(classify(attachment.mimeType) ?? 'image')
}
</script>

<template>
  <div class="flex flex-col items-end gap-2">
    <template v-for="attachment in attachments" :key="attachment.id">
      <img
        v-if="classify(attachment.mimeType) === 'image'"
        :src="resolve(attachment.url)"
        :alt="attachment.originalName"
        class="border-border h-[190px] w-[280px] rounded-2xl border object-cover"
      />

      <video
        v-else-if="classify(attachment.mimeType) === 'video'"
        :src="resolve(attachment.url)"
        class="border-border aspect-video w-[280px] rounded-2xl border bg-black"
        controls
        preload="metadata"
      />

      <!-- 文本附件走这里：显示成卡片而不是预览，因为「预览一个 txt」的实质就是打开它读，
           而消息里那点空间读不了正文——它的内容已经进了向量库，提问时会被检索出来。
           同时兼作兜底：日后加进来的类型没配渲染分支时，至少还有个能认出是什么的卡片 -->
      <div
        v-else
        class="border-border bg-background flex h-14 w-[280px] items-center gap-2.5 rounded-xl border p-2"
      >
        <div
          class="flex size-10 shrink-0 items-center justify-center rounded-[10px]"
          :class="CARD_ACCENT[cardKind(attachment.mimeType)].tile"
        >
          <component
            :is="CARD_ICON[cardKind(attachment.mimeType)]"
            class="size-5"
            :class="CARD_ACCENT[cardKind(attachment.mimeType)].icon"
          />
        </div>
        <div class="flex min-w-0 flex-col gap-0.5">
          <span class="text-foreground truncate text-[13px] font-medium">
            {{ attachment.originalName }}
          </span>
          <span class="text-muted-foreground text-[11.5px]">{{ labelOf(attachment) }}</span>
        </div>
      </div>
    </template>
  </div>
</template>
