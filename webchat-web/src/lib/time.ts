/** 设计稿里会话项右上角的相对时间：刚刚 / 14:20 / 昨天 / 周一 */

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'] as const

const MINUTE = 60_000
const DAY = 24 * 60 * MINUTE

export function formatRelativeTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const now = new Date()
  if (now.getTime() - date.getTime() < MINUTE) {
    return '刚刚'
  }
  if (isSameDay(date, now)) {
    return `${pad(date.getHours())}:${pad(date.getMinutes())}`
  }
  if (isSameDay(date, new Date(now.getTime() - DAY))) {
    return '昨天'
  }
  if (now.getTime() - date.getTime() < 7 * DAY) {
    return WEEKDAYS[date.getDay()] ?? ''
  }
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

/** 消息流中间的时间分隔胶囊：今天 14:32 / 昨天 09:05 / 9月12日 14:32 */
export function formatMessageDivider(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  const now = new Date()
  if (isSameDay(date, now)) {
    return `今天 ${time}`
  }
  if (isSameDay(date, new Date(now.getTime() - DAY))) {
    return `昨天 ${time}`
  }
  return `${date.getMonth() + 1}月${date.getDate()}日 ${time}`
}

/** 会话项里把「同一天的消息」归到一组，用于插入日期分隔 */
export function dayKey(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toDateString()
}

function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  )
}

function pad(value: number): string {
  return value.toString().padStart(2, '0')
}
