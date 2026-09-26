/**
 * 附件类型的识别列表，与后端 `AttachmentTypePolicy` 里那份**逐条对齐**。
 *
 * 后端那份才是真正的边界——这份被绕过（改请求、直接 curl）也拦得住；
 * 它在这里的作用只是选中文件后立刻给出拒绝反馈，省掉一次没有意义的传输。
 *
 * 两份都必须是**精确枚举**，不能用 `image/*` 这样的前缀规则：前缀会把 `image/svg+xml`
 * 一并放进来，而 SVG 可以内嵌脚本，公网可读的桶里直接打开它的 URL 就是一个存储型 XSS 入口。
 *
 * **音频当前不在列表里**：`qwen3.8-max` 的官方输入模态是图像 / 文本 / 视频，
 * 实测带音频提交会被服务端以 `An incorrect modal 'audio' was entered` 拒绝。
 * 要支持音频得先把模型换成 Qwen-Omni 系列，再把对应 MIME 加回这里与后端那张表。
 *
 * **文本文件走的是另一条路**：它不作为多模态内容发给模型，而是切分后进向量库、
 * 提问时检索相关片段注入提示词。所以它的上限比图片视频严得多（见 MAX_TEXT_FILE_SIZE），
 * 卡片形态也不同（是纯文字卡片，没有缩略图可看）。
 */

import { FileText, Video } from '@lucide/vue'

const IMAGE_MIME_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/bmp']

const VIDEO_MIME_TYPES = ['video/mp4']

const TEXT_MIME_TYPES = ['text/plain']

const ACCEPTED_MIME_TYPES = new Set([...IMAGE_MIME_TYPES, ...VIDEO_MIME_TYPES, ...TEXT_MIME_TYPES])

/**
 * 给 `<input type="file">` 的 accept。
 *
 * 扩展名与 MIME 都列上：有些系统按扩展名推断类型、有些按注册表，只给一边会让文件在选择器里变灰。
 */
export const ACCEPT_ATTRIBUTE = [
  ...ACCEPTED_MIME_TYPES,
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.webp',
  '.bmp',
  '.mp4',
  '.txt',
].join(',')

/** 图片与视频的单文件大小上限，与后端 webchat.attachment.max-file-size 一致 */
export const MAX_FILE_SIZE = 10 * 1024 * 1024

/**
 * 文本文件的单文件大小上限，与后端 webchat.attachment.max-text-file-size 一致。
 *
 * 比媒体严得多是因为成本：文本要切分后逐段调 embedding，1MB 中文约合 35 万 token，
 * 而向量模型的 TPM 是 100 万——一个满额文件就吃掉三分之一的每分钟配额。
 */
export const MAX_TEXT_FILE_SIZE = 1 * 1024 * 1024

/** 单条消息的附件数量上限，与后端 webchat.attachment.max-files-per-message 一致 */
export const MAX_FILES_PER_MESSAGE = 5

/** 与 MIME 的顶层类型同名，省掉「前端叫 file、后端叫 text」这种两套命名 */
export type AttachmentKind = 'image' | 'video' | 'text'

/** 按 MIME 的顶层类型分类；不在识别列表内返回 null */
export function classify(mimeType: string | undefined): AttachmentKind | null {
  const mime = normalizeMime(mimeType)
  if (!ACCEPTED_MIME_TYPES.has(mime)) {
    return null
  }
  if (mime.startsWith('image/')) {
    return 'image'
  }
  return mime.startsWith('text/') ? 'text' : 'video'
}

/** 非图片附件的卡片副标题。图片走缩略图，不需要文字说明 */
export function kindLabel(kind: AttachmentKind): string {
  if (kind === 'image') {
    return '图片'
  }
  return kind === 'text' ? '文本文件' : '视频'
}

/** 卡片形态的附件种类，用来索引下面两张表 */
export type CardKind = Exclude<AttachmentKind, 'image'>

/** 把可能是图片的种类收窄成卡片种类，省掉模板里满屏的非空断言 */
export function cardKindOf(mimeType: string | undefined): CardKind {
  return classify(mimeType) === 'text' ? 'text' : 'video'
}

/**
 * 非图片卡片的图标与强调色。
 *
 * 规律取自设计稿里 PDF / 音频 / 表格那三张卡：**图标砖底色 = 强调色 + `1A`（即 10% 不透明度），
 * 图标本身用同一个强调色的纯色**。三处卡片（输入框预览、上传中、消息里）共用这两张表，
 * 加类型时只改这里。
 */
export const CARD_ICON = { video: Video, text: FileText } as const

export const CARD_ACCENT: Record<CardKind, { tile: string; icon: string }> = {
  // 设计稿没有视频，这一对沿用它的规律另取的 rose-600
  video: { tile: 'bg-[#E11D481A]', icon: 'text-[#E11D48]' },
  // blue-600 正是设计稿里「文档」那张卡的配色，与视频区分明显
  text: { tile: 'bg-[#2563EB1A]', icon: 'text-[#2563EB]' },
}

/**
 * 选中文件后的本地预检。
 *
 * @returns 不能上传时的原因，可以上传时返回 null
 */
export function validateFile(file: File): string | null {
  const kind = classify(file.type)
  if (!kind) {
    return `「${file.name}」不是支持的格式，只能上传常见的图片、MP4 视频或 txt 文本文件`
  }
  if (file.size === 0) {
    return `「${file.name}」是空文件`
  }
  const limit = kind === 'text' ? MAX_TEXT_FILE_SIZE : MAX_FILE_SIZE
  if (file.size > limit) {
    return `「${file.name}」超过 ${formatSize(limit)}，换一个小一点的`
  }
  return null
}

export function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${Math.round((bytes / (1024 * 1024)) * 10) / 10}MB`
  }
  if (bytes >= 1024) {
    return `${Math.round(bytes / 1024)}KB`
  }
  return `${bytes} 字节`
}

/** 浏览器偶尔会带上参数（如 video/mp4; codecs=avc1）或大小写不一 */
function normalizeMime(mimeType: string | undefined): string {
  if (!mimeType) {
    return ''
  }
  return mimeType.split(';')[0]?.trim().toLowerCase() ?? ''
}
