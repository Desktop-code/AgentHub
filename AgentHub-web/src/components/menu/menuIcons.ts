/**
 * 菜单图标候选集与组件映射。所属域：前端壳（菜单管理 / 侧栏 / 工作台共用）。
 *
 * 约束：
 * 1. 只登记 Element Plus 官方图标组件，名称与 `@element-plus/icons-vue` 导出一致；
 *    菜单表 `icon` 里存的就是这个名称字符串。
 * 2. 新增候选时必须同时在上方 import 与 `MENU_ICON_MAP` 里登记，
 *    否则侧栏与工作台会回退到默认 Setting 图标。
 * 3. 分组顺序即选择器展示顺序，按「常用 → 业务 → 文件 → 系统」排列。
 */
import type { Component } from 'vue'
import {
  Aim,
  Avatar,
  Bell,
  Box,
  ChatDotRound,
  Coin,
  Collection,
  Comment,
  Compass,
  Connection,
  Cpu,
  DataAnalysis,
  DataLine,
  Document,
  Files,
  Finished,
  Grid,
  Guide,
  Histogram,
  House,
  InfoFilled,
  Link,
  MagicStick,
  Management,
  Memo,
  Menu as MenuIcon,
  Money,
  Monitor,
  Notebook,
  OfficeBuilding,
  PieChart,
  Postcard,
  Promotion,
  Reading,
  Search,
  Setting,
  Star,
  Tickets,
  Timer,
  Tools,
  TrendCharts,
  Trophy,
  User,
  UserFilled,
  Wallet,
  Warning
} from '@element-plus/icons-vue'

/** 图标分组：label 用于选择器分组标题，items 为图标组件名 */
export interface MenuIconGroup {
  /** 分组标题 */
  label: string
  /** 该组下的图标组件名 */
  items: string[]
}

/** 名称到组件的映射；找不到时由调用方回退 Setting */
export const MENU_ICON_MAP: Record<string, Component> = {
  Aim,
  Avatar,
  Bell,
  Box,
  ChatDotRound,
  Coin,
  Collection,
  Comment,
  Compass,
  Connection,
  Cpu,
  DataAnalysis,
  DataLine,
  Document,
  Files,
  Finished,
  Grid,
  Guide,
  Histogram,
  House,
  InfoFilled,
  Link,
  MagicStick,
  Management,
  Memo,
  Menu: MenuIcon,
  Money,
  Monitor,
  Notebook,
  OfficeBuilding,
  PieChart,
  Postcard,
  Promotion,
  Reading,
  Search,
  Setting,
  Star,
  Tickets,
  Timer,
  Tools,
  TrendCharts,
  Trophy,
  User,
  UserFilled,
  Wallet,
  Warning
}

/** 菜单管理里可选的图标分组 */
export const MENU_ICON_GROUPS: MenuIconGroup[] = [
  {
    label: '常用',
    items: ['House', 'Setting', 'Menu', 'Grid', 'Files', 'Document', 'Tickets', 'Collection']
  },
  {
    label: '人员与组织',
    items: ['User', 'UserFilled', 'Avatar', 'OfficeBuilding', 'Management', 'Postcard']
  },
  {
    label: '接入与智能',
    items: ['Connection', 'Link', 'Cpu', 'MagicStick', 'Monitor', 'Compass', 'Aim', 'Guide']
  },
  {
    label: '数据与运营',
    items: ['DataLine', 'DataAnalysis', 'Histogram', 'PieChart', 'TrendCharts', 'Coin', 'Money', 'Wallet']
  },
  {
    label: '工具与状态',
    items: ['Tools', 'Box', 'Bell', 'Star', 'Warning', 'InfoFilled', 'Timer', 'Promotion']
  },
  {
    label: '知识与记录',
    items: ['Notebook', 'Reading', 'Memo', 'Search', 'ChatDotRound', 'Comment', 'Finished', 'Trophy']
  }
]

/**
 * 按名称取图标组件。
 *
 * @param name 菜单表 icon 字段
 * @return 命中时返回组件，空值或未登记时返回 Setting
 */
export function resolveMenuIcon(name?: string): Component {
  return (name && MENU_ICON_MAP[name]) || Setting
}
