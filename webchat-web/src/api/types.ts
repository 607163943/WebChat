import type { components } from './schema'

/**
 * 后端返回的会话列表项。
 *
 * 生成的 schema 把所有字段都标成了可选（Java record 序列化后字段其实总是存在），
 * 这里收窄成必填，省去业务代码里满屏的 `?? ''` 兜底。
 */
export type Conversation = Required<components['schemas']['ConversationVO']>

/**
 * 文本附件的向量索引状态。
 *
 * `pending` 之后必然走到一个终态，所以它既是界面上「还要不要继续问」的判据，
 * 也是轮询的终止条件。后端只把结果放在这里，失败原因（片段过多、队列已满、向量库满、
 * embedding 异常）只写日志——它们对用户是同一件事，分了也没有可采取的动作。
 */
export type IndexState = 'pending' | 'ready' | 'empty' | 'failed' | 'unavailable' | 'not_indexed'

/** 一个已上传的附件。上传响应与消息回显共用同一个结构 */
export type Attachment = Omit<Required<components['schemas']['AttachmentVO']>, 'indexState'> & {
  indexState: IndexState
}

/** 附件的索引状态，轮询接口的最小投影 */
export type AttachmentIndexState = Omit<
  Required<components['schemas']['AttachmentIndexStateVO']>,
  'indexState'
> & {
  indexState: IndexState
}

/**
 * 一条助手回复是怎么收场的。
 *
 * 生成出错或被中断时，半截正文照样落库（后端不会保存空回复），靠这个字段区分「没写完」——
 * 用户消息恒为 `completed`。
 */
export type MessageStatus = 'completed' | 'interrupted' | 'failed'

/**
 * 会话中的一条消息。
 *
 * `attachments` 只在用户消息上可能非空；它是「刷新后附件还在」的唯一来源。
 * 这里连数组元素一起收窄成必填——`Required<MessageVO>` 只把 `attachments` 本身变成必填，
 * 元素类型仍是字段全可选的 `AttachmentVO`，直接传给组件会报类型不兼容。
 *
 * `status` 同理收窄成字面量联合：生成侧写的是 `interrupted` / `failed`，组件里比对字符串时
 * 打错一个字母应该编译不过。
 */
export type ChatMessage = Omit<
  Required<components['schemas']['MessageVO']>,
  'attachments' | 'status'
> & {
  attachments: Attachment[]
  status: MessageStatus
}

export type MessageRole = 'user' | 'assistant' | 'system'

/** 后端统一响应信封 */
export interface Result<T> {
  code: number
  message: string
  data: T
}
