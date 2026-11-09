/**
 * 试跑左栏「历史会话」的分组模型。所属域：runtime（前端）。
 *
 * 从 `TrialPanel.vue` 抽出的共享类型 + 纯函数：父组件负责取数与文案，本模块只做
 * 「按时间分桶」这一件纯计算，子组件只按组渲染。双方都以本文件为契约，
 * 避免同一结构 / 同一分桶规则在组件两侧各写一份。
 *
 * 为什么抽成纯函数：分桶边界（日零点、7 天 / 30 天窗口）最容易写错又不便观察，
 * 纯函数可以用固定 `now` 直接断言，不必起页面。
 */
import type { TrialSession } from '@/types/runtime'

/** 一个分组标题（今日 / 7天内 / 30天内 / 更早）+ 该组下的会话行。 */
export interface SessionGroup {
  /** 分组标题，直接展示 */
  label: string
  /** 该组的会话，顺序由父组件决定 */
  items: TrialSession[]
}

/** 分组标题与展示顺序。顺序即左栏从上到下的顺序，空组不展示。 */
export const SESSION_GROUP_LABELS = ['今日', '7天内', '30天内', '更早'] as const

/** 分组标题类型 */
export type SessionGroupLabel = (typeof SESSION_GROUP_LABELS)[number]

/**
 * 7 天窗口的自然日跨度：含今日在内共 7 个自然日。
 * 「7天内」= 昨日 00:00 到 6 天前 00:00，不含今日（今日单独一组）。
 */
const RECENT_7_DAYS = 6
/** 30 天窗口同上：含今日共 30 个自然日，因此「30天内」从 29 天前算起。 */
const RECENT_30_DAYS = 29

/** 本地日零点，避免时区偏移把「今日」判错。 */
export function startOfLocalDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate())
}

/** 服务端时间可能是「yyyy-MM-dd HH:mm:ss」，统一成毫秒；解析失败返回 0。 */
export function parseSessionTime(value?: string): number {
  if (!value) {
    return 0
  }
  // 直接 new Date('yyyy-MM-dd HH:mm:ss') 在 Safari 等环境不可靠，先换成 ISO 风格
  const time = new Date(value.replace(' ', 'T')).getTime()
  return Number.isNaN(time) ? 0 : time
}

/**
 * 按 今日 / 7天内 / 30天内 / 更早 分桶。
 *
 * <p>边界一律按「本地自然日」计算，不用「过去 N 小时」：会话列表是按天理解的时间轴，
 * 用小时窗口会让同一组在一天内漂移。时间取 updateTime，缺失回落 startTime；
 * 解析不出时间的行归入「更早」（宁可沉底，也不误判成今天）。
 *
 * @param items 已排序的会话（顺序在组内保持不变）
 * @param now   当前时间，默认取系统时间；测试传入固定值
 * @return 只含非空组、按 {@link SESSION_GROUP_LABELS} 顺序排列的分组
 */
export function groupSessionsByDay(items: TrialSession[], now: Date = new Date()): SessionGroup[] {
  const buckets: Record<SessionGroupLabel, TrialSession[]> = {
    今日: [],
    '7天内': [],
    '30天内': [],
    更早: []
  }
  const todayStart = startOfLocalDay(now).getTime()
  const dayMs = 24 * 60 * 60 * 1000
  // 两档窗口下界：早于它的落到「更早」
  const sevenDayStart = todayStart - RECENT_7_DAYS * dayMs
  const thirtyDayStart = todayStart - RECENT_30_DAYS * dayMs

  for (const item of items) {
    const stamp = parseSessionTime(item.updateTime || item.startTime)
    if (stamp > 0 && stamp >= todayStart) {
      buckets['今日'].push(item)
    } else if (stamp >= sevenDayStart) {
      // 窗口下界是正数，脏时间（stamp=0）不会落进这里，会一路沉到「更早」
      buckets['7天内'].push(item)
    } else if (stamp >= thirtyDayStart) {
      buckets['30天内'].push(item)
    } else {
      buckets['更早'].push(item)
    }
  }
  return SESSION_GROUP_LABELS.filter((label) => buckets[label].length > 0).map((label) => ({
    label,
    items: buckets[label]
  }))
}
