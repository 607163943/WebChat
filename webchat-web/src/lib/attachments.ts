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
 */

const IMAGE_MIME_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/bmp']

const VIDEO_MIME_TYPES = ['video/mp4']

const ACCEPTED_MIME_TYPES = new Set([...IMAGE_MIME_TYPES, ...VIDEO_MIME_TYPES])

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
].join(',')

/** 单文件大小上限，与后端 webchat.attachment.max-file-size 一致 */
export const MAX_FILE_SIZE = 10 * 1024 * 1024

/** 单条消息的附件数量上限，与后端 webchat.attachment.max-files-per-message 一致 */
export const MAX_FILES_PER_MESSAGE = 5

export type AttachmentKind = 'image' | 'video'

/** 按 MIME 的顶层类型分类；不在识别列表内返回 null */
export function classify(mimeType: string | undefined): AttachmentKind | null {
  const mime = normalizeMime(mimeType)
  if (!ACCEPTED_MIME_TYPES.has(mime)) {
    return null
  }
  return mime.startsWith('image/') ? 'image' : 'video'
}

/** 非图片附件的卡片副标题。图片走缩略图，不需要文字说明 */
export function kindLabel(kind: AttachmentKind): string {
  return kind === 'image' ? '图片' : '视频'
}

/**
 * 选中文件后的本地预检。
 *
 * @returns 不能上传时的原因，可以上传时返回 null
 */
export function validateFile(file: File): string | null {
  if (!classify(file.type)) {
    return `「${file.name}」不是支持的格式，只能上传常见的图片或 MP4 视频`
  }
  if (file.size === 0) {
    return `「${file.name}」是空文件`
  }
  if (file.size > MAX_FILE_SIZE) {
    return `「${file.name}」超过 ${formatSize(MAX_FILE_SIZE)}，换一个小一点的`
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
