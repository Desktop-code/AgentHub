/**
 * 整段录音（听写输入）。所属域：前端基础设施。
 *
 * 交互口径：点一下开始、再点一下结束；结束即把整段音频交给 onComplete，
 * 由页面去调识别接口并填输入框——本 composable 不发送消息、不碰对话链路。
 *
 * 边界：
 * - 只用 MediaRecorder 录真实音频；禁用 Web Speech API 的语音识别（浏览器识别不可控且无法绑定模型）。
 * - 录音只存在内存 Blob 里，不写 localStorage / IndexedDB；取消或卸载即丢弃。
 * - 时长上限靠前端计时器兜（服务端读不到可靠时长），体积上限前后端各拦一次。
 */
import { onBeforeUnmount, ref } from 'vue'

/** 录音参数与回调 */
export interface VoiceRecorderOptions {
  /** 单段录音时长上限（秒），到时自动结束并转写 */
  maxSeconds?: number
  /** 单段录音体积上限（字节） */
  maxBytes?: number
  /** 正常结束：拿到整段音频 */
  onComplete?: (audio: Blob) => void | Promise<void>
  /** 用户主动取消：用于页面给「已取消」提示 */
  onCancel?: () => void
  /** 录音失败（无麦克风、权限拒绝、体积超限、空录） */
  onError?: (message: string) => void
}

/** 浏览器容器优先级：Chrome/Edge 首选 webm/opus，Safari 回落到 mp4 */
const PREFERRED_MIME_TYPES = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
  'audio/wav'
] as const

/**
 * 创建一个可复用的录音器。每个对话面板一个实例即可。
 *
 * @param options 上限与回调
 * @return 录音状态与控制方法
 */
export function useVoiceRecorder(options: VoiceRecorderOptions = {}) {
  const maxSeconds = options.maxSeconds ?? 60
  const maxBytes = options.maxBytes ?? 10 * 1024 * 1024

  const recording = ref(false)
  /** 已录秒数，用于按钮上的计时；停止后归零 */
  const seconds = ref(0)

  let recorder: MediaRecorder | null = null
  let stream: MediaStream | null = null
  let chunks: Blob[] = []
  let timer: number | undefined
  /** 用户点了取消：onstop 里只清理，不回调 onComplete */
  let cancelled = false
  /** 组件已卸载：收尾时不再回调页面，避免卸载后还弹提示 */
  let disposed = false

  /**
   * 开始录音。调用方需处理抛出的中文错误（直接展示即可）。
   *
   * @throws Error 环境不支持、非安全上下文、权限拒绝或没有麦克风
   */
  async function start(): Promise<void> {
    if (recording.value) {
      return
    }
    if (typeof MediaRecorder === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      throw new Error('当前浏览器不支持录音，请更换 Chrome / Edge')
    }
    // http 非 localhost 时浏览器直接禁用麦克风，先给明确原因，避免弹一个看不懂的 DOMException
    if (!window.isSecureContext) {
      throw new Error('当前页面不是 HTTPS（或 localhost），浏览器禁止使用麦克风')
    }
    let media: MediaStream
    try {
      media = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch (error) {
      throw new Error(microphoneMessage(error))
    }
    let created: MediaRecorder
    try {
      created = createRecorder(media)
    } catch {
      stopTracks(media)
      throw new Error('当前浏览器不支持该录音格式，请更换 Chrome / Edge')
    }
    stream = media
    recorder = created
    chunks = []
    cancelled = false
    created.ondataavailable = (event: BlobEvent) => {
      if (event.data && event.data.size > 0) {
        chunks.push(event.data)
      }
    }
    // 统一在 onstop 里收尾：正常停止、自动到时、组件卸载都走这里
    created.onstop = () => {
      void finish()
    }
    recording.value = true
    seconds.value = 0
    created.start(200)
    timer = window.setInterval(() => {
      seconds.value += 1
      if (seconds.value >= maxSeconds) {
        stop()
      }
    }, 1000)
  }

  /**
   * 停止录音并转写。重复调用无副作用。
   */
  function stop(): void {
    if (!recorder || !recording.value) {
      return
    }
    recording.value = false
    clearTimer()
    try {
      recorder.stop()
    } catch {
      // stop 失败时 onstop 不会回调，直接收尾，避免状态卡在「录音中」
      void finish()
    }
  }

  /**
   * 取消录音：停止但不调用 onComplete，只通知页面「已取消」。
   */
  function cancel(): void {
    if (!recording.value) {
      return
    }
    cancelled = true
    stop()
  }

  /**
   * 收尾：拼 Blob、释放麦克风、校验体积，再交给页面。
   */
  async function finish(): Promise<void> {
    const finished = recorder
    recorder = null
    const type = finished?.mimeType || 'audio/webm'
    const audio = new Blob(chunks, { type })
    chunks = []
    stopTracks(stream)
    stream = null
    seconds.value = 0
    if (disposed) {
      return
    }
    if (cancelled) {
      cancelled = false
      options.onCancel?.()
      return
    }
    if (audio.size === 0) {
      options.onError?.('没有录到声音，请重试')
      return
    }
    if (audio.size > maxBytes) {
      options.onError?.(`录音超过 ${Math.round(maxBytes / 1024 / 1024)}MB 上限，请缩短后重试`)
      return
    }
    try {
      await options.onComplete?.(audio)
    } catch (error) {
      options.onError?.(error instanceof Error && error.message ? error.message : '语音处理失败，请重试')
    }
  }

  /**
   * 构造 MediaRecorder：优先选浏览器声明支持的容器。
   *
   * @param media 麦克风流
   * @return 录音器
   */
  function createRecorder(media: MediaStream): MediaRecorder {
    const mimeType = preferredMimeType()
    return mimeType ? new MediaRecorder(media, { mimeType }) : new MediaRecorder(media)
  }

  /**
   * @return 首个受支持的容器；都不支持返回空串（交给浏览器默认）
   */
  function preferredMimeType(): string {
    for (const type of PREFERRED_MIME_TYPES) {
      if (MediaRecorder.isTypeSupported(type)) {
        return type
      }
    }
    return ''
  }

  function clearTimer(): void {
    if (timer !== undefined) {
      window.clearInterval(timer)
      timer = undefined
    }
  }

  function stopTracks(media: MediaStream | null): void {
    media?.getTracks().forEach((track) => track.stop())
  }

  // 切页 / 关抽屉时强制释放麦克风，绝不能把占用留到下一次进入
  onBeforeUnmount(() => {
    disposed = true
    cancelled = true
    recording.value = false
    clearTimer()
    if (recorder) {
      try {
        recorder.stop()
      } catch {
        // 卸载路径不关心结果
      }
      recorder = null
    }
    stopTracks(stream)
    stream = null
    chunks = []
  })

  return { recording, seconds, start, stop, cancel }
}

/**
 * 把 getUserMedia 的 DOMException 翻成可执行的中文建议。
 *
 * @param error 原始异常
 * @return 用户能看懂的原因
 */
function microphoneMessage(error: unknown): string {
  const name = error instanceof DOMException ? error.name : ''
  switch (name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return '未获得麦克风权限，请在浏览器地址栏允许麦克风后重试'
    case 'NotFoundError':
    case 'DevicesNotFoundError':
      return '没有检测到麦克风设备'
    case 'NotReadableError':
    case 'TrackStartError':
      return '麦克风被其他程序占用，请关闭后重试'
    default:
      return '无法开始录音，请检查麦克风连接与权限'
  }
}
