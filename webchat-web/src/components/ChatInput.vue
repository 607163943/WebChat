<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowUp, Plus, Square } from '@lucide/vue'

import AttachmentPreview from '@/components/AttachmentPreview.vue'
import UploadingAttachment from '@/components/UploadingAttachment.vue'
import type { Attachment } from '@/api/types'
import type { PendingUpload } from '@/stores/chat'
import { ACCEPT_ATTRIBUTE } from '@/lib/attachments'

const props = defineProps<{
  streaming: boolean
  /** 已上传、待随消息提交的附件 */
  attachments: Attachment[]
  /** 正在上传的文件，排在已上传的那些后面 */
  pendingUploads: PendingUpload[]
  uploading: boolean
}>()

const emit = defineEmits<{
  send: []
  stop: []
  'pick-files': [files: File[]]
  'remove-attachment': [id: number]
}>()

const MAX_HEIGHT = 140

/**
 * 输入内容托管在 store，不在组件内部。
 * 清空与「发送失败后还原」都发生在 store 里，组件只负责展示与交互。
 */
const draft = defineModel<string>({ required: true })

const textarea = ref<HTMLTextAreaElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
/** 中文输入法组字期间按回车是在选字，不能当作发送 */
const composing = ref(false)

/**
 * 只有附件、没有文字同样可以发送——传张图直接问「这是什么」是很常见的用法。
 *
 * 有文件还在上传时不算可发送：这时发出去只会带上已传完的那几个，剩下的留在预览行，
 * 用户会以为它们一起发出去了。按钮样式、disabled 与回车都看这一个判断。
 */
const canSend = computed(
  () => !props.uploading && (draft.value.trim().length > 0 || props.attachments.length > 0),
)

const pickDisabled = computed(() => props.streaming || props.uploading)

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
  // 生成中按回车不发新消息（store 侧也会拦，这里拦是为了不改动输入框内容）；
  // 上传中那一半由 canSend 兜着，同样是为了让草稿原样留在框里
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

function onFilesPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  // 必须清空 value：不清的话连续选同一个文件不会再触发 change
  input.value = ''
  if (files.length > 0) {
    emit('pick-files', files)
  }
}

/**
 * 粘贴剪贴板里的文件（截图、复制的图片或视频）。
 *
 * **只挂在 textarea 上**：paste 事件本身就只在获得焦点的可编辑元素上触发，
 * 挂这里天然就是「光标真在输入框里才生效」；挂到 document 上会连别处的粘贴一起接管。
 *
 * 两个提前返回都是为了不破坏文本粘贴——**只有真取到文件才 preventDefault**：
 * 一旦无条件拦下，普通文本粘贴就再也进不来输入框了。
 *
 * 剪贴板里同时有文字和图片时以**文字**为准（从 Excel 复制单元格、从 Word 复制图文段落
 * 都会同时带上一个位图），否则用户以为在贴表格，结果上传了一张表格截图。
 * 判据取非空 text/plain：纯截图（Win+Shift+S、右键「复制图片」）的剪贴板里没有文字条目。
 */
function onPaste(event: ClipboardEvent): void {
  const data = event.clipboardData
  if (!data) {
    return
  }
  if (data.getData('text/plain').trim().length > 0) {
    return
  }
  const files: File[] = []
  // 用 items 而不是 files：items 带 kind，能一眼排除文字条目。
  // 文字条目的 kind 是 'string'，不排除的话 text/html 这类也会被当成文件
  for (const item of data.items) {
    if (item.kind !== 'file') {
      continue
    }
    const file = item.getAsFile()
    if (file) {
      files.push(file)
    }
  }
  if (files.length === 0) {
    // 空剪贴板，或只有 text/html 没有纯文本：放行，让浏览器按默认行为处理
    return
  }
  // 拦下是为了不让浏览器再往输入框里补一段文件名之类的文本；
  // 文件能不能收下由 store 判断（生成中、超限、格式不符都在那边给提示）
  event.preventDefault()
  emit('pick-files', files)
}
</script>

<template>
  <!-- 定位（贴底 / 空状态上浮）交给 ChatView 的锚点，这里只负责这一组内容 -->
  <div class="flex w-full flex-col items-center gap-3.5">
    <div
      class="bg-background border-border pointer-events-auto flex w-full max-w-[720px] flex-col gap-2 rounded-[26px] border p-2.5 shadow-[0_2px_8px_rgba(0,0,0,0.08)]"
    >
      <!-- 附件行：设计稿里行高 56、项间 gap 8，只有这一行出现时胶囊才变高 -->
      <div
        v-if="props.attachments.length > 0 || props.pendingUploads.length > 0"
        class="flex w-full flex-wrap items-center gap-2"
      >
        <AttachmentPreview
          v-for="attachment in props.attachments"
          :key="attachment.id"
          :attachment="attachment"
          @remove="emit('remove-attachment', $event)"
        />
        <!-- 上传中的排后面：逐个上传，已完成的必然是先传完的，两段接起来就是选择顺序 -->
        <UploadingAttachment
          v-for="pending in props.pendingUploads"
          :key="pending.id"
          :pending="pending"
        />
      </div>

      <div class="flex w-full items-center gap-2">
        <input
          ref="fileInput"
          type="file"
          multiple
          class="hidden"
          :accept="ACCEPT_ATTRIBUTE"
          @change="onFilesPicked"
        />

        <button
          type="button"
          class="text-fg-secondary hover:bg-muted-hover bg-transparent flex size-10 shrink-0 items-center justify-center rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-50"
          :disabled="pickDisabled"
          title="添加图片或视频"
          @click="fileInput?.click()"
        >
          <Plus class="size-4" />
        </button>

        <textarea
          ref="textarea"
          v-model="draft"
          rows="1"
          placeholder="给 WebChat 发送消息…"
          class="placeholder:text-subtle-fg max-h-[140px] min-h-8 flex-1 resize-none overflow-y-hidden bg-transparent py-1 text-[14px] leading-[1.6] outline-none"
          @input="resize"
          @keydown="onKeydown"
          @paste="onPaste"
          @compositionstart="composing = true"
          @compositionend="composing = false"
        />

        <!-- 生成中把发送换成停止，同一个位置，不新增按钮 -->
        <button
          v-if="props.streaming"
          type="button"
          class="bg-primary text-primary-foreground hover:bg-primary/90 flex size-10 shrink-0 items-center justify-center rounded-full transition-colors"
          title="停止生成"
          @click="emit('stop')"
        >
          <Square class="size-3.5 fill-current" />
        </button>

        <button
          v-else
          type="button"
          class="flex size-10 shrink-0 items-center justify-center rounded-full transition-colors"
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
    </div>

    <p class="text-muted-foreground text-[11.5px]">
      {{ props.streaming ? '正在生成，可点击右侧按钮停止' : '内容由 AI 生成，请谨慎参考。' }}
    </p>
  </div>
</template>
