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
