/**
 * 菜单图标**全量候选集**（选择器用）。所属域：前端壳。
 *
 * <p>为什么单独成文件：全表 46 个图标只有「菜单管理 → 图标选择器」需要看到，
 * 侧栏/工作台在实际渲染时只会用到菜单表里已有的那几十个名字。
 * 本文件被 `MenuIconPicker.vue`（菜单管理页，路由级懒加载）静态引用，
 * 因此这 46 个图标的代码只会在进入菜单管理页时才下载，不再随首屏壳一起加载。
 *
 * <p>侧栏运行期取图标请走 `./menuIcons` 的 {@code resolveMenuIcon}：
 * 它只带首屏真正需要的子集，遇到本文件才有的名字时再动态加载本模块。
 *
 * 约束：
 * 1. 只登记 Element Plus 官方图标组件，名称与 `@element-plus/icons-vue` 导出一致；
 *    菜单表 `icon` 里存的就是这个名称字符串。
 * 2. 新增候选时必须同时在上方 import 与 {@code MENU_ICON_MAP} 里登记，并在 `MENU_ICON_GROUPS` 分组中出现。
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
  Share,
  Star,
  Tickets,
  Timer,
  Tools,
  TrendCharts,
  Trophy,
  User,
  UserFilled,
  VideoPlay,
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

/** 全量「名称 → 组件」映射；选择器预览与侧栏的懒加载兜底都读这里 */
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
  Share,
  Star,
  Tickets,
  Timer,
  Tools,
  TrendCharts,
  Trophy,
  User,
  UserFilled,
  VideoPlay,
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
  },
  {
    // 媒体与分享：默认菜单种子里「知识库文档 / 评测」等用到 Share、VideoPlay，
    // 之前这两个名字没有登记，侧栏只能显示兜底图标；补进来后既能选、也能正确渲染
    label: '媒体与分享',
    items: ['Share', 'VideoPlay']
  }
]
