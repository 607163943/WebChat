<script setup lang="ts">
import { computed } from 'vue'

/**
 * 上传进度环。尺寸与配色取自设计稿「附件预览」帧里的两处上传中态：
 * 图片那格是 38、卡片那颗是 40，环宽都是 3；进度色 `#0EA5E9`，底环是同色的 20% 透明。
 *
 * 用 SVG 描边而不是 conic-gradient：描边长度能直接上 CSS 过渡，而小文件的上传进度
 * 常常是一步从 0 跳到 100，没有过渡就只是闪一下。
 */
const props = defineProps<{
  /** 环的外径（px） */
  size: number
  /** 0~1 */
  progress: number
}>()

/** 描边居中画在半径线上，退半个环宽，外沿才正好贴住 size */
const radius = computed(() => props.size / 2 - 1.5)

/**
 * 描边长度用 pathLength 归一成 100，于是进度就是「留下一段、藏起其余」：
 * 偏移等于周长时整条藏起（0%），等于周长的一半时留半圈。
 *
 * 起点在 3 点钟方向，负 90 度旋到 12 点钟，与设计稿的 startAngle 90 一致；
 * 正偏移是把图案往回推，所以露出来的弧是顺时针长的，也对得上负 sweep。
 */
const dashOffset = computed(() => 100 - props.progress * 100)
</script>

<template>
  <div class="relative shrink-0" :style="{ width: `${size}px`, height: `${size}px` }">
    <svg
      class="size-full -rotate-90"
      :viewBox="`0 0 ${size} ${size}`"
      fill="none"
      aria-hidden="true"
    >
      <circle :cx="size / 2" :cy="size / 2" :r="radius" stroke="#0EA5E933" stroke-width="3" />
      <circle
        :cx="size / 2"
        :cy="size / 2"
        :r="radius"
        stroke="#0EA5E9"
        stroke-width="3"
        pathLength="100"
        stroke-dasharray="100"
        :stroke-dashoffset="dashOffset"
        class="transition-[stroke-dashoffset] duration-200 ease-out motion-reduce:transition-none"
      />
    </svg>

    <!-- 图标压在环心，环里那点地方放不下别的 -->
    <div class="absolute inset-0 flex items-center justify-center">
      <slot />
    </div>
  </div>
</template>
