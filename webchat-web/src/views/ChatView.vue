<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { PanelLeft, X } from '@lucide/vue'

import ChatInput from '@/components/ChatInput.vue'
import ConversationSidebar from '@/components/ConversationSidebar.vue'
import MessageList from '@/components/MessageList.vue'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()

/**
 * 从按下回车到本轮回复结束之间为 true。
 *
 * 判断空状态只看 messages 会慢半拍：send() 在还没有会话时要先 await 建会话接口，
 * 乐观插入消息发生在那之后，输入框会愣一个网络往返才开始位移。
 * 这里在收到 send 事件时同步置位，让过渡立刻起步。
 */
const sending = ref(false)

/**
 * 是否处于「新会话」空状态。
 *
 * loadingMessages / loadingConversations 两个条件不能省：openConversation 会先把
 * messages 清空再去拉取，少了它们，每次切换会话都会把新会话那套问候语与输入框
 * 位移的动画整个重放一遍。
 */
const showEmpty = computed(
  () =>
    chat.isConversationEmpty &&
    !chat.loadingMessages &&
    !chat.loadingConversations &&
    !sending.value,
)

async function onSend(): Promise<void> {
  sending.value = true
  try {
    await chat.send()
  } finally {
    // 请求没到后端时 store 会撤回乐观插入的消息并还原草稿，这里跟着落回空状态
    sending.value = false
  }
}

onMounted(() => {
  void chat.initialize()
})

onBeforeUnmount(() => {
  // 页面离开时中断流式请求：后端会因此判定为「客户端断开」，本次回复不落库
  chat.abortStream()
})
</script>

<template>
  <!-- @container-size 是为了让输入区能用 cqh 表达位移：空状态需要把输入框
       抬到垂直居中，抬升距离取决于主区域高度，用容器查询单位就不用 JS 去量 -->
  <div class="flex h-full overflow-hidden">
    <ConversationSidebar
      :conversations="chat.conversations"
      :current-id="chat.currentId"
      :loading="chat.loadingConversations"
      :collapsed="chat.sidebarCollapsed"
      @select="chat.openConversation"
      @create="chat.startConversation"
      @remove="chat.removeConversation"
      @toggle="chat.toggleSidebar"
    />

    <main class="@container-size relative flex min-w-0 flex-1 flex-col">
      <!-- 侧边栏收拢后的展开入口。悬浮在左上角，所以下方内容要给它让位 -->
      <button
        v-if="chat.sidebarCollapsed"
        type="button"
        class="bg-background/80 border-border text-muted-foreground hover:text-foreground absolute top-3 left-3 z-10 flex size-8 items-center justify-center rounded-lg border backdrop-blur transition-colors"
        title="展开侧边栏"
        @click="chat.toggleSidebar"
      >
        <PanelLeft class="size-4" />
      </button>

      <div
        v-if="chat.errorMessage"
        class="border-destructive/30 bg-destructive/5 text-destructive mt-4 mr-4 flex items-start gap-2 rounded-lg border px-3 py-2 text-[13px]"
        :class="chat.sidebarCollapsed ? 'ml-14' : 'ml-4'"
      >
        <span class="min-w-0 flex-1 wrap-break-word">{{ chat.errorMessage }}</span>
        <button
          type="button"
          class="hover:bg-destructive/10 -mr-1 shrink-0 rounded p-0.5 transition-colors"
          title="关闭"
          @click="chat.clearError"
        >
          <X class="size-3.5" />
        </button>
      </div>

      <!-- 消息列表比输入框晚 120ms 出场：输入框先动，列表再淡入跟上，读起来像一次编排 -->
      <Transition
        enter-active-class="transition duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] delay-[120ms] motion-reduce:transition-none"
        enter-from-class="translate-y-2.5 opacity-0"
        enter-to-class="translate-y-0 opacity-100"
        leave-active-class="transition-opacity duration-150 motion-reduce:transition-none"
        leave-from-class="opacity-100"
        leave-to-class="opacity-0"
      >
        <MessageList
          v-if="!showEmpty"
          :messages="chat.messages"
          :streaming-text="chat.streamingText"
          :streaming="chat.streaming"
          @regenerate="chat.regenerateLast"
        />
      </Transition>

      <!-- 输入区锚点：始终贴底，空状态时整组上浮到垂直居中（见下方位移公式）。
           pointer-events-none 是必需的——锚点是通栏的，会挡住左右两侧列表的点击 -->
      <div
        class="pointer-events-none absolute inset-x-0 bottom-5 flex flex-col items-center px-4 transition-transform duration-450 ease-[cubic-bezier(0.22,1,0.36,1)] motion-reduce:transition-none"
        :class="showEmpty ? 'translate-y-[calc(90.3px-50cqh)]' : 'translate-y-0'"
      >
        <!-- 问候语不参与布局流，靠绝对定位挂在胶囊上方跟着一起走。
             若放进流里，它淡出时会把胶囊顶上 57.6px，位移变成两段跳。

             位移公式：锚点贴底时其底边在 H-20，空状态下整组垂直居中
             （问候语 33.6 + gap 24 + 输入框组 83.25 = 140.85）时底边在 H/2 + 70.4，
             两者相减得 50cqh - 90.3 的下移量，取负号即上浮。 -->
        <p
          class="text-foreground pointer-events-none absolute bottom-full left-1/2 mb-6 -translate-x-1/2 text-2xl leading-[1.4] font-semibold whitespace-nowrap transition-opacity duration-150 motion-reduce:transition-none"
          :class="showEmpty ? 'opacity-100' : 'opacity-0'"
        >
          有什么可以帮你的吗？
        </p>

        <ChatInput
          v-model="chat.draft"
          :streaming="chat.streaming"
          @send="onSend"
          @stop="chat.stopStreaming"
        />
      </div>
    </main>
  </div>
</template>
