import axios from 'axios'
import type { AxiosError } from 'axios'

import type { Result } from './types'

/**
 * 后端地址。开发期前端 7000 直连后端 8000，跨域由后端 CORS 放行，Vite 侧不配代理。
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8000'

/** 请求根本没连上后端时的提示文案。SSE 封装也用这一句，避免两处写法漂移 */
export const NETWORK_ERROR_MESSAGE = '无法连接服务器，请确认后端已启动'

/** 业务异常，message 已经是可直接展示给用户的文案 */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 15000,
})

/** 拆掉 Result 信封：成功取 data，失败抛错误 */
function unwrap<T>(result: Result<T>): T {
  if (result.code !== 200) {
    throw new ApiError(result.message, result.code)
  }
  return result.data
}

function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  const axiosError = error as AxiosError<Result<unknown>>
  // 后端出错时也返回 Result 信封，优先用它给的文案
  const backendMessage = axiosError.response?.data?.message
  if (backendMessage) {
    return new ApiError(backendMessage, axiosError.response?.status)
  }
  if (axiosError.code === 'ECONNABORTED') {
    return new ApiError('请求超时，请重试')
  }
  if (!axiosError.response) {
    return new ApiError(NETWORK_ERROR_MESSAGE)
  }
  return new ApiError(`请求失败（HTTP ${axiosError.response.status}）`, axiosError.response.status)
}

export async function get<T>(url: string): Promise<T> {
  try {
    const { data } = await http.get<Result<T>>(url)
    return unwrap(data)
  } catch (error) {
    throw toApiError(error)
  }
}

export async function post<T>(url: string, body?: unknown): Promise<T> {
  try {
    const { data } = await http.post<Result<T>>(url, body)
    return unwrap(data)
  } catch (error) {
    throw toApiError(error)
  }
}

export async function del<T>(url: string): Promise<T> {
  try {
    const { data } = await http.delete<Result<T>>(url)
    return unwrap(data)
  } catch (error) {
    throw toApiError(error)
  }
}

/**
 * 上传 multipart 表单。与 {@link post} 分开是因为两处默认值都得改：
 *
 * 1. **不要手设 Content-Type**——axios 会连同 boundary 一起生成，手设成 application/json
 *    会让后端解析不出分段，直接报「当前请求不是 multipart」。
 * 2. 上传要传字节，10MB 的文件在慢网络下很容易超过默认的 15 秒。
 *
 * `onProgress` 给的是**已交给网络栈的字节**比例，不是服务端处理完的比例：它到 1
 * 只说明请求体发完了，后端还在落盘、嗅探文件头、写库，所以调用方在满格后要换文案。
 *
 * 进度来自 axios 的 xhr 适配器（浏览器里的默认首选）——**fetch 适配器不支持上传进度**，
 * 日后若有人把 adapter 改成 fetch，这里会静默地一直停在 0。
 */
export async function upload<T>(
  url: string,
  form: FormData,
  onProgress?: (progress: number) => void,
): Promise<T> {
  try {
    const { data } = await http.post<Result<T>>(url, form, {
      timeout: 60000,
      onUploadProgress: (event) => {
        // total 为 0 或缺失时（lengthComputable 为 false）算不出比例，只能不报
        if (onProgress && event.total) {
          onProgress(Math.min(event.loaded / event.total, 1))
        }
      },
    })
    return unwrap(data)
  } catch (error) {
    throw toApiError(error)
  }
}
