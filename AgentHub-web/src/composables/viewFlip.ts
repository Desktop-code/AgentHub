/**
 * 网格↔主从飞卡（共用）。所属域：前端壳。
 * 全程 FLIP：Last 定几何 + Invert(translate/scale) → Play identity。
 * 落地交接：同帧先露真卡再摘幽灵（可交叉淡入）；禁止先删幽灵再显真卡。
 * Agent / Skill / 工具源 / 模型供应商页共用本执行器，禁止各页另写一套动画。
 */
import { ref } from 'vue'

/** 幽灵卡简易标签；tone 控制色板。 */
export interface FlipGhostTag {
  label: string
  tone?: 'on' | 'off' | 'react' | 'router' | 'plain' | 'warn'
}

/**
 * 飞卡快照：各页把业务行压成统一形态，幽灵 DOM 只读这些字段。
 */
export interface FlipCardSnap {
  id: number
  name: string
  /** 副行：编码 / 标题 / 类型 / 协议等 */
  code: string
  tags: FlipGhostTag[]
}

export interface FlipRect {
  top: number
  left: number
  width: number
  height: number
}

/** 幽灵：几何按 Last 写死；只过渡 transform / 交接时的 opacity。 */
export interface GhostFrame {
  top: string
  left: string
  width: string
  height: string
  transform: string
  transformOrigin: string
  transition: string
  visibility: string
  opacity: string
  willChange: string
}

/** 落点视觉：去程对齐选中竖列卡，回程对齐网格卡。 */
export type GhostLanding = 'rail-selected' | 'grid'

const TO_MASTER_MS = 340
const TO_GRID_MS = 260
const CROSSFADE_MS = 70
const EASE = 'cubic-bezier(0.25, 0.8, 0.25, 1)'
const ORIGIN = 'top left'

function prefersReducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** 量元素视口矩形。 */
export function rectOf(el: HTMLElement): FlipRect {
  const r = el.getBoundingClientRect()
  return { top: r.top, left: r.left, width: r.width, height: r.height }
}

function sleep(ms: number) {
  return new Promise<void>((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

function waitFrames(count = 2) {
  return new Promise<void>((resolve) => {
    const step = (left: number) => {
      if (left <= 0) {
        resolve()
        return
      }
      requestAnimationFrame(() => step(left - 1))
    }
    step(count)
  })
}

function ghostInvert(last: FlipRect, first: FlipRect): GhostFrame {
  const sx = first.width / Math.max(last.width, 1)
  const sy = first.height / Math.max(last.height, 1)
  const dx = first.left - last.left
  const dy = first.top - last.top
  return {
    top: `${last.top}px`,
    left: `${last.left}px`,
    width: `${last.width}px`,
    height: `${last.height}px`,
    transform: `translate3d(${dx}px,${dy}px,0) scale(${sx},${sy})`,
    transformOrigin: ORIGIN,
    transition: 'none',
    visibility: 'visible',
    opacity: '1',
    willChange: 'transform, opacity'
  }
}

function ghostPlay(last: FlipRect, ms: number): GhostFrame {
  return {
    top: `${last.top}px`,
    left: `${last.left}px`,
    width: `${last.width}px`,
    height: `${last.height}px`,
    transform: 'translate3d(0,0,0) scale(1,1)',
    transformOrigin: ORIGIN,
    transition: `transform ${ms}ms ${EASE}`,
    visibility: 'visible',
    opacity: '1',
    willChange: 'transform, opacity'
  }
}

/**
 * 飞卡执行器。busy 时调用方禁止切视图 / 切选中项。
 */
export function useViewFlip() {
  const busy = ref(false)
  const flyingId = ref(0)
  const ghostSnap = ref<FlipCardSnap | null>(null)
  const ghostFrame = ref<GhostFrame | null>(null)
  const ghostLanding = ref<GhostLanding>('rail-selected')
  const detailPhase = ref<'none' | 'enter' | 'leave'>('none')

  function clearGhost() {
    ghostSnap.value = null
    ghostFrame.value = null
  }

  function resetFlags() {
    busy.value = false
    flyingId.value = 0
    detailPhase.value = 'none'
    clearGhost()
  }

  /**
   * 交接同帧：先露真卡，再淡出并摘幽灵。禁止先删幽灵。
   * 不在此触发路由 / 列表重拉，避免落地 reflow。
   */
  async function handoff(toEl: HTMLElement, fromEl: HTMLElement) {
    // 清幽灵残留 scale 语义：先钉死 identity（无 transition）
    if (ghostFrame.value) {
      ghostFrame.value = {
        ...ghostFrame.value,
        transform: 'translate3d(0,0,0) scale(1,1)',
        transformOrigin: ORIGIN,
        transition: 'none',
        opacity: '1'
      }
    }

    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => {
        // 1) 同帧先露真卡（覆盖 JS visibility，CSS 勿用 !important）
        toEl.style.visibility = 'visible'
        toEl.style.opacity = '0'
        toEl.style.transition = 'none'

        requestAnimationFrame(() => {
          toEl.style.transition = `opacity ${CROSSFADE_MS}ms linear`
          toEl.style.opacity = '1'
          if (ghostFrame.value) {
            ghostFrame.value = {
              ...ghostFrame.value,
              transform: 'translate3d(0,0,0) scale(1,1)',
              transformOrigin: ORIGIN,
              transition: `opacity ${CROSSFADE_MS}ms linear`,
              opacity: '0'
            }
          }
          resolve()
        })
      })
    })

    await sleep(CROSSFADE_MS)

    // 2) 真卡已在，再摘幽灵
    clearGhost()
    fromEl.style.visibility = ''
    toEl.style.transition = ''
    toEl.style.opacity = ''
    toEl.style.visibility = ''
    // 去掉 is-flying 遮罩（勿在摘幽灵前清，交叉淡入期间仍可用 flying 标记）
    flyingId.value = 0
    detailPhase.value = 'none'
    busy.value = false
  }

  /**
   * 同页 FLIP：translate + scale；落地手交接见 handoff。
   *
   * @param opts.snap 幽灵内容快照
   * @param opts.fromEl 起飞元素
   * @param opts.direction 去程 / 回程
   * @param opts.prepare 切层后返回落点元素
   * @param opts.detail 右侧详情进出动画相位
   * @return 是否完成飞卡（失败时调用方自行兜底切态）
   */
  async function runFlip(opts: {
    snap: FlipCardSnap
    fromEl: HTMLElement
    direction: 'to-master' | 'to-grid'
    prepare: () => Promise<HTMLElement | null> | HTMLElement | null
    detail: 'enter' | 'leave' | 'none'
  }) {
    if (busy.value) {
      return false
    }

    const ms = opts.direction === 'to-master' ? TO_MASTER_MS : TO_GRID_MS
    ghostLanding.value = opts.direction === 'to-master' ? 'rail-selected' : 'grid'

    if (prefersReducedMotion()) {
      busy.value = true
      flyingId.value = opts.snap.id
      if (opts.detail === 'leave') {
        detailPhase.value = 'leave'
      }
      await sleep(120)
      await opts.prepare()
      if (opts.detail === 'enter') {
        detailPhase.value = 'enter'
      }
      await sleep(80)
      resetFlags()
      return true
    }

    const first = rectOf(opts.fromEl)
    busy.value = true
    flyingId.value = opts.snap.id
    if (opts.detail === 'leave') {
      detailPhase.value = 'leave'
    }
    opts.fromEl.style.visibility = 'hidden'

    ghostSnap.value = opts.snap
    ghostFrame.value = {
      top: `${first.top}px`,
      left: `${first.left}px`,
      width: `${first.width}px`,
      height: `${first.height}px`,
      transform: 'translate3d(0,0,0) scale(1,1)',
      transformOrigin: ORIGIN,
      transition: 'none',
      visibility: 'visible',
      opacity: '1',
      willChange: 'transform, opacity'
    }

    await waitFrames(1)
    const toEl = await opts.prepare()
    if (!toEl) {
      opts.fromEl.style.visibility = ''
      resetFlags()
      return false
    }

    // 落点已带选中 class（activeId 提前设好）；只藏字能量 Last
    toEl.style.visibility = 'hidden'
    await waitFrames(1)
    const last = rectOf(toEl)

    ghostFrame.value = ghostInvert(last, first)
    await waitFrames(2)

    // Play 略短，留给交叉淡入
    const playMs = Math.max(120, ms - CROSSFADE_MS)
    ghostFrame.value = ghostPlay(last, playMs)

    if (opts.detail === 'enter') {
      detailPhase.value = 'enter'
    }

    await sleep(playMs)
    await handoff(toEl, opts.fromEl)
    return true
  }

  return {
    busy,
    flyingId,
    ghostSnap,
    ghostFrame,
    ghostLanding,
    detailPhase,
    runFlip,
    resetFlags
  }
}
