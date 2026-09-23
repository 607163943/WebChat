import type { components } from './schema'

/**
 * 后端返回的会话列表项。
 *
 * 生成的 schema 把所有字段都标成了可选（Java record 序列化后字段其实总是存在），
 * 这里收窄成必填，省去业务代码里满屏的 `?? ''` 兜底。
 */
export type Conversation = Required<components['schemas']['ConversationVO']>

/** 一个已上传的附件。上传响应与消息回显共用同一个结构 */
export type Attachment = Required<components['schemas']['AttachmentVO']>

/**
 * 会话中的一条消息。
 *
 * `attachments` 只在用户消息上可能非空；它是「刷新后附件还在」的唯一来源。
 * 这里连数组元素一起收窄成必填——`Required<MessageVO>` 只把 `attachments` 本身变成必填，
 * 元素类型仍是字段全可选的 `AttachmentVO`，直接传给组件会报类型不兼容。
 */
export type ChatMessage = Omit<Required<components['schemas']['MessageVO']>, 'attachments'> & {
  attachments: Attachment[]
}

export type MessageRole = 'user' | 'assistant' | 'system'

/** 后端统一响应信封 */
export interface Result<T> {
  code: number
  message: string
  data: T
}
