/**
 * 助手回答朗读（整段）。所属域：前端基础设施。
 *
 * 播放策略（按需求定稿）：
 * 1. Agent 绑定了 TTS 模型 → 调后端合成，拿到音频后用 <audio> 播放，支持暂停 / 继续 / 停止；
 * 2. 未绑定 TTS 模型 → 不禁用，直接降级浏览器 speechSynthesis；
 * 3. 合成失败（超时、凭证被拒等）→ 同样降级浏览器，并给一条非打断轻提示。
 *
 * 禁止项：不得用 Chat 模型出的文字冒充音频；不得借用平台默认 Key 兜底。
 * 内存：合成音频用 object URL，切换 / 停止 / 卸载时立即 revoke，不在页面长期持有。
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { synthesizeSpeech } from '@/api/voice'
import { toSpeakText } from '@/utils/speakText'

/** 朗读状态。preparing 表示模型音频还在路上，按钮显示「准备中」 */
export type SpeechState = 'idle' | 'preparing' | 'playing' | 'paused'

/** 朗读参数 */
export interface SpeechPlayerOptions {
  /** 当前 Agent id；没有 Agent（虚拟根 / MA 三栏）时不走模型合成 */
  getAgentId: () => number | undefined
  /** 该 Agent 是否绑定了可用的 TTS 模型 */
  ttsEnabled: () => boolean
  /** 非打断提示：合成失败降级、浏览器不支持等 */
  onNotice?: (message: string) => void
}

/**
 * 创建一个单例播放器：同一时刻只朗读一条回答，切到下一条会先停掉上一条。
 *
 * @param options 取 Agent 与提示回调
 * @return 播放状态与控制方法
 */
export function useSpeechPlayer(options: SpeechPlayerOptions) {
  /** 当前正在播放/暂停的回答键；idle 时为空串 */
  const key = ref('')
  const state = ref<SpeechState>('idle')

  let audio: HTMLAudioElement | null = null
  let objectUrl = ''
  /** 当前是否走浏览器合成（暂停 / 继续的实现不同） */
  let browser = false
  /**
   * 请求令牌：每次停止或开始新朗读都自增。
   * 迟到的合成响应据此丢弃，避免「点了停止又被后面的响应重新播起来」。
   */
  let token = 0

  /** 有条目处于播放/准备中（暂停也算占用，外部据此显示停止按钮） */
  const activeKey = computed(() => (state.value === 'idle' ? '' : key.value))

  /**
   * 点朗读按钮：同一条 → 暂停 / 继续；不同条 → 停掉上一条后从头播。
   *
   * @param turnKey 回答的唯一键（轮 key + 块序号）
   * @param markdown 助手回答原文（内部转朗读纯文本）
   */
  async function toggle(turnKey: string, markdown: string): Promise<void> {
    if (state.value === 'preparing' && key.value === turnKey) {
      // 合成还没回来就再点一次：视为放弃这条，直接停
      stop()
      return
    }
    if (state.value !== 'idle' && key.value === turnKey) {
      if (state.value === 'playing') {
        pause()
      } else {
        resume()
      }
      return
    }
    stop()
    await start(turnKey, markdown)
  }

  /**
   * 开始朗读一条回答。模型合成失败会自动降级浏览器。
   *
   * @param turnKey 回答键
   * @param markdown 回答原文
   */
  async function start(turnKey: string, markdown: string): Promise<void> {
    const text = toSpeakText(markdown)
    if (!text) {
      options.onNotice?.('这条回答没有可朗读的内容')
      return
    }
    const agentId = options.getAgentId()
    const myToken = ++token
    key.value = turnKey
    if (options.ttsEnabled() && agentId) {
      state.value = 'preparing'
      try {
        const blob = await synthesizeSpeech(agentId, text)
        if (myToken !== token) {
          return
        }
        await playBlob(blob)
        return
      } catch (error) {
        if (myToken !== token) {
          return
        }
        // 失败不打断用户：明确说明已改用浏览器，再继续朗读
        options.onNotice?.(`${messageOf(error, '语音合成失败')}，已改用浏览器朗读`)
      }
    }
    if (myToken !== token) {
      return
    }
    speakInBrowser(text, myToken)
  }

  /**
   * 播放模型返回的音频。
   *
   * @param blob 音频字节
   * @throws Error 浏览器拒绝自动播放或解码失败，由调用方降级
   */
  async function playBlob(blob: Blob): Promise<void> {
    objectUrl = URL.createObjectURL(blob)
    const element = new Audio(objectUrl)
    audio = element
    browser = false
    element.onended = () => reset()
    await new Promise<void>((resolve, reject) => {
      element.onerror = () => reject(new Error('音频播放失败'))
      element
        .play()
        .then(() => {
          state.value = 'playing'
          resolve()
        })
        .catch((error: unknown) => reject(error instanceof Error ? error : new Error('音频播放失败')))
    })
  }

  /**
   * 浏览器朗读（降级路径）。不依赖后端，也不看是否绑定模型。
   *
   * <p>为什么延迟一拍再 speak：Chrome 里紧接 cancel() 同步调用 speak() 会丢掉这一句
   * （队列清理与入队撞在同一帧），表现为「点了没声音、状态却显示在播」。
   * 这里先清空队列，下一拍再入队；令牌用于在延迟窗口内被停止 / 换条时丢弃本次朗读。
   *
   * @param text    朗读纯文本
   * @param myToken 本次朗读的令牌，与 reset / 新一次 start 比较
   */
  function speakInBrowser(text: string, myToken: number): void {
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) {
      reset()
      options.onNotice?.('当前浏览器不支持朗读，请更换 Chrome / Edge')
      return
    }
    const synth = window.speechSynthesis
    // 上一条的残留先清掉，避免两条叠着念；clean 后再入队
    if (synth.speaking || synth.pending) {
      synth.cancel()
    }
    const utterance = new SpeechSynthesisUtterance(text)
    utterance.lang = 'zh-CN'
    utterance.rate = 1
    utterance.onend = () => {
      if (myToken === token) {
        reset()
      }
    }
    utterance.onerror = () => {
      if (myToken === token) {
        reset()
      }
    }
    browser = true
    state.value = 'playing'
    let started = false
    utterance.onstart = () => {
      started = true
    }
    window.setTimeout(() => {
      // 延迟窗口内用户可能已停止或换了另一条，这次朗读必须作废
      if (myToken !== token) {
        return
      }
      // 之前点过暂停会把整条队列挂在 paused 上，直接 speak 会一直不出声
      if (synth.paused) {
        synth.resume()
      }
      synth.speak(utterance)
      // 兜底：浏览器静默拒绝（无可用语音 / 被策略拦住）时不能一直显示「播放中」。
      // 判据同时看 onstart 与 synth 自身状态——部分 Chrome 版本会正常发声但不派发 onstart，
      // 只看 started 会把正在播的朗读误判成失败并掐断。
      window.setTimeout(() => {
        if (myToken === token && !started && !synth.speaking && !synth.pending) {
          reset()
          options.onNotice?.('浏览器没有开始朗读，请检查系统语音与音量设置')
        }
      }, 1500)
    }, 60)
  }

  /** 暂停当前朗读 */
  function pause(): void {
    if (state.value !== 'playing') {
      return
    }
    if (browser) {
      window.speechSynthesis.pause()
    } else {
      audio?.pause()
    }
    state.value = 'paused'
  }

  /** 继续当前朗读 */
  function resume(): void {
    if (state.value !== 'paused') {
      return
    }
    if (browser) {
      window.speechSynthesis.resume()
    } else {
      void audio?.play()
    }
    state.value = 'playing'
  }

  /** 停止并复位；可安全重复调用 */
  function stop(): void {
    reset()
  }

  /**
   * 复位：令牌自增让在途响应失效，同时停掉两套播放器并释放 object URL。
   */
  function reset(): void {
    token += 1
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel()
    }
    if (audio) {
      audio.pause()
      audio.onended = null
      audio.onerror = null
      audio.removeAttribute('src')
      audio = null
    }
    if (objectUrl) {
      URL.revokeObjectURL(objectUrl)
      objectUrl = ''
    }
    browser = false
    key.value = ''
    state.value = 'idle'
  }

  // 关抽屉 / 切页时一定要停：否则声音会在已经离开的页面上继续念
  onBeforeUnmount(() => reset())

  return { key, state, activeKey, toggle, pause, resume, stop }
}

/**
 * @param error 捕获到的异常
 * @param fallback 兜底文案
 * @return 可展示的中文原因
 */
function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}
