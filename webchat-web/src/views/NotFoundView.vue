<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { House } from '@lucide/vue'

import { Button } from '@/components/ui/button'
</script>

<template>
  <!-- 独立整页：设计稿里它是一张通栏白页，不套聊天页那层壳（侧边栏、输入框） -->
  <div class="bg-background flex h-full flex-col items-center justify-center">
    <div class="flex flex-col items-center gap-[18px]">
      <p class="text-foreground text-[120px] leading-none font-bold tracking-[-2px]">404</p>

      <!-- 这一段自带 20px 下内边距，与上面的 18px 间距叠出按钮上方 38px 的留白 -->
      <div class="flex flex-col items-center gap-[14px] pb-5">
        <p class="text-foreground text-4xl leading-[1.3] font-semibold">页面不存在</p>
        <p class="text-muted-foreground text-base leading-[1.6]">
          抱歉，你访问的页面不存在或已被移除。
        </p>
      </div>

      <!-- 48px 高配 24px 圆角是一枚整圆胶囊；变体 default 的配色（--primary / --primary-foreground）
           与设计稿的 #18181B / #FAFAFA 是同一组。
           左右内边距必须带上 has-[>svg] 前缀：尺寸变体里有一条同前缀的 px-3（给带图标的按钮收窄），
           裸写 px-[26px] 压不住它，实测会变成 12px -->
      <Button as-child class="h-12 gap-2 rounded-full has-[>svg]:px-[26px] text-[15px]">
        <RouterLink to="/">
          <House class="size-[18px]" />
          返回首页
        </RouterLink>
      </Button>
    </div>
  </div>
</template>
