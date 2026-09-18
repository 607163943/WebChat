/**
 * 复制文本到剪贴板。
 *
 * <p>优先用异步 Clipboard API；它要求安全上下文（https 或 localhost），
 * 用局域网 IP 打开开发服务器时不可用，因此保留一个 textarea + execCommand 的兜底。
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 落到下面的兜底方案
  }

  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    // 放在视口外，避免复制时页面跳动
    textarea.style.position = 'fixed'
    textarea.style.top = '-9999px'
    textarea.setAttribute('readonly', '')
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}
