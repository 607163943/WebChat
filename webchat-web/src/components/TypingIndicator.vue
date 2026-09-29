<script setup lang="ts">
import { Search } from '@lucide/vue'

/**
 * 等待期间的气泡，两种形态：
 *
 * - 不带 label：三点跳动，表示「模型在想」（等待首个片段）
 * - 带 label：放大镜 + 一句话，表示「模型在跑工具」——联网搜索时要几秒，
 *   这几秒里模型一个字都不吐，不说一声与卡死没有区别
 *
 * 两种形态共用一个组件：它们出现的位置、尺寸、消失时机完全一样，
 * 拆成两个组件只会让「什么时候显示哪个」散到调用方去。
 */
defineProps<{
  /** 显示在气泡右侧的说明，如「正在联网搜索…」；不传就是单纯的三点 */
  label?: string
}>()
</script>

<template>
  <div class="flex h-[30px] items-center gap-2.5">
    <div
      class="bg-muted flex h-[30px] items-center justify-center gap-1 rounded-lg"
      :class="label ? 'px-3' : 'w-[49px]'"
    >
      <Search v-if="label" class="text-muted-foreground size-3.5 animate-pulse" />
      <template v-else>
        <span
          v-for="index in 3"
          :key="index"
          class="bg-muted-foreground size-[5px] animate-[typing-dot_1.2s_ease-in-out_infinite] rounded-full"
          :style="{ animationDelay: `${(index - 1) * 150}ms` }"
        />
      </template>
    </div>
    <span v-if="label" class="text-muted-foreground text-[13px]">{{ label }}</span>
  </div>
</template>
