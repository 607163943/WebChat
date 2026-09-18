import { del, get, post } from './http'
import type { ChatMessage, Conversation } from './types'

/** 当前用户的全部会话，按最后活跃时间倒序。后端不分页，一次返回全部。 */
export function listConversations(): Promise<Conversation[]> {
  return get<Conversation[]>('/api/conversations')
}

/** 新建空白会话，标题为默认值 */
export function createConversation(): Promise<Conversation> {
  return post<Conversation>('/api/conversations')
}

/** 删除会话及其全部消息。幂等，重复删除不会报错。 */
export function deleteConversation(id: number): Promise<void> {
  return del<void>(`/api/conversations/${id}`)
}

/** 会话的全部消息，按发送顺序 */
export function listMessages(conversationId: number): Promise<ChatMessage[]> {
  return get<ChatMessage[]>(`/api/conversations/${conversationId}/messages`)
}
