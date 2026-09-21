<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowUp, Plus, Square } from '@lucide/vue'

const props = defineProps<{ streaming: boolean }>()

const emit = defineEmits<{ send: []; stop: [] }>()

const MAX_HEIGHT = 140

/**
 * 输入内容托管在 store，不在组件内部。
 * 清空与「发送失败后还原」都发生在 store 里，组件只负责展示与交互。
 */
const draft = defineModel<string>({ required: true })

const textarea = ref<HTMLTextAreaElement | null>(null)
/** 中文输入法组字期间按回车是在选字，不能当作发送 */
const composing = ref(false)

const canSend = computed(() => draft.value.trim().length > 0)

// 内容被外部改动（发送后清空、失败后还原）时也要跟着调整高度
watch(draft, () => {
  void nextTick(resize)
})

function resize(): void {
  const element = textarea.value
  if (!element) {
    return
  }
  element.style.height = 'auto'
  const contentHeight = element.scrollHeight
  element.style.height = `${Math.min(contentHeight, MAX_HEIGHT)}px`
  // 只有内容真的超过上限才让它滚动。
  // 一直开着 auto 的话，行框高度（14px × 1.6 = 22.4px）会让内容刚好比 min-h 高零点几像素，
  // 于是空着也常驻一条滚动条。
  element.style.overflowY = contentHeight > MAX_HEIGHT ? 'auto' : 'hidden'
}

function submit(): void {
  // 生成中按回车不发新消息（store 侧也会拦，这里拦是为了不改动输入框内容）
  if (!canSend.value || props.streaming) {
    return
  }
  emit('send')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey) {
    return
  }
  // isComposing 与自维护的 composing 都要看：部分浏览器在提交组字的那次回车
  // 上不会把 isComposing 置为 true，只看它就可能在用户选字时误发消息
  if (event.isComposing || composing.value) {
    return
  }
  event.preventDefault()
  submit()
}
</script>

<template>
  <!-- 定位（贴底 / 空状态上浮）交给 ChatView 的锚点，这里只负责这一组内容 -->
  <div class="flex w-full flex-col items-center gap-3.5">
    <div
      class="bg-background border-border pointer-events-auto flex w-full max-w-[720px] items-center gap-2 rounded-[26px] border px-2.5 shadow-[0_2px_8px_rgba(0,0,0,0.08)]"
    >
      <!-- 设计稿有这一颗，但附件功能还没做，所以它点是没反应的，
           title 里如实说明，别让它看起来像能用 -->
      <button
        type="button"
        class="bg-muted text-fg-secondary hover:bg-muted-active flex size-8 shrink-0 items-center justify-center rounded-full transition-colors"
        title="添加附件（暂未开放）"
      >
        <Plus class="size-4" />
      </button>

      <textarea
        ref="textarea"
        v-model="draft"
        rows="1"
        placeholder="给 WebChat 发送消息…"
        class="placeholder:text-subtle-fg max-h-[140px] min-h-[50px] flex-1 resize-none overflow-y-hidden bg-transparent py-3.5 text-[14px] leading-[1.6] outline-none"
        @input="resize"
        @keydown="onKeydown"
        @compositionstart="composing = true"
        @compositionend="composing = false"
      />

      <!-- 生成中把发送换成停止，同一个位置，不新增按钮 -->
      <button
        v-if="props.streaming"
        type="button"
        class="bg-primary text-primary-foreground hover:bg-primary/90 flex size-8 shrink-0 items-center justify-center rounded-full transition-colors"
        title="停止生成"
        @click="emit('stop')"
      >
        <Square class="size-3.5 fill-current" />
      </button>

      <button
        v-else
        type="button"
        class="flex size-8 shrink-0 items-center justify-center rounded-full transition-colors"
        :class="
          canSend
            ? 'bg-primary text-primary-foreground hover:bg-primary/90'
            : 'bg-muted text-subtle-fg cursor-not-allowed'
        "
        :disabled="!canSend"
        title="发送"
        @click="submit"
      >
        <ArrowUp class="size-4" />
      </button>
    </div>

    <p class="text-muted-foreground text-[11.5px]">
      {{ props.streaming ? '正在生成，可点击右侧按钮停止' : '内容由 AI 生成，请谨慎参考。' }}
    </p>
  </div>
</template>
