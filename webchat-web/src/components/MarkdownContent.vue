<script setup lang="ts">
import { computed } from 'vue'

import { renderMarkdown } from '@/lib/markdown'

const props = defineProps<{ content: string }>()

/**
 * 这里可以放心用 v-html：markdown-it 关掉了 html 解析，代码块由 Shiki 生成，
 * 两者产出的都是已转义的内容。
 */
const html = computed(() => renderMarkdown(props.content))
</script>

<template>
  <div class="markdown-body" v-html="html" />
</template>
