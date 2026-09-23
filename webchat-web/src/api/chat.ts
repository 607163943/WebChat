import { API_BASE_URL } from './http'
import { streamSse } from './sse'

/**
 * 聊天流式接口的事件回调。
 *
 * 事件协议（手写约定，生成不出类型）：若干 delta → done →（仅新会话）title；失败则以 error 结束。
 */
export interface ChatStreamHandlers {
  /** 增量片段，追加到当前助手气泡末尾 */
  onDelta: (content: string) => void
  /** 生成结束，messageId 是落库后的助手消息 ID */
  onDone: (messageId: number) => void
  /**
   * 新会话首条消息生成标题后的一次性事件。
   *
   * @param conversationId 这条标题属于哪个会话。事件自带主语，调用方不用拿「当前会话」去猜
   */
  onTitle: (title: string, conversationId: number) => void
  /**
   * 生成失败，或连接层面的失败。
   *
   * @param streamStarted 事件流是否真的开始过。false 表示请求没被后端接受，
   *   后端不会留下任何数据（用户消息也没落库）。
   */
  onError: (message: string, streamStarted: boolean) => void
}

/** 发送消息并接收流式回复 */
export async function sendMessage(
  conversationId: number,
  content: string,
  attachmentIds: number[],
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  await streamChat(
    `/api/conversations/${conversationId}/messages`,
    { content, attachmentIds },
    handlers,
    signal,
  )
}

/** 重新生成：由服务端删掉最后一条助手回复，并复用其前面那条用户消息，用户消息不会重复插入 */
export async function regenerate(
  conversationId: number,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  await streamChat(`/api/conversations/${conversationId}/regenerate`, undefined, handlers, signal)
}

async function streamChat(
  path: string,
  body: unknown,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  await streamSse(
    `${API_BASE_URL}${path}`,
    body,
    {
      onEvent(event, data) {
        switch (event) {
          case 'delta':
            handlers.onDelta(textField(data, 'content'))
            break
          case 'done':
            handlers.onDone(numberField(data, 'messageId'))
            break
          case 'title':
            handlers.onTitle(textField(data, 'title'), numberField(data, 'conversationId'))
            break
          case 'error':
            // 能收到 error 事件说明流已经建立，后端也已落过用户消息
            handlers.onError(textField(data, 'message'), true)
            break
          default:
            // 未知事件名直接忽略，便于后端日后新增事件而不破坏旧前端
            break
        }
      },
      onError: handlers.onError,
    },
    signal,
  )
}

function textField(data: unknown, key: string): string {
  if (data !== null && typeof data === 'object' && key in data) {
    const value: unknown = data[key as keyof typeof data]
    if (typeof value === 'string') {
      return value
    }
  }
  return ''
}

function numberField(data: unknown, key: string): number {
  if (data !== null && typeof data === 'object' && key in data) {
    const value: unknown = data[key as keyof typeof data]
    if (typeof value === 'number') {
      return value
    }
  }
  return 0
}
