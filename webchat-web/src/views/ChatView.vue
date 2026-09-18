<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { MessageSquare, PanelLeft, X } from '@lucide/vue'

import ChatInput from '@/components/ChatInput.vue'
import ConversationSidebar from '@/components/ConversationSidebar.vue'
import MessageList from '@/components/MessageList.vue'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()

onMounted(() => {
  void chat.initialize()
})

onBeforeUnmount(() => {
  // 页面离开时中断流式请求：后端会因此判定为「客户端断开」，本次回复不落库
  chat.abortStream()
})
</script>

<template>
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

    <main class="relative flex min-w-0 flex-1 flex-col">
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

      <MessageList
        v-if="chat.messages.length || chat.streaming"
        :messages="chat.messages"
        :streaming-text="chat.streamingText"
        :streaming="chat.streaming"
        @regenerate="chat.regenerateLast"
      />

      <div v-else class="flex flex-1 flex-col items-center justify-center gap-3 px-6 text-center">
        <div class="bg-primary flex size-11 items-center justify-center rounded-xl">
          <MessageSquare class="text-primary-foreground size-5" />
        </div>
        <p class="text-[15px] font-medium">开始和 WebChat 助手对话吧</p>
        <p class="text-muted-foreground max-w-[320px] text-[13px] leading-relaxed">
          在下方输入你的问题，回复会以流式逐字返回；会话会自动保存，刷新页面也不会丢。
        </p>
      </div>

      <ChatInput
        v-model="chat.draft"
        :streaming="chat.streaming"
        @send="chat.send"
        @stop="chat.stopStreaming"
      />
    </main>
  </div>
</template>
