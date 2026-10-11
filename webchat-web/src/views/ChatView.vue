<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
 * 乐观插入消息发生在那之后，输入框会愣一个网络往返才归位。
 * 这里在收到 send 事件时同步置位，让它当场闪现回底部。
 */
const sending = ref(false)

/**
 * 是否处于「新会话」空状态。
 *
 * loadingMessages 不能省：openConversation 会先把 messages 清空再去拉取，少了它，
 * 每次切换会话都会把新会话那套问候语与输入框归位整个重放一遍。
 *
 * loadingConversations 则刻意不参与判断。它只在启动拉侧边栏列表时为 true，与「当前
 * 这个会话是不是空的」无关；算进来的话，启动那一刻（地址是 /，本来就是个新会话）会先
 * 按「非空」画一帧——输入框出现在底部，等列表回来再跳到居中，就是启动时输入框自己动了
 * 一下。第一帧就该是它最终的样子，而不是等一个跟它无关的请求。
 */
const showEmpty = computed(
  () => chat.isConversationEmpty && !chat.loadingMessages && !sending.value,
)

/**
 * 地址与「当前是哪个会话」的双向同步。
 *
 * 地址是打开会话的外部入口：刷新、手输、前进后退都从地址进来（地址 → store）；
 * 而程序自己造成的会话变化——首条消息落库后出现的 id、删除当前会话、打不开的地址回落——
 * 反过来把地址对齐（store → 地址）。两个方向各自只写自己那一侧，衔接点是 currentId。
 */
const route = useRoute()
const router = useRouter()

/** 会话 id ↔ 地址的唯一换算。草稿态（还没落库的会话）没有 id，落在根路径 */
function conversationPath(id: number | null): string {
  return id === null ? '/' : `/${id}`
}

/** 地址参数的原始文本。可重复参数会给成数组，本路由只有一个末段，取第一项即可 */
function paramText(raw: string | string[] | undefined): string | undefined {
  return Array.isArray(raw) ? raw[0] : raw
}

/**
 * 解析地址末段的会话 id；没有这一段就是「新会话」草稿态。
 *
 * 路由已把末段限定成正整数，这里的检查是第二道：超出安全整数范围的数字（20 位以上）
 * 长得符合路由、却表达不出一个 id。
 */
function parseConversationId(raw: string | string[] | undefined): number | null {
  const text = paramText(raw)
  if (text === undefined || !/^\d+$/.test(text)) {
    return null
  }
  const value = Number(text)
  return Number.isSafeInteger(value) && value > 0 ? value : null
}

/**
 * 地址 → 状态。
 *
 * immediate 让刷新页面走的是同一条路：地址里带着 id 就直接去开那个会话，
 * 而不是先渲染一个新会话界面再跳过去（那会闪一下空状态，白跑一次动画）。
 */
watch(
  () => route.params.id,
  async (raw) => {
    const id = parseConversationId(raw)
    if (id === null) {
      // 没有末段就是「新会话」草稿态；有末段却解析不出 id 的（20 位以上的数字），
      // 路由认下了它、它却不是一个会话 id，按「打不开」收场
      chat.startConversation()
      const text = paramText(raw)
      if (text !== undefined) {
        chat.reportMissingConversation(text)
        // 地址得自己收回。上面那句置的是 currentId，而它本来就是 null 时（刷新页面、
        // 刚从别处回落到这里）不构成变化，不会有人替我们把地址改回去
        await router.replace('/')
      }
      return
    }
    if (!(await chat.openConversation(id))) {
      // 会话打不开（已删除、不属于当前用户）：回到「新会话」草稿态并说明是哪个 id 打不开，
      // 地址由下面那个 watcher 顺带收回 `/`。
      //
      // 这一步刻意停在聊天页里做，而不是把整页交给 404：会话存不存在要问过后端才知道，
      // 而那时聊天页早就画完了——换成 404 等于让用户看着主界面闪一下再被整页换掉。
      //
      // 拉取期间用户可能已从侧边栏点了别的会话：那就别再动人家的选择，提醒也一并免了
      if (route.params.id === raw) {
        chat.startConversation()
        chat.reportMissingConversation(id)
      }
    }
  },
  { immediate: true },
)

/**
 * 状态 → 地址。
 *
 * 这里出现的地址变化都是程序的副产物（会话落库、删除、回落），一律 replace，
 * 不往历史里塞记录——历史只记用户点出来的会话（见 onSelect）。
 */
watch(
  () => chat.currentId,
  (id) => {
    const path = conversationPath(id)
    if (route.path !== path) {
      void router.replace(path)
    }
  },
)

/** 选中侧边栏里的会话：走地址，真正的打开由上面的 watcher 做——地址是唯一入口 */
function onSelect(id: number): void {
  void router.push(conversationPath(id))
}

function onCreate(): void {
  void router.push('/')
}

const composer = ref<HTMLElement | null>(null)

/**
 * 输入框组的实测高度，写成 CSS 变量供下面两处使用。
 *
 * 原来是两个写死的常量：锚点位移里的 90.3 与消息列表的 pb-[110px]，它们都把
 * 「输入框组 83.25px」算了进去。带上附件后胶囊会从 52 涨到 116，两个常量同时失效。
 * 更要命的是这两处要的还不是同一个量——一个含问候语、一个不含，共用一个数字必然错一处。
 * 所以这里只量「输入框组自己的高度」，两处各自推导。
 *
 * 量的是锚点而不是 ChatInput：问候语是 absolute bottom-full，不在锚点的盒子里，
 * 所以锚点的 offsetHeight 恰好就是输入框组的高度。
 */
const chatInputHeight = ref(83)
let resizeObserver: ResizeObserver | null = null

function observeComposer(): void {
  const element = composer.value
  if (!element) {
    return
  }
  resizeObserver = new ResizeObserver(() => {
    chatInputHeight.value = element.offsetHeight
  })
  resizeObserver.observe(element)
  chatInputHeight.value = element.offsetHeight
}

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
  observeComposer()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  // 页面离开时中断流式请求：后端会因此判定为「客户端断开」，并把已生成的部分落库为 interrupted
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
      @select="onSelect"
      @create="onCreate"
      @remove="chat.removeConversation"
      @toggle="chat.toggleSidebar"
    />

    <main
      class="@container-size relative flex min-w-0 flex-1 flex-col"
      :style="{ '--chat-input-h': `${chatInputHeight}px` }"
    >
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

      <!-- 消息列表比输入框晚 120ms 出场：输入框先就位，列表再淡入跟上，读起来像一次编排 -->
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
          :searching-label="chat.searchingLabel"
          @regenerate="chat.regenerateLast"
        />
      </Transition>

      <!-- 输入区锚点：始终贴底，空状态时整组上浮到垂直居中（见下方位移公式）。
           上浮与归位刻意不做过渡：提交时输入框直接闪现到位置，滑动只会让每次发送
           都慢半拍；附件改变高度时的重新居中同理，瞬间生效反而更跟手。
           pointer-events-none 是必需的——锚点是通栏的，会挡住左右两侧列表的点击 -->
      <div
        ref="composer"
        class="pointer-events-none absolute inset-x-0 bottom-5 flex flex-col items-center px-4"
        :style="{
          transform: showEmpty
            ? 'translateY(calc((var(--chat-input-h) + 57.6px) / 2 + 20px - 50cqh))'
            : 'translateY(0)',
        }"
      >
        <!-- 问候语不参与布局流，靠绝对定位挂在胶囊上方跟着一起走。
             若放进流里，它出现或消失时会把胶囊顶上 57.6px，位移变成两段跳。
             淡出也一并去掉：锚点改走闪现后，留着它只会在输入框已经落到底部时
             还在原地留 150ms 的残影。

             位移公式：锚点贴底时其底边在 H-20，空状态下整组垂直居中
             （问候语 33.6 + mb-6 24 + 输入框组实测高度）时底边在 H/2 + 组高/2，
             两者相减即上面那个表达式。组高是量出来的，输入框带上附件后会自动跟着变。 -->
        <p
          class="text-foreground pointer-events-none absolute bottom-full left-1/2 mb-6 -translate-x-1/2 text-2xl leading-[1.4] font-semibold whitespace-nowrap"
          :class="showEmpty ? 'opacity-100' : 'opacity-0'"
        >
          有什么可以帮你的吗？
        </p>

        <ChatInput
          v-model="chat.draft"
          :streaming="chat.streaming"
          :attachments="chat.attachments"
          :pending-uploads="chat.pendingUploads"
          :uploading="chat.uploading"
          @send="onSend"
          @stop="chat.stopStreaming"
          @pick-files="chat.addFiles"
          @remove-attachment="chat.removeAttachment"
        />
      </div>
    </main>
  </div>
</template>
