<script setup lang="ts"></script>

<template>
  <!-- 等待首个片段期间显示的三点气泡 -->
  <div class="bg-muted flex h-[30px] w-[49px] items-center justify-center gap-1 rounded-lg">
    <span
      v-for="index in 3"
      :key="index"
      class="bg-muted-foreground size-[5px] animate-[typing-dot_1.2s_ease-in-out_infinite] rounded-full"
      :style="{ animationDelay: `${(index - 1) * 150}ms` }"
    />
  </div>
</template>
