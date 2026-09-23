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
import { deleteAttachment as deleteAttachmentApi, uploadAttachment } from '@/api/attachments'
import { ApiError } from '@/api/http'
import type { Attachment, ChatMessage, Conversation } from '@/api/types'
import { MAX_FILES_PER_MESSAGE, validateFile } from '@/lib/attachments'

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
  /**
   * 已上传、还没随消息提交的附件（库里 message_id 为空的那个中间态）。
   *
   * 与 draft 一样托管在 store：切会话**不清空**它们（跟草稿的行为保持一致——草稿也不会因切会话而丢），
   * 发送成功才清空。发送失败**不还原**——文件已经在服务端了，退回去还得重传一遍。
   */
  const attachments = ref<Attachment[]>([])
  /** 有文件正在上传。期间不允许再选，避免并发上传把顺序打乱 */
  const uploading = ref(false)
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

  /**
   * 开一个新对话：只把界面切到空白，**不建后端会话**。
   *
   * 会话在用户发出首条消息时才落库（见 send），所以点一下「新建对话」再刷新页面，
   * 库里不会多出一条点开是空的会话。做法是把 currentId 置空——null 就是这个
   * 「已开好、还没落库」的草稿态，send 里本来就有「没有会话就先建一个」的分支。
   */
  function startConversation(): void {
    abortStream()
    currentId.value = null
    messages.value = []
  }

  /**
   * 确保有一个可用的会话，返回其 id；创建失败返回 null。
   *
   * 这是会话落库的唯一入口，调用点只有 send：会话行和它的首条消息是同一次点击产生的。
   */
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

  /**
   * 撤掉一个刚建出来、消息却没发出去的会话。
   *
   * 会话行是为了发那条消息才插的，消息既然没落库就不该留它，否则刷新后侧边栏会多出一条
   * 点开是空的会话。
   *
   * 与 removeConversation 的分工：这里是静默回滚，不报错、也不自动切到别的会话——
   * 撤完要回到草稿态，好让用户重发时重新建一个。
   */
  async function discardConversation(id: number): Promise<void> {
    try {
      await deleteConversationApi(id)
      conversations.value = conversations.value.filter((item) => item.id !== id)
      if (currentId.value === id) {
        currentId.value = null
      }
    } catch {
      // 删不掉就让它留着：本地列表刚按服务端刷新过，下次打开还看得到它，
      // 不值得为掩盖一条空会话再造一层状态
    }
  }

  async function removeConversation(id: number): Promise<void> {
    try {
      await deleteConversationApi(id)
      conversations.value = conversations.value.filter((item) => item.id !== id)
      if (currentId.value !== id) {
        return
      }
      // 删掉的正是当前会话，本轮生成随之作废，必须先断流：否则它会照常跑完，
      // done 把回复塞进已被清空的 messages，后端还会往一个已删除的会话里写消息。
      abortStream()
      // 自动回到新会话的草稿态，方便用户继续提问
      currentId.value = null
      messages.value = []
    } catch (error) {
      errorMessage.value = messageOf(error)
    }
  }

  /**
   * 发送输入框里的内容。
   *
   * 会话落库的唯一时机就在这里：没有会话就先建一个再发，所以「开好新对话但一直没发消息」
   * 不会在库里留下任何东西（见 startConversation）。
   */
  async function send(): Promise<void> {
    const text = draft.value.trim()
    const pending = [...attachments.value]
    // 只有附件、没有文字也允许发送——传张图直接问「这是什么」很正常
    if ((!text && pending.length === 0) || streaming.value) {
      return
    }
    let id = currentId.value
    const createdNow = id === null
    if (createdNow) {
      id = await ensureConversation()
    }
    if (id === null) {
      return
    }
    const conversationId = id
    // 先清空输入框；这一步失败时会由 runStream 还原回来
    draft.value = ''
    // 后端在收到消息时就会落库，本地先乐观插入，省掉一次往返。
    // 附件也要跟着插进去，否则「只发附件」的那条在发送瞬间就是个空气泡
    messages.value.push(localMessage('user', text, pending))
    const accepted = await runStream(
      (handlers, signal) =>
        sendMessageApi(
          conversationId,
          text,
          pending.map((item) => item.id),
          handlers,
          signal,
        ),
      text,
    )
    if (accepted) {
      // 附件已经绑到那条消息上了，输入框里的预览该收走
      attachments.value = []
    }
    if (createdNow && !accepted) {
      // 消息没发出去，这个会话就是白建的，一并撤掉（乐观插入的那条消息已由 runStream 撤回）
      await discardConversation(conversationId)
    }
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
    await runStream((handlers, signal) => regenerateApi(conversationId, handlers, signal))
  }

  function clearError(): void {
    errorMessage.value = ''
  }

  /**
   * 选中文件后逐个校验、上传。
   *
   * 不合规的在这里就被挡下，省掉一次没有意义的传输；后端还会用同一份白名单再拦一次，
   * 那一份才是真正的边界（这份改个请求就能绕过）。
   */
  async function addFiles(files: File[]): Promise<void> {
    if (files.length === 0 || streaming.value || uploading.value) {
      return
    }
    const problems: string[] = []
    const candidates: File[] = []
    for (const file of files) {
      const problem = validateFile(file)
      if (problem) {
        problems.push(problem)
      } else {
        candidates.push(file)
      }
    }
    const room = MAX_FILES_PER_MESSAGE - attachments.value.length
    if (candidates.length > room) {
      problems.push(
        room <= 0
          ? `一条消息最多带 ${MAX_FILES_PER_MESSAGE} 个附件`
          : `一条消息最多带 ${MAX_FILES_PER_MESSAGE} 个附件，本次只收下前 ${room} 个`,
      )
      candidates.length = Math.max(room, 0)
    }
    // 错误横幅只有一个槽位，先报最先遇到的那个问题
    errorMessage.value = problems[0] ?? ''
    if (candidates.length === 0) {
      return
    }

    uploading.value = true
    try {
      // 逐个上传而不是打包成一个请求：每个文件要各自拿到一个附件 id
      for (const file of candidates) {
        attachments.value.push(await uploadAttachment(file, currentId.value))
      }
    } catch (error) {
      errorMessage.value = messageOf(error)
    } finally {
      uploading.value = false
    }
  }

  /** 移除一个待发送的附件。先本地移除再调接口，失败就放回原位 */
  async function removeAttachment(id: number): Promise<void> {
    const index = attachments.value.findIndex((item) => item.id === id)
    const removed = attachments.value[index]
    if (!removed) {
      return
    }
    attachments.value.splice(index, 1)
    try {
      await deleteAttachmentApi(id)
    } catch (error) {
      attachments.value.splice(index, 0, removed)
      errorMessage.value = messageOf(error)
    }
  }

  /**
   * 跑一轮流式请求。
   *
   * @returns 请求是否被后端收下。false 表示它压根没到后端，本轮服务端不留任何数据，
   *   调用方据此决定要不要把为这次发送而新建的会话也撤掉。
   */
  async function runStream(
    start: (handlers: ChatStreamHandlers, signal: AbortSignal) => Promise<void>,
    sentText: string | null = null,
  ): Promise<boolean> {
    const controller = new AbortController()
    abortController = controller
    streaming.value = true
    streamingText.value = ''
    errorMessage.value = ''
    let failed = false
    /**
     * 请求是否被后端收下（收下了就意味着用户消息已落库）。
     *
     * 只有「压根没到后端」的失败才置为 false；成功与主动停止都算收下——停止是客户端
     * 单方面断开，请求早就发出去了，用户消息同样在库里。
     */
    let accepted = true

    try {
      await start(
        {
          onDelta: (chunk) => {
            streamingText.value += chunk
          },
          onDone: (messageId) => {
            messages.value.push({
              id: messageId,
              role: 'assistant',
              content: streamingText.value,
              createTime: new Date().toISOString(),
              // 助手回复不带附件，但结构上这个字段是必填的
              attachments: [],
            })
            streamingText.value = ''
          },
          onTitle: (title, conversationId) => {
            // 立即更新侧边栏；随后那次列表刷新会再对齐一次权威值。
            // 按事件给的会话 id 定位，而不是 currentId——事件讲的是哪个会话就改哪一行
            const target = conversations.value.find((item) => item.id === conversationId)
            if (target) {
              target.title = title
            }
          },
          onError: (message, streamStarted) => {
            failed = true
            accepted = streamStarted
            errorMessage.value = message
          },
        },
        controller.signal,
      )
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
        if (!accepted) {
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
    return accepted
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

  function localMessage(
    role: ChatMessage['role'],
    content: string,
    attachments: Attachment[] = [],
  ): ChatMessage {
    // 负数 ID 只用于 v-for 的 key，真实 ID 在下次拉取会话消息时补齐
    return { id: -Date.now(), role, content, createTime: new Date().toISOString(), attachments }
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
    attachments,
    uploading,
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
    addFiles,
    removeAttachment,
    toggleSidebar,
    abortStream,
  }
})

function messageOf(error: unknown): string {
  return error instanceof ApiError ? error.message : '操作失败，请重试'
}
