/**
 * Agent 平台预置形象（机器人 8 套）与色板。所属域：agent（前端）。
 * <p>
 * 与用户头像上传彻底分开：形象是前端矢量图元键，线稿颜色由 avatarConfig.color 驱动，
 * 背景恒为白底圆角方座（不随形象、不随颜色变化）。键名为正式命名，禁止 demo 前缀。
 * <p>
 * 视觉口径：白色圆角方座 + 座上线稿 SVG，边长由调用方给（列表 40px）。
 * 图形源已从 temp 的样稿抽成图元数据，运行期只读仓内常量，不读 temp/。
 */
import type { AgentRobotShape } from '@/constants/agentRobotShapes'
import type { AvatarConfig } from '@/types/agent'

/** 机器人形象底座色：白底方座（与 SVG 内的 rect 一致，用于图片透明区与首字兜底底） */
export const AGENT_AVATAR_BASE = '#ffffff'

/** 白底方座的细边框色，保证在白色卡片上仍有轮廓 */
export const AGENT_AVATAR_BORDER = '#e5e7eb'

/** 首字兜底时的默认线稿色（经典机霓虹蓝） */
export const AGENT_DEFAULT_LOOK_COLOR = '#38bdf8'

/** 单套机器人形象 */
export interface AgentRobotLook {
  /** 入库键，如 classic */
  key: string
  /** 展示名，如 经典机 */
  label: string
  /** 适用描述，如 通用百搭 */
  desc: string
  /** 该套的推荐霓虹色：未指定颜色时的默认线稿色 */
  color: string
  /** 线稿图元 */
  art: AgentRobotShape[]
}

/**
 * 平台 8 套机器人形象。
 * <p>顺序即文档/设计稿顺序；key 一旦入库不得改名。图形与推荐色成套，
 * 但颜色最终以 avatarConfig.color 为准（不填则用这里的推荐色）。
 */
export const AGENT_ROBOT_LOOKS: AgentRobotLook[] = [
  {
    key: 'classic',
    label: '经典机',
    desc: '通用百搭',
    color: '#38bdf8',
    art: [
      { t: 'rect', x: 5, y: 6, w: 14, h: 14, rx: 3 },
      { t: 'path', d: 'M12 2v4M9 12h.01M15 12h.01' },
      { t: 'path', d: 'M9 16h6M3 12h2M19 12h2' }
    ]
  },
  {
    key: 'explorer',
    label: '探索机',
    desc: '未知搜索',
    color: '#a78bfa',
    art: [
      { t: 'path', d: 'M12 2C7.58 2 4 5.58 4 10v8h16v-8c0-4.42-3.58-8-8-8z' },
      { t: 'rect', x: 7, y: 9, w: 10, h: 5, rx: 2 },
      { t: 'circle', cx: 10, cy: 11.5, r: 1 },
      { t: 'circle', cx: 14, cy: 11.5, r: 1 },
      { t: 'path', d: 'M9 18v3M15 18v3' }
    ]
  },
  {
    key: 'geek',
    label: '极客机',
    desc: '代码研发',
    color: '#4ade80',
    art: [
      { t: 'rect', x: 3, y: 7, w: 18, h: 12, rx: 4 },
      { t: 'path', d: 'M8 7V4M16 7V4' },
      { t: 'path', d: 'M7 13h2M15 13h2' },
      { t: 'path', d: 'M9 13h6' }
    ]
  },
  {
    key: 'cyclops',
    label: '单眼机',
    desc: '精准分析',
    color: '#fbbf24',
    art: [
      { t: 'path', d: 'M4 10h16v10H4z' },
      { t: 'circle', cx: 12, cy: 15, r: 3 },
      { t: 'path', d: 'M12 10V6M8 6h8' }
    ]
  },
  {
    key: 'armored',
    label: '重装机',
    desc: '硬核安全',
    color: '#f87171',
    art: [
      { t: 'path', d: 'M5 4h14v12l-7 5-7-5z' },
      { t: 'path', d: 'M8 9l3 3M16 9l-3 3M9 15h6' }
    ]
  },
  {
    key: 'broadcast',
    label: '电视机',
    desc: '信息播报',
    color: '#2dd4bf',
    art: [
      { t: 'rect', x: 3, y: 8, w: 18, h: 12, rx: 2 },
      { t: 'path', d: 'M7 4l2 4M17 4l-2 4' },
      { t: 'circle', cx: 8, cy: 14, r: 1.5 },
      { t: 'circle', cx: 16, cy: 14, r: 1.5 },
      { t: 'path', d: 'M10 14h4' }
    ]
  },
  {
    key: 'friendly',
    label: '萌系机',
    desc: '贴心接待',
    color: '#e879f9',
    art: [
      { t: 'path', d: 'M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z' },
      { t: 'path', d: 'M12 4v4' },
      { t: 'circle', cx: 12, cy: 3, r: 1 },
      { t: 'path', d: 'M8 14s1 1 4 1 4-1 4-1' }
    ]
  },
  {
    key: 'matrix',
    label: '矩阵机',
    desc: '数据运算',
    color: '#94a3b8',
    art: [
      { t: 'rect', x: 5, y: 5, w: 14, h: 14, rx: 2 },
      { t: 'path', d: 'M9 9h2v2H9zM13 9h2v2h-2zM9 13h2v2H9zM13 13h2v2h-2z' }
    ]
  }
]

/**
 * 历史键 → 机器人键的兼容映射。
 * <p>
 * 后端 {@code AgentLookPalette.ICONS} 是白名单，旧数据里已入库的是 look-* 键；
 * 本刀不改后端白名单、不做数据迁移，故在展示层按语义就近映射，
 * 保证老 Agent 升级后直接显示机器人而不是退回首字。
 */
const LEGACY_LOOK_ALIAS: Record<string, string> = {
  'look-guide': 'classic',
  'look-research': 'explorer',
  'look-coder': 'geek',
  'look-analyst': 'cyclops',
  'look-ops': 'armored',
  'look-butler': 'broadcast',
  'look-support': 'friendly',
  'look-scholar': 'matrix'
}

/** 表单用的形象选项（键沿用后端白名单，图形换成本刀的机器人） */
export interface AgentLookOption {
  /** 入库键，如 look-guide（后端白名单键） */
  key: string
  /** 表单展示名 */
  label: string
  /** 该套推荐霓虹色（缩略图默认线稿色） */
  color: string
}

/**
 * 平台预置形象清单（供现网形象弹窗使用，键仍是后端白名单键）。
 * <p>本刀只换图形与展示名，不新增选择器、不改上传链路。
 */
export const AGENT_LOOK_OPTIONS: AgentLookOption[] = [
  { key: 'look-guide', label: '经典机', color: '#38bdf8' },
  { key: 'look-research', label: '探索机', color: '#a78bfa' },
  { key: 'look-coder', label: '极客机', color: '#4ade80' },
  { key: 'look-analyst', label: '单眼机', color: '#fbbf24' },
  { key: 'look-ops', label: '重装机', color: '#f87171' },
  { key: 'look-butler', label: '电视机', color: '#2dd4bf' },
  { key: 'look-support', label: '萌系机', color: '#e879f9' },
  { key: 'look-scholar', label: '矩阵机', color: '#94a3b8' }
]

/** 固定色板（含主题紫）；即线稿可选的颜色集合，背景不受影响 */
export const AGENT_COLOR_OPTIONS: string[] = [
  '#635BFF',
  '#3B82F6',
  '#10B981',
  '#F59E0B',
  '#EF4444',
  '#8B5CF6',
  '#06B6D4',
  '#64748B'
]

/** 未配置颜色时的默认色（主题紫） */
export const AGENT_DEFAULT_COLOR = '#635BFF'

/** 用户气泡未上传头像时的中性底色 */
export const USER_AVATAR_FALLBACK_COLOR = '#94A3B8'

/**
 * 按机器人键取形象资源。
 *
 * @param key 机器人键（classic / explorer / …）
 * @return 选项；未知键返回 undefined
 */
export function findAgentRobotLook(key?: string | null): AgentRobotLook | undefined {
  if (!key) {
    return undefined
  }
  return AGENT_ROBOT_LOOKS.find((item) => item.key === key.trim().toLowerCase())
}

/**
 * 按任意历史键 / 机器人键取形象资源（含旧 look-* 兼容映射）。
 *
 * @param key 入库键，可能是新键或旧 look-* 键
 * @return 机器人形象；两者都对不上返回 undefined（调用方走首字兜底）
 */
export function resolveRobotLook(key?: string | null): AgentRobotLook | undefined {
  if (!key) {
    return undefined
  }
  const normalized = key.trim().toLowerCase()
  return findAgentRobotLook(normalized) || findAgentRobotLook(LEGACY_LOOK_ALIAS[normalized])
}

/**
 * 收敛形象配置为稳定结构（去掉空串、统一小写 type），可直接参与脏比较与提交。
 *
 * @param config 接口 / 弹窗回写的配置
 * @return 规范化配置；无有效内容时 null（表示未配置）
 */
export function normalizeAgentAvatarConfig(config?: AvatarConfig | null): AvatarConfig | null {
  if (!config) {
    return null
  }
  const type = (config.type || '').toLowerCase()
  const url = (config.url || '').trim()
  if (type === 'custom' && url) {
    return { type: 'custom', url }
  }
  const icon = (config.icon || '').trim()
  const color = (config.color || '').trim()
  if (!icon && !color) {
    return null
  }
  return { type: 'preset', icon: icon || null, color: color || null }
}

/** 头像可渲染视图 */
export interface AgentAvatarView {
  /** 自定义上传图地址；空串表示不用图片（走线稿或首字） */
  imgSrc: string
  /** 线稿图元；null 表示没有命中机器人图形 */
  art: AgentRobotShape[] | null
  /** 线稿描边色（即「颜色」；只作用于图形，不作用于背景） */
  strokeColor: string
  /** 底座色：恒为白底方座 */
  background: string
  /** 首字兜底文字 */
  initial: string
  /** 是否自定义上传图 */
  isCustom: boolean
  /** 命中机器人形象 */
  isRobot: boolean
  /** 首字兜底时文字颜色（沿用线稿色） */
  initialColor: string
}

/**
 * 把任意来源的形象配置收敛成可渲染视图。
 * <p>
 * custom 优先取上传图；preset 按键取机器人图元（含旧 look-* 兼容）；
 * 键对不上且无上传图时交给调用方用首字兜底。
 * 颜色只决定线稿描边，背景永远是白底方座。
 * 不抛错：库内脏值只影响展示，不阻断详情页。
 *
 * @param config 形象配置（接口原值）
 * @param name 展示名（首字兜底）
 * @return 可渲染视图
 */
export function resolveAgentAvatar(config?: AvatarConfig | null, name?: string | null): AgentAvatarView {
  const type = (config?.type || '').toLowerCase()
  const customUrl = (config?.url || '').trim()
  const robot = resolveRobotLook(config?.icon)
  const color = (config?.color || '').trim()
  return {
    imgSrc: type === 'custom' && customUrl ? resolveAvatarUrl(customUrl) : '',
    art: robot?.art || null,
    // 线稿色：优先用户选的颜色，未选则用该套推荐霓虹色，再兜底经典机蓝
    strokeColor: color || robot?.color || AGENT_DEFAULT_LOOK_COLOR,
    // 白底方座恒定，颜色不再影响背景
    background: AGENT_AVATAR_BASE,
    initial: initialOf(name),
    isCustom: type === 'custom' && !!customUrl,
    isRobot: !!robot,
    initialColor: color || robot?.color || AGENT_DEFAULT_LOOK_COLOR
  }
}

/**
 * 名称首字兜底（中文取一字，英文取大写首字母）。
 *
 * @param name 展示名
 */
export function initialOf(name?: string | null): string {
  const text = (name || '').trim()
  if (!text) {
    return 'A'
  }
  return text.slice(0, 1).toUpperCase()
}

/**
 * 未配置形象时的稳定兜底配置：按展示名固定挑一套平台机器人。
 *
 * <p>同一名字每次都落到同一套，刷新不会变脸；颜色取该套推荐色，不新造色值。
 * 方案（MA）未配 `ma_def.avatar_config` 时用它；专家未配置则仍沿用首字兜底（不改单兵口径）。
 *
 * @param name 展示名
 * @return 可直接渲染 / 回填的 preset 形象配置
 */
export function resolveFallbackRobotConfig(name?: string | null): AvatarConfig {
  const text = (name || '').trim()
  // 简单字符和取模：稳定、无依赖，中文按 UTF-16 码元累加即可
  let sum = 0
  for (let index = 0; index < text.length; index += 1) {
    sum = (sum + text.charCodeAt(index)) % 4096
  }
  const option = AGENT_LOOK_OPTIONS[sum % AGENT_LOOK_OPTIONS.length]
  return { type: 'preset', icon: option.key, color: option.color }
}

/**
 * 未配置形象时的稳定兜底形象：按展示名固定挑一套平台机器人。
 *
 * <p>用途：方案（MA）未配置自有形象时，时间线 / 候选池里不能再只给一个首字方座。
 * 方案形象现已落在 {@code ma_def.avatar_config}，本函数只负责「未配置」这一支。
 *
 * @param name 展示名
 * @return 可渲染形象视图（必为机器人线稿）
 */
export function resolveFallbackRobotAvatar(name?: string | null): AgentAvatarView {
  return resolveAgentAvatar(resolveFallbackRobotConfig(name), (name || '').trim())
}

/**
 * 把头像相对路径拼成可给 img 用的绝对地址。
 * <p>库里存 /uploads/...；开发期前端在 5173，需拼 VITE_API_BASE。
 *
 * @param avatarUrl 相对或绝对 URL
 */
export function resolveAvatarUrl(avatarUrl?: string | null): string {
  const raw = (avatarUrl || '').trim()
  if (!raw) {
    return ''
  }
  if (/^https?:\/\//i.test(raw)) {
    return raw
  }
  const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '')
  return raw.startsWith('/') ? `${base}${raw}` : `${base}/${raw}`
}
