/**
 * 对话语音接口。所属域：runtime。
 *
 * 与试跑接口共用同一个 request 实例（已带 Bearer Token），但请求标记 `silent`：
 * 朗读失败需要「降级浏览器 + 轻提示」，不能再被全局红条打断。
 *
 * 安全边界：音频只在内存里转成 FormData 直接发出，不落 localStorage、不写 IndexedDB；
 * 合成音频用完即 revoke，不在页面上长期持有二进制。
 */
import type { AxiosError } from 'axios'
import request from '@/utils/request'
import type { Result } from '@/types'

/** 听写超时。整段录音上传 + 上游识别都比普通接口慢，15 秒不够 */
const TRANSCRIBE_TIMEOUT_MS = 60_000
/** 合成超时。整段回答合成接近上游的模型时延上限 */
const SYNTHESIZE_TIMEOUT_MS = 90_000

/** 识别返回体 */
interface VoiceTextResult {
  text: string
}

/**
 * 整段音频 → 文字。识别成功后由调用方填进输入框，本函数不触发发送。
 *
 * @param agentId Agent（语音模型从该 Agent 的 ASR 槽取）
 * @param audio   录音 Blob
 * @param fileName 上传文件名，扩展名决定上游按什么容器解码
 * @return 识别文字（已去首尾空白；空结果由后端转成业务错误）
 * @throws Error 中文可读原因，调用方直接展示
 */
export async function transcribeAudio(agentId: number, audio: Blob, fileName: string): Promise<string> {
  const form = new FormData()
  form.append('file', audio, fileName)
  try {
    const response = await request.post<Result<VoiceTextResult>>('/api/runtime/voice/transcribe', form, {
      params: { agentId },
      // 不手写 Content-Type：交给浏览器带 boundary，手写反而会让后端解析失败
      timeout: TRANSCRIBE_TIMEOUT_MS,
      silent: true
    })
    return (response.data?.data?.text || '').trim()
  } catch (error) {
    throw new Error(await messageOf(error, '语音识别失败，请重试'))
  }
}

/**
 * 整段文字 → 音频字节。
 *
 * @param agentId Agent（语音模型从该 Agent 的 TTS 槽取）
 * @param text    待朗读纯文本（调用方已去掉 Markdown 标记）
 * @return 音频 Blob
 * @throws Error 中文可读原因；调用方据此降级到浏览器朗读
 */
export async function synthesizeSpeech(agentId: number, text: string): Promise<Blob> {
  try {
    const response = await request.post<Blob>('/api/runtime/voice/speak', { agentId, text }, {
      responseType: 'blob',
      timeout: SYNTHESIZE_TIMEOUT_MS,
      silent: true
    })
    const blob = response.data
    // 业务失败时后端仍返回 JSON，但 responseType=blob 会把它包成 Blob；这里还原成人话
    if (blob.type.includes('application/json')) {
      throw new Error(await bodyMessage(blob, '语音合成失败，请重试'))
    }
    if (!blob || blob.size === 0) {
      throw new Error('语音合成没有返回音频，请稍后重试')
    }
    return blob
  } catch (error) {
    if (error instanceof Error) {
      throw error
    }
    throw new Error(await messageOf(error, '语音合成失败，请重试'))
  }
}

/**
 * 从 axios 错误里取后端 msg。blob 响应体需要异步读一次文本。
 *
 * @param error    捕获到的异常
 * @param fallback 取不到时的兜底文案
 * @return 可直接展示的中文
 */
async function messageOf(error: unknown, fallback: string): Promise<string> {
  // 响应拦截器对业务失败直接 reject 后端的 Result 体（没有 response 包装），优先取它的 msg
  if (error && typeof error === 'object' && 'msg' in error) {
    const msg = (error as { msg?: unknown }).msg
    if (typeof msg === 'string' && msg.trim()) {
      return msg
    }
  }
  const response = (error as AxiosError<unknown> | undefined)?.response
  const data = response?.data
  if (data instanceof Blob) {
    return bodyMessage(data, fallback)
  }
  if (data && typeof data === 'object' && 'msg' in data) {
    const msg = (data as { msg?: unknown }).msg
    return typeof msg === 'string' && msg.trim() ? msg : fallback
  }
  return fallback
}

/**
 * 解析 JSON 错误体里的 msg。
 *
 * @param blob     JSON 响应体
 * @param fallback 解析失败时的兜底文案
 * @return 中文原因
 */
async function bodyMessage(blob: Blob, fallback: string): Promise<string> {
  try {
    const body = JSON.parse(await blob.text()) as { msg?: string }
    return body.msg?.trim() || fallback
  } catch {
    return fallback
  }
}
