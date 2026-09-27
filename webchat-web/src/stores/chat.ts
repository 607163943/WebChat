import { computed, reactive, ref } from 'vue'
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
import type { Attachment, ChatMessage, Conversation, MessageStatus } from '@/api/types'
import type { AttachmentKind } from '@/lib/attachments'
import { MAX_FILES_PER_MESSAGE, classify, validateFile } from '@/lib/attachments'

const SIDEBAR_KEY = 'webchat:sidebar-collapsed'

/**
 * 正在上传、还没拿到后端附件对象的文件。
 *
 * 它不是一个「附件」，只是预览行里的一格：上传成功就换成真正的 {@link Attachment}（连同缩略图或卡片），
 * 失败则连同后面还没轮到的那些一起消失，只在顶部横幅留一句话。
 */
export interface PendingUpload {
  /** 本地负数 id，与 localMessage 同一套路，只用于 v-for 的 key */
  id: number
  name: string
  kind: AttachmentKind
  /** 已交给网络栈的字节比例 0~1，到 1 之后后端还在处理（见 UploadingAttachment） */
  progress: number
}

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
  /**
   * 正在上传的文件，排在 {@link attachments} 后面一起显示。
   *
   * 与 attachments 分开是必须的：它没有 id、没有 url、没有 mimeType，拿不到后端对象之前
   * 压根构造不出一个 Attachment；硬塞进去会让「已就绪」和「还在传」两种态混成一个。
   */
  const pendingUploads = ref<PendingUpload[]>([])
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

  /**
   * 打开一个会话，把消息换成它的。
   *
   * @returns 这个会话是否可用。false 表示它不存在或不属于当前用户（后端 404）——
   *   地址里那个 id 就此作废，调用方（ChatView 的地址 watcher）据此回落到新会话；
   *   网络故障之类的失败算「可用」，保留用户当前的选择，只把错误提示出来。
   */
  async function openConversation(id: number): Promise<boolean> {
    if (currentId.value === id) {
      return true
    }
    abortStream()
    currentId.value = id
    messages.value = []
    loadingMessages.value = true
    try {
      const loaded = await listMessages(id)
      // 拉取期间用户可能已切到别的会话（含切回新会话草稿态）：这份结果过期了，
      // 丢掉——否则它会把后来那个会话的消息覆盖掉
      if (currentId.value === id) {
        messages.value = loaded
      }
      return true
    } catch (error) {
      errorMessage.value = messageOf(error)
      return !(error instanceof ApiError && error.status === 404)
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
    // 有文件还在上传时也拦住：这时发出去只会带上已传完的那几个，剩下的留在预览行，
    // 用户会以为它们一起发出去了（发送按钮此时同样是禁用的，这里再拦一道）
    if ((!text && pending.length === 0) || streaming.value || uploading.value) {
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
    const { accepted } = await runStream(
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
    // 后端会把新内容覆盖到这条回复上（不再删了重插），本地先摘掉它，避免流式期间同一条显示两遍。
    // 摘下来的要留着：这一轮一个字都没产出时后端根本没动那一行，得把它放回去，
    // 否则界面上会少一条库里确实存在的消息
    const last = messages.value[messages.value.length - 1]
    const removed = last?.role === 'assistant' ? messages.value.pop() : undefined

    const { replied } = await runStream((handlers, signal) =>
      regenerateApi(conversationId, handlers, signal),
    )

    // 生成期间用户可能已切到别的会话：那时候 messages 里装的是别人的消息，
    // 把 removed 放回去等于把上一条会话的回复插进了这一条
    if (!replied && removed && currentId.value === conversationId) {
      messages.value.push(removed)
    }
  }

  function clearError(): void {
    errorMessage.value = ''
  }

  /**
   * 文件入口（「+」按钮选择、输入框里粘贴）共用的一条路：逐个校验、上传。
   *
   * 不合规的在这里就被挡下，省掉一次没有意义的传输；后端还会用同一份白名单再拦一次，
   * 那一份才是真正的边界（这份改个请求就能绕过）。
   *
   * 收下的文件先落进 {@link pendingUploads} 占位（带进度环），传完一个才变成 attachments 里的一项。
   */
  async function addFiles(files: File[]): Promise<void> {
    if (files.length === 0) {
      return
    }
    // 生成中 / 上传中不收新文件。「+」按钮这时是置灰的，那是一种看得见的拒绝；
    // 粘贴没有这层视觉反馈，不说一声用户只会以为粘贴没生效（这个分支实际上只有粘贴能走到）
    if (streaming.value || uploading.value) {
      errorMessage.value = streaming.value
        ? '正在生成回复，等这一轮结束再添加附件'
        : '还有文件在上传，稍等一下再添加'
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
    // 选中的文件立刻全部进预览行，还没轮到的那几个停在 0%，传完一个才换成正式的回显。
    // 于是预览行里的顺序天然与选择顺序一致：已完成的在前（先传完），排队中的在后
    const queue: { file: File; item: PendingUpload }[] = []
    for (const file of candidates) {
      // 进度要写在**视图读到的那个代理**上才驱动更新，所以造出来就包一层 reactive 再推。
      // 推裸对象的话，下面改的是那份裸对象，环不会动
      const item = reactive<PendingUpload>({
        id: -Date.now() - queue.length,
        name: file.name,
        kind: classify(file.type) ?? 'image',
        progress: 0,
      })
      queue.push({ file, item })
      pendingUploads.value.push(item)
    }
    try {
      // 逐个上传而不是打包成一个请求：每个文件要各自拿到一个附件 id
      for (const { file, item } of queue) {
        const attachment = await uploadAttachment(file, currentId.value, (progress) => {
          item.progress = progress
        })
        attachments.value.push(attachment)
        pendingUploads.value = pendingUploads.value.filter((entry) => entry.id !== item.id)
      }
    } catch (error) {
      errorMessage.value = messageOf(error)
      // 循环在这里断了，队列里剩下的（含正在传的这个）一起撤掉——留着只会永远停在 0%，
      // 而它们既没传上去也不会自己重试。已经传完的那几个仍在 attachments 里，不受影响
      const dropped = new Set(queue.map(({ item }) => item.id))
      pendingUploads.value = pendingUploads.value.filter((entry) => !dropped.has(entry.id))
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
   * @returns accepted 请求是否被后端收下。false 表示它压根没到后端，本轮服务端不留任何数据，
   *   调用方据此决定要不要把为这次发送而新建的会话也撤掉。
   *   replied 本轮是否已经有一条助手回复进了 messages——完整的、以及失败或中断后留下的半截都算。
   *   「重新生成」靠它决定要不要把先前摘下来的那条旧回复放回去。
   */
  async function runStream(
    start: (handlers: ChatStreamHandlers, signal: AbortSignal) => Promise<void>,
    sentText: string | null = null,
  ): Promise<{ accepted: boolean; replied: boolean }> {
    const controller = new AbortController()
    abortController = controller
    /** 本轮属于哪个会话：半截回复只该落在它自己那个会话的列表里 */
    const streamConversationId = currentId.value
    streaming.value = true
    streamingText.value = ''
    errorMessage.value = ''
    let failed = false
    let replied = false
    /**
     * 请求是否被后端收下（收下了就意味着用户消息已落库）。
     *
     * 只有「压根没到后端」的失败才置为 false；成功与主动停止都算收下——停止是客户端
     * 单方面断开，请求早就发出去了，用户消息同样在库里。
     */
    let accepted = true
    /**
     * 本轮的累计正文。
     *
     * 刻意用局部变量而不是读 streamingText：后者是给视图用的，abortStream 会立刻清空它
     * （空状态与流式气泡都靠它判断），而「有没有半截内容」这个判据必须在清空之后依然成立。
     */
    let produced = ''

    /**
     * 把累计正文变成一条正式消息。
     *
     * 失败与中断共用这里：两种情况下后端都已经把这段内容落了库（failed / interrupted），
     * 本地跟着留下，刷新前后才一致。
     */
    function keepPartial(status: MessageStatus): void {
      // 一个字都没吐就没什么可留的，后端同样不会写这条空消息
      if (produced === '') {
        return
      }
      // 用户已经切走的那个会话：这段内容不属于眼下这个列表，库里那条等他下次打开时出现
      if (currentId.value !== streamConversationId) {
        return
      }
      messages.value.push({
        // 负数 id 与乐观插入的用户消息同一套路，只用于 v-for 的 key 与「哪条能重新生成」，
        // 真实 id 在下次拉取消息时补齐
        id: -Date.now(),
        role: 'assistant',
        content: produced,
        createTime: new Date().toISOString(),
        // 助手回复不带附件，但结构上这个字段是必填的
        attachments: [],
        status,
      })
      replied = true
    }

    try {
      await start(
        {
          onDelta: (chunk) => {
            produced += chunk
            streamingText.value += chunk
          },
          onDone: (messageId) => {
            messages.value.push({
              id: messageId,
              role: 'assistant',
              content: produced,
              createTime: new Date().toISOString(),
              // 助手回复不带附件，但结构上这个字段是必填的
              attachments: [],
              status: 'completed',
            })
            replied = true
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
      // 已经生成的半截内容不能再丢：后端在出错与断开两种收场下都把它落了库，
      // 本地丢掉就会出现「刷新一下内容自己冒出来」的错位
      if (!replied) {
        keepPartial(failed ? 'failed' : 'interrupted')
      }
      streamingText.value = ''
      if (abortController === controller) {
        abortController = null
      }
      // 生成失败时把用户刚发的内容放回输入框，省得重新敲一遍。
      // 仅限「发送消息」这条路径：手动停止不算失败（用户消息已发出，还回去会造成重复提问），
      // regenerate 也没有输入框内容可还。
      // 这段必须排在 keepPartial 之后不影响它：请求没到后端时 produced 必为空串，推不出半截消息
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
    return { accepted, replied }
  }

  /**
   * 手动停止本次生成。
   *
   * 与失败不同：用户消息已经发出并落库，所以不把内容还回输入框（否则重发会重复提问）。
   * 已经生成的部分不再丢掉——后端会把它作为 interrupted 落库，收尾时 runStream 会把它
   * 留成一条正式消息。
   */
  function stopStreaming(): void {
    abortStream()
  }

  /**
   * 中断当前流：切会话、删会话、离开页面都走这里。
   *
   * 对后端而言这与「用户点停止」是同一条路（客户端断开，都会被落库成 interrupted），
   * 区别只在本地要不要把半截留在当前列表里——那个判断在 runStream 收尾时按 currentId 做，
   * 不在这里。这里只负责断流并把流式状态收干净（isConversationEmpty 依赖 streamingText
   * 被清空，否则新建对话的空状态会闪一下）。
   */
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
    return {
      id: -Date.now(),
      role,
      content,
      createTime: new Date().toISOString(),
      attachments,
      // 本地造出来的消息都是用户发出去的，不涉及生成状态
      status: 'completed',
    }
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
    pendingUploads,
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
