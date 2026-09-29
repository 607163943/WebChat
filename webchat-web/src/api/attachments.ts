import { API_BASE_URL, del, get, upload } from './http'
import type { Attachment, AttachmentIndexState } from './types'

/**
 * 上传一个附件，返回可直接回显的附件对象。
 *
 * 选中文件就立刻上传（而不是等发送时一起传），对应库里「message_id 为空 = 待绑定」的中间态——
 * 用户完全可能在还没有会话的时候就把图片传上来，而会话是延迟落库的。
 *
 * @param conversationId 当前会话；新对话草稿态还没有会话，传 null
 * @param onProgress 已发出的字节比例（0~1），用于预览行的进度环
 */
export function uploadAttachment(
  file: File,
  conversationId: number | null,
  onProgress?: (progress: number) => void,
): Promise<Attachment> {
  const form = new FormData()
  form.append('file', file)
  if (conversationId !== null) {
    form.append('conversationId', String(conversationId))
  }
  return upload<Attachment>('/api/attachments', form, onProgress)
}

/** 删除一个还没随消息发出的附件。后端幂等，重复删除不报错 */
export function deleteAttachment(id: number): Promise<void> {
  return del<void>(`/api/attachments/${id}`)
}

/**
 * 批量查询附件的索引状态。
 *
 * 文本附件的向量化在后台跑，上传响应里带的只能是「刚提交」那一刻的状态，
 * 失败（embedding 挂了、片段过多、队列满）要过一会儿才知道——靠这个接口问出来。
 *
 * ids 用逗号分隔：Spring 的 List<Long> 参数按这个约定拆分。
 */
export function fetchIndexStates(ids: number[]): Promise<AttachmentIndexState[]> {
  const query = new URLSearchParams({ ids: ids.join(',') })
  return get<AttachmentIndexState[]>(`/api/attachments/index-state?${query}`)
}

/**
 * 把后端给的相对路径补成可直接用的地址。
 *
 * 库里存的是相对路径（如 `/api/attachments/content/2026/09/23/xxx.png`），
 * 这样换部署环境不用改库；日后若接了对象存储、url 变成绝对地址，这里原样放行。
 */
export function resolveAttachmentUrl(url: string): string {
  return /^https?:\/\//i.test(url) ? url : `${API_BASE_URL}${url}`
}
