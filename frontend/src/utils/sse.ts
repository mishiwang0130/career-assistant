import { getAccessToken } from '@/utils/storage'

/** SSE 事件回调：按事件名分发。 */
export type SseEventHandler = (event: string, data: string) => void

/** 进流前失败时抛出的异常，携带 HTTP 状态码。 */
export class SseRequestError extends Error {
  /** HTTP 状态码。 */
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'SseRequestError'
    this.status = status
  }
}

/**
 * 发送 SSE 请求并逐事件回调。
 *
 * EventSource 不支持 POST 与自定义请求头，因此这里用 fetch + ReadableStream 手工解析 SSE 报文。
 * 进流前失败（HTTP 非 2xx，后端返回统一 Result）会抛出 SseRequestError；
 * 进流后的失败由后端用 error 事件表达，通过 onEvent 回调交给业务处理。
 *
 * @param url 接口地址
 * @param payload 请求体
 * @param onEvent 事件回调
 * @param signal 取消信号
 */
export async function postSse(
  url: string,
  payload: unknown,
  onEvent: SseEventHandler,
  signal?: AbortSignal,
): Promise<void> {
  const accessToken = getAccessToken()
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify(payload),
    signal,
  })

  if (!response.ok || !response.body) {
    throw new SseRequestError(await readErrorMessage(response), response.status)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  for (;;) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    buffer = dispatchFrames(buffer, onEvent)
  }
  buffer += decoder.decode()
  dispatchFrames(`${buffer}\n\n`, onEvent)
}

/**
 * 从缓冲区中切出完整帧并回调，返回剩余的不完整内容。
 *
 * @param buffer 已接收内容
 * @param onEvent 事件回调
 * @returns 剩余未处理内容
 */
function dispatchFrames(buffer: string, onEvent: SseEventHandler): string {
  let rest = buffer
  let boundary = rest.indexOf('\n\n')
  while (boundary >= 0) {
    const frame = rest.slice(0, boundary)
    rest = rest.slice(boundary + 2)
    const message = parseFrame(frame)
    if (message) {
      onEvent(message.event, message.data)
    }
    boundary = rest.indexOf('\n\n')
  }
  return rest
}

/**
 * 解析单个 SSE 帧。
 *
 * 注释行（以 : 开头）与空行直接忽略；多个 data 行按 SSE 规范用换行拼接。
 *
 * @param frame 帧内容
 * @returns 解析结果，无数据时返回 null
 */
function parseFrame(frame: string): { event: string; data: string } | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const rawLine of frame.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (!line || line.startsWith(':')) {
      continue
    }
    const separatorIndex = line.indexOf(':')
    const field = separatorIndex === -1 ? line : line.slice(0, separatorIndex)
    let value = separatorIndex === -1 ? '' : line.slice(separatorIndex + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  return { event, data: dataLines.join('\n') }
}

/**
 * 读取进流前失败时的错误提示。
 *
 * @param response 响应对象
 * @returns 错误提示
 */
async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { msg?: string }
    if (body.msg) {
      return body.msg
    }
  } catch {
    // 非 JSON 响应退化为通用提示。
  }
  return response.status === 401 ? '登录已过期，请重新登录' : '对话请求失败'
}
