import type { components } from './schema'

/**
 * 后端返回的会话列表项。
 *
 * 生成的 schema 把所有字段都标成了可选（Java record 序列化后字段其实总是存在），
 * 这里收窄成必填，省去业务代码里满屏的 `?? ''` 兜底。
 */
export type Conversation = Required<components['schemas']['ConversationVO']>

/** 会话中的一条消息 */
export type ChatMessage = Required<components['schemas']['MessageVO']>

export type MessageRole = 'user' | 'assistant' | 'system'

/** 后端统一响应信封 */
export interface Result<T> {
  code: number
  message: string
  data: T
}
