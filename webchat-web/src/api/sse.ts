import { fetchEventSource } from '@microsoft/fetch-event-source'

import { NETWORK_ERROR_MESSAGE } from './http'

/**
 * 由后端返回的错误体转换来的异常，用于和网络层失败区分开：
 * 前者要把后端的文案原样展示，后者要换成「连不上服务器」这类用户看得懂的提示。
 */
class BackendError extends Error {}

export interface SseHandlers {
  /** 收到一条带名字的事件；data 已解析成对象 */
  onEvent: (event: string, data: unknown) => void
  /**
   * 连接失败或事件流异常中断。
   *
   * @param streamStarted 事件流是否真的开始过。false 表示请求压根没被后端接受
   *   （连接不上、HTTP 报错、响应不是 SSE），此时后端不会留下任何数据——
   *   调用方据此判断要不要把自己乐观插入的内容撤掉。
   */
  onError: (message: string, streamStarted: boolean) => void
}

/**
 * 收发 SSE 的统一封装。**业务代码不要直接调用 @microsoft/fetch-event-source**，
 * 那三处默认行为对聊天场景都是错的，全部在这里隔离掉：
 *
 * 1. `onerror` 返回 undefined 不是「停止」，而是「1 秒后重连」（源码是
 *    `onerror?.(err) ?? retryInterval`）。失败会反复重发 POST，等价于重复生成、重复落库。
 *    必须**在 onerror 里 throw** 才能真正终止。
 * 2. 必须显式传 `openWhenHidden: true`。不传会注册 visibilitychange 监听，切标签页即
 *    abort 当前请求、切回来重新发起，同一次回答被生成两遍。
 * 3. 它的默认 `onopen` 在 content-type 不是 text/event-stream 时抛错——后端返回 JSON 错误体
 *    （比如会话不存在时的 404）就会命中这条，再绕回第 1 条的重试路径。
 *    所以这里自己判断状态码，不依赖库的默认检查。
 *
 * 另有一条协议约束：后端每条事件都必须带 `event:` 名。该库在 `event:` 缺失时把事件类型
 * 置为**空字符串**（而不是 SSE 规范里的 "message"），所以这里直接丢弃无名事件。
 *
 * @param signal 外部取消信号；主动取消不算错误，不会触发 onError
 */
export async function streamSse(
  url: string,
  body: unknown,
  handlers: SseHandlers,
  signal?: AbortSignal,
): Promise<void> {
  // 自己持有一个 controller，才能把外部 signal 与内部取消串起来
  const controller = new AbortController()
  const abortFromOutside = () => controller.abort()
  signal?.addEventListener('abort', abortFromOutside, { once: true })

  // 只有 onopen 确认了响应确实是事件流，才算「后端已经接受了这次请求」
  let streamStarted = false

  try {
    await fetchEventSource(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      openWhenHidden: true,

      async onopen(response) {
        if (response.ok) {
          const contentType = response.headers.get('content-type') ?? ''
          if (contentType.includes('text/event-stream')) {
            streamStarted = true
            return
          }
        }
        throw new BackendError(await readErrorMessage(response))
      },

      onmessage(message) {
        if (!message.event) {
          return
        }
        handlers.onEvent(message.event, parseData(message.data))
      },

      onclose() {
        // 服务端正常关流表示本次生成结束，不重连（返回 undefined 即不再重试）
      },

      onerror(error) {
        // 必须 throw —— 返回 undefined 会被当成「1 秒后重连」
        throw error
      },
    })
  } catch (error) {
    if (controller.signal.aborted) {
      return
    }
    handlers.onError(describeFailure(error, streamStarted), streamStarted)
  } finally {
    signal?.removeEventListener('abort', abortFromOutside)
  }
}

function parseData(data: string | undefined): unknown {
  if (!data) {
    return undefined
  }
  try {
    return JSON.parse(data)
  } catch {
    return undefined
  }
}

/** 把各类失败翻译成能直接展示给用户的文案 */
function describeFailure(error: unknown, streamStarted: boolean): string {
  if (error instanceof BackendError) {
    return error.message
  }
  if (streamStarted) {
    // 流已经建立过，说明是生成到一半断的
    return '生成中断，请重试'
  }
  // fetch 在网络层失败时抛的是 TypeError，文案随浏览器而异
  //（Chrome「Failed to fetch」/ Firefox「NetworkError…」/ Safari「Load failed」），
  // 统一换成人话
  return NETWORK_ERROR_MESSAGE
}

/** 后端非 2xx 时返回的是 Result 信封，优先用它给的文案 */
async function readErrorMessage(response: Response): Promise<string> {
  try {
    const payload: unknown = await response.json()
    if (
      payload !== null &&
      typeof payload === 'object' &&
      'message' in payload &&
      typeof payload.message === 'string' &&
      payload.message
    ) {
      return payload.message
    }
  } catch {
    // 响应体不是 JSON，落到下面的状态码兜底
  }
  return `请求失败（HTTP ${response.status}）`
}
