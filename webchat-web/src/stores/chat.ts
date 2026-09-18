import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { regenerate as regenerateApi, sendMessage as sendMessageApi } from '@/api/chat'
import type { ChatStreamHandlers } from '@/api/chat'
import {
  createConversation as createConversationApi,
  deleteConversation as deleteConversationApi,
  listConversations,
  listMessages,
} from '@/api/conversations'
import { ApiError } from '@/api/http'
import type { ChatMessage, Conversation } from '@/api/types'

const SIDEBAR_KEY = 'webchat:sidebar-collapsed'

/** 读侧边栏的收拢状态。localStorage 可能被禁用（隐私模式等），一律静默兜底。 */
function readStoredFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

function writeStoredFlag(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, value ? '1' : '0')
  } catch {
    // 存不下就算了，下次打开回到默认展开
  }
}

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const currentId = ref<number | null>(null)
  const messages = ref<ChatMessage[]>([])
  /** 正在流式接收、尚未成为正式消息的助手回复 */
  const streamingText = ref('')
  const streaming = ref(false)
  const loadingConversations = ref(false)
  const loadingMessages = ref(false)
  const errorMessage = ref('')
  /**
   * 输入框内容托管在 store 里，而不是留在组件内部。
   *
   * 这样「发送失败后把内容还回输入框」才能成立——失败发生在 store 里，
   * 组件不该为了接这个结果再造一套回调。注意目前只托管文本：
   * 日后支持附件时，附件不进这里，失败也不还原。
   */
  const draft = ref('')
  /** 侧边栏是否收拢 */
  const sidebarCollapsed = ref(readStoredFlag(SIDEBAR_KEY))

  let abortController: AbortController | null = null

  const currentConversation = computed(
    () => conversations.value.find((item) => item.id === currentId.value) ?? null,
  )

  /** 当前会话没有任何内容——用于显示空状态 */
  const isConversationEmpty = computed(
    () => messages.value.length === 0 && !streaming.value && streamingText.value === '',
  )

  async function initialize(): Promise<void> {
    await loadConversations()
    const first = conversations.value[0]
    if (first) {
      await openConversation(first.id)
    }
  }

  async function loadConversations(): Promise<void> {
    loadingConversations.value = true
    try {
      conversations.value = await listConversations()
    } catch (error) {
      errorMessage.value = messageOf(error)
    } finally {
      loadingConversations.value = false
    }
  }

  async function openConversation(id: number): Promise<void> {
    if (currentId.value === id) {
      return
    }
    abortStream()
    currentId.value = id
    messages.value = []
    loadingMessages.value = true
    try {
      messages.value = await listMessages(id)
    } catch (error) {
      errorMessage.value = messageOf(error)
    } finally {
      loadingMessages.value = false
    }
  }

  async function startConversation(): Promise<void> {
    abortStream()
    await ensureConversation()
  }

  /** 确保有一个可用的会话，返回其 id；创建失败返回 null */
  async function ensureConversation(): Promise<number | null> {
    try {
      const created = await createConversationApi()
      conversations.value = [created, ...conversations.value]
      currentId.value = created.id
      messages.value = []
      return created.id
    } catch (error) {
      errorMessage.value = messageOf(error)
      return null
    }
  }

  async function removeConversation(id: number): Promise<void> {
    try {
      await deleteConversationApi(id)
      conversations.value = conversations.value.filter((item) => item.id !== id)
      if (currentId.value !== id) {
        return
      }
      // 删掉的是当前会话，自动切到剩余的第一个
      const next = conversations.value[0]
      if (next) {
        currentId.value = null
        await openConversation(next.id)
      } else {
        currentId.value = null
        messages.value = []
      }
    } catch (error) {
      errorMessage.value = messageOf(error)
    }
  }

  async function send(): Promise<void> {
    const text = draft.value.trim()
    if (!text || streaming.value) {
      return
    }
    // 还没有会话时直接开一个，省掉「先点新建再发消息」这一步
    let id = currentId.value
    if (id === null) {
      id = await ensureConversation()
    }
    if (id === null) {
      return
    }
    const conversationId = id
    // 先清空输入框；这一步失败时会由 runStream 还原回来
    draft.value = ''
    // 后端在收到消息时就会落库，本地先乐观插入，省掉一次往返
    messages.value.push(localMessage('user', text))
    await runStream((handlers) => sendMessageApi(conversationId, text, handlers), text)
  }

  async function regenerateLast(): Promise<void> {
    const conversationId = currentId.value
    if (conversationId === null || streaming.value) {
      return
    }
    // 后端会删掉最后一条助手回复，本地同步移除，避免流式期间同一条显示两遍
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'assistant') {
      messages.value.pop()
    }
    await runStream((handlers) => regenerateApi(conversationId, handlers))
  }

  function clearError(): void {
    errorMessage.value = ''
  }

  async function runStream(
    start: (handlers: ChatStreamHandlers) => Promise<void>,
    sentText: string | null = null,
  ): Promise<void> {
    const controller = new AbortController()
    abortController = controller
    streaming.value = true
    streamingText.value = ''
    errorMessage.value = ''
    let failed = false
    /** 后端是否已经接受了这次请求（接受了就意味着用户消息已落库） */
    let requestAccepted = false

    try {
      await start({
        onDelta: (chunk) => {
          streamingText.value += chunk
        },
        onDone: (messageId) => {
          messages.value.push({
            id: messageId,
            role: 'assistant',
            content: streamingText.value,
            createTime: new Date().toISOString(),
          })
          streamingText.value = ''
        },
        onTitle: (title) => {
          // 立即更新侧边栏；随后那次列表刷新会再对齐一次权威值
          const target = conversations.value.find((item) => item.id === currentId.value)
          if (target) {
            target.title = title
          }
        },
        onError: (message, streamStarted) => {
          failed = true
          requestAccepted = streamStarted
          errorMessage.value = message
        },
      })
    } finally {
      streaming.value = false
      // 失败时丢弃半截内容——后端同样不会落库，留着会让刷新后前后不一致
      streamingText.value = ''
      if (abortController === controller) {
        abortController = null
      }
      // 生成失败时把用户刚发的内容放回输入框，省得重新敲一遍。
      // 仅限「发送消息」这条路径：手动停止不算失败（用户消息已发出，还回去会造成重复提问），
      // regenerate 也没有输入框内容可还。
      if (failed && sentText !== null) {
        if (!requestAccepted) {
          // 请求压根没到后端，刚才乐观插入的那条用户消息并不存在，必须撤掉，
          // 否则用户拿着还原出来的内容重发一次，界面上就会出现两条一样的提问
          const last = messages.value[messages.value.length - 1]
          if (last?.role === 'user' && last.content === sentText) {
            messages.value.pop()
          }
        }
        // 只在输入框为空时还原，避免覆盖用户在这期间新输入的内容
        if (draft.value === '') {
          draft.value = sentText
        }
      }
      await refreshConversations()
    }
  }

  /**
   * 手动停止本次生成。
   *
   * 与失败不同：用户消息已经发出并落库，所以不把内容还回输入框（否则重发会重复提问）。
   * 半截回复同样丢弃——后端不会落库，留着刷新后就没了。
   */
  function stopStreaming(): void {
    abortStream()
  }

  function abortStream(): void {
    abortController?.abort()
    abortController = null
    streaming.value = false
    streamingText.value = ''
  }

  function toggleSidebar(): void {
    sidebarCollapsed.value = !sidebarCollapsed.value
    writeStoredFlag(SIDEBAR_KEY, sidebarCollapsed.value)
  }

  /** 本轮回复改变了会话的最后活跃时间（可能还顺带改了标题），重新拉一次列表对齐顺序 */
  async function refreshConversations(): Promise<void> {
    try {
      conversations.value = await listConversations()
    } catch {
      // 刷新列表失败不影响本轮对话，静默忽略
    }
  }

  function localMessage(role: ChatMessage['role'], content: string): ChatMessage {
    // 负数 ID 只用于 v-for 的 key，真实 ID 在下次拉取会话消息时补齐
    return { id: -Date.now(), role, content, createTime: new Date().toISOString() }
  }

  return {
    conversations,
    currentId,
    messages,
    streamingText,
    streaming,
    loadingConversations,
    loadingMessages,
    errorMessage,
    draft,
    sidebarCollapsed,
    currentConversation,
    isConversationEmpty,
    initialize,
    loadConversations,
    openConversation,
    startConversation,
    removeConversation,
    send,
    stopStreaming,
    regenerateLast,
    clearError,
    toggleSidebar,
    abortStream,
  }
})

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : '操作失败，请重试'
}
