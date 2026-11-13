<template>
  <!--
    左右分栏壳：左侧导航通顶（Logo / 菜单 / 折叠 / 用户），右侧仅页签 + 主内容。
    无顶部 Header；KeepAlive / 页签缓存协议保持原样。
  -->
  <div class="shell" :class="{ 'is-editor-focus': focusActive }">
    <aside class="aside" :class="{ 'is-collapsed': collapsed }">
      <!-- 顶部品牌区：原顶栏 Logo 移入；折叠时只出角标 -->
      <div class="brand-zone">
        <AppLogo class="brand" :show-text="!collapsed" />
      </div>
      <nav class="nav">
        <!-- 工作台顶层入口，不包在「概览」目录下 -->
        <router-link
          to="/workspace"
          class="nav-link nav-top"
          :class="{ 'is-current': isCurrent('/workspace') }"
          :title="collapsed ? '对话' : undefined"
        >
          <el-icon><House /></el-icon>
          <span class="nav-label">对话</span>
        </router-link>
        <section v-for="dir in directories" :key="dir.id" class="dir">
          <!-- 不折叠分组：收起时标题隐藏，子菜单图标仍平铺显示 -->
          <template v-if="dir.flat">
            <div class="dir-heading">
              <span class="nav-label">{{ dir.name }}</span>
            </div>
          </template>
          <!-- 可折叠目录：收起时只留图标，子项进右侧飞出层 -->
          <el-popover
            v-else
            :disabled="!collapsed"
            placement="right-start"
            :width="200"
            trigger="hover"
            :show-arrow="false"
            :offset="8"
            popper-class="aside-flyout"
            :hide-after="80"
          >
            <template #reference>
              <div class="dir-ref">
                <button
                  type="button"
                  class="dir-btn"
                  :class="{ 'is-current': collapsed && dirContainsCurrent(dir) }"
                  @click="onDirClick(dir.id)"
                >
                  <el-icon><component :is="iconOf(dir.icon)" /></el-icon>
                  <span class="nav-label">{{ dir.name }}</span>
                  <el-icon class="caret"><component :is="isOpen(dir.id) ? ArrowDown : ArrowRight" /></el-icon>
                </button>
              </div>
            </template>
            <div class="flyout">
              <div class="flyout-title">{{ dir.name }}</div>
              <template v-for="item in dir.children" :key="item.id">
                <div v-if="item.kind === 'group'" class="flyout-group">
                  <div class="flyout-group-title">{{ item.name }}</div>
                  <router-link
                    v-for="leaf in item.children"
                    :key="leaf.id"
                    :to="leaf.path"
                    class="flyout-link"
                    :class="{ 'is-current': isCurrent(leaf.path) }"
                  >
                    {{ leaf.name }}
                  </router-link>
                </div>
                <router-link
                  v-else
                  :to="item.path"
                  class="flyout-link"
                  :class="{ 'is-current': isCurrent(item.path) }"
                >
                  {{ item.name }}
                </router-link>
              </template>
            </div>
          </el-popover>
          <!-- 不折叠：收起后子菜单仍显示；可折叠：仅展开态内联显示 -->
          <div
            v-show="dir.flat || (!collapsed && isOpen(dir.id))"
            class="children"
            :class="{ 'is-flat': dir.flat }"
          >
            <template v-for="item in dir.children" :key="item.id">
              <template v-if="item.kind === 'group'">
                <div class="dir-heading dir-heading-nested">
                  <span class="nav-label">{{ item.name }}</span>
                </div>
                <router-link
                  v-for="leaf in item.children"
                  :key="leaf.id"
                  :to="leaf.path"
                  class="nav-link"
                  :class="{ 'is-current': isCurrent(leaf.path) }"
                  :title="collapsed ? leaf.name : undefined"
                >
                  <el-icon><component :is="iconOf(leaf.icon)" /></el-icon>
                  <span class="nav-label">{{ leaf.name }}</span>
                </router-link>
              </template>
              <router-link
                v-else
                :to="item.path"
                class="nav-link"
                :class="{ 'is-current': isCurrent(item.path) }"
                :title="collapsed ? item.name : undefined"
              >
                <el-icon><component :is="iconOf(item.icon)" /></el-icon>
                <span class="nav-label">{{ item.name }}</span>
              </router-link>
            </template>
          </div>
        </section>
      </nav>
      <!-- 底部用户行：头像 + 名称 + 下拉箭头；箭头右侧是收起/展开按钮 -->
      <footer class="foot">
        <div class="account-row">
          <el-dropdown
            class="account-dropdown"
            trigger="click"
            placement="top-start"
            popper-class="aside-account-popper"
            @command="onAccountCommand"
          >
            <button
              type="button"
              class="account"
              aria-label="用户菜单"
              :title="collapsed ? displayName : undefined"
            >
              <span class="avatar" :class="{ 'has-photo': !!avatarSrc }" :style="avatarFallbackStyle">
                <img v-if="avatarSrc" :src="avatarSrc" alt="" />
                <template v-else>{{ initials }}</template>
              </span>
              <span class="account-meta">
                <span class="user-name">{{ displayName }}</span>
                <span v-if="roleLabel" class="role-tag">{{ roleLabel }}</span>
              </span>
              <!-- 下拉箭头在用户区最右，紧挨外侧收起按钮左侧 -->
              <el-icon class="account-caret"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人设置</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-tooltip
            :content="collapsed ? '展开侧边栏' : '收起侧边栏'"
            :placement="collapsed ? 'right' : 'top'"
          >
            <button
              type="button"
              class="collapse-btn"
              :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
              @click="toggleCollapse"
            >
              <el-icon class="collapse-icon">
                <component :is="collapsed ? Expand : Fold" />
              </el-icon>
            </button>
          </el-tooltip>
        </div>
      </footer>
    </aside>
    <!-- 右侧内容列：页签条与主区上下排列；折叠时靠 aside 宽度过渡让位 -->
    <div class="content">
      <!-- 编辑页专注模式：页签条也让位，主区高度全给画布；工作台等整屏页按 meta.hideTabs 隐藏 -->
      <TagsView v-if="!focusActive && !hideTabs" />
      <main class="main" :class="{ 'is-flush': onWorkspace }">
        <!--
          缓存策略：include 用 store 派生的 cachedViews（组件名），关页签即移出、组件被销毁。
          key 用 cacheName 而非 route.path：Agent 列表与详情共用同一组件（/agent/list ↔ /agent/list/1），
          若按路径做 key，翻卡结束后 replace URL 会把刚飞完的页面整块重挂，动画与状态全丢。
        -->
        <router-view v-slot="{ Component }">
          <keep-alive :include="cachedViews">
            <component :is="Component" :key="pageKey" />
          </keep-alive>
        </router-view>
      </main>
    </div>
    <!--
      运行抽屉：首次打开后才挂载（v-if=mounted），之后常驻。
      目的是让壳首屏不预取 TrialPanel 整树（ChatPanel / maRunView / 编排泳道），
      这一块的 chunk 体积远超壳本身，而大多数会话并不会运行。
      异步组件与 composable 共用同一个 chunk，`openAgentTrial` 会等它就绪再置可见。
    -->
    <AgentTrialDrawer
      v-if="trialDrawerMounted"
      v-model="trialOpen"
      :agent-id="trialTarget.agentId"
      :agent-name="trialTarget.name"
      :run-mode="trialTarget.runMode"
    />
    <!-- 个人设置：侧栏用户菜单进入；与 Agent 形象配置分开 -->
    <ProfileSettingsDialog v-model="profileOpen" />
  </div>
</template>

<script setup lang="ts">
/**
 * 控制台壳。所属域：前端布局。
 * 左右分栏：左侧导航通顶（Logo / 菜单 / 折叠 / 用户下拉），右侧仅多页签 + 主内容；无顶部 Header。
 * 「工作台」为顶层入口（默认落地页）；type=1 可折叠（进系统默认收起），type=4 无折叠分组。
 * 运行抽屉挂在壳上，列表和详情共用，不占路由。侧栏不渲染 /agent/trial。
 * KeepAlive include / pageKey 协议不变。壳上挂载 sessionKeepAlive：可见时每 10min 打 /me 保活。
 */
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQuery, LocationQueryValue } from 'vue-router'
import {
  ArrowDown,
  ArrowRight,
  Expand,
  Fold,
  House
} from '@element-plus/icons-vue'
import AppLogo from '@/components/AppLogo.vue'
import ProfileSettingsDialog from '@/components/profile/ProfileSettingsDialog.vue'
import { resolveMenuIcon, preloadMenuIcons } from '@/components/menu/menuIcons'
import {
  USER_AVATAR_FALLBACK_COLOR,
  resolveAvatarUrl
} from '@/constants/agentLooks'
import { openAgentTrial, preloadTrialDrawer, TRIAL_MENU_PATH, useAgentTrial } from '@/composables/agentTrial'
import { useSessionKeepAlive } from '@/composables/sessionKeepAlive'
import { useWorkflowFocus } from '@/composables/workflowFocus'
import { useTagsViewStore } from '@/stores/tagsView'
import { useUserStore } from '@/stores/user'
import TagsView from './components/TagsView.vue'
import type { MenuNode } from '@/types'

/**
 * 试跑抽屉按需分包：壳首屏不引用 TrialPanel 整树，
 * 首次 `openAgentTrial` 触发挂载时才真正下载（见 composables/agentTrial）。
 * 不设 loadingComponent：抽屉在 chunk 到位前不挂载，也就没有可展示的壳，
 * 首开的等待由 `openAgentTrial` 内部「等 chunk 就绪再置可见」吸收，不会出现空抽屉。
 */
const AgentTrialDrawer = defineAsyncComponent(() => preloadTrialDrawer())

/** 长页面（试跑等）可见时每 10min 打 /me，配合后端滑动续期；401 仍清票跳登录 */
useSessionKeepAlive()

/** 工作流编辑页专注模式：为 true 时收起左侧主导航与页签条，把高度让给画布 */
const { focusActive } = useWorkflowFocus()

/** 只记侧栏收起，和登录 token 无关。 */
const COLLAPSE_KEY = 'agenthub_sidebar_collapsed'
/**
 * 全局折叠偏好（localStorage）。工作台只在**本页**默认收起，
 * 不写这里、也不改用户已有的全局偏好。
 */
const collapsedGlobal = ref(localStorage.getItem(COLLAPSE_KEY) === '1')
/** 工作台本页的临时展开态：离开本页即失效，不持久化。 */
const workspaceExpanded = ref(false)

/** 侧栏叶子页面 */
interface NavItem {
  kind?: 'link'
  id: string
  name: string
  path: string
  icon?: string
}

/** 嵌套在可折叠目录下的无折叠分组 */
interface NavGroup {
  kind: 'group'
  id: string
  name: string
  children: NavItem[]
}

type NavChild = NavItem | NavGroup

interface NavDir {
  id: string
  name: string
  icon?: string
  /** true = type 4，纯文本标题且子项始终展开 */
  flat: boolean
  children: NavChild[]
}

/** 与后端 MenuType.DIRECTORY_FLAT 一致 */
const TYPE_DIRECTORY_FLAT = 4

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const tags = useTagsViewStore()
const { open: trialOpen, target: trialTarget, mounted: trialDrawerMounted } = useAgentTrial()
/** 当前是否工作台：唯一正式对话入口，整屏 + 默认收起侧栏。 */
const onWorkspace = computed(() => route.path === '/workspace')
/** 实际侧栏折叠：工作台默认收起；其它页沿用全局偏好。 */
const collapsed = computed(() => (onWorkspace.value ? !workspaceExpanded.value : collapsedGlobal.value))
/** 隐藏页签：只认路由 meta.hideTabs（当前仅工作台），其它页默认保持原样。 */
const hideTabs = computed(() => route.meta?.hideTabs === true)
/** 目录默认收起。用户点开后记在这里，true 才是展开。 */
const directoryOpen = ref<Record<string, boolean>>({})

// 离开工作台即丢掉本页临时展开态：下次进入仍默认收起，不改全局偏好
watch(
  () => route.path,
  (path) => {
    if (path !== '/workspace') {
      workspaceExpanded.value = false
    }
  }
)

/** KeepAlive include 名单：由页签派生，关页签即从缓存释放 */
const cachedViews = computed(() => tags.cachedViews)

/**
 * router-view 的 key。
 * 用缓存名而不是 route.path：Agent 列表与详情是同一组件的两种状态，
 * 按路径做 key 会在 replace URL 时把飞卡刚完成的页面重挂一遍。
 * 未配置 cacheName 的页面退回 path，保证不同页之间仍能正确切换。
 */
const pageKey = computed(() => {
  const name = route.meta?.cacheName
  return typeof name === 'string' && name ? name : route.path
})

const displayName = computed(() => user.profile?.nickname || user.profile?.username || '用户')
const initials = computed(() => displayName.value.trim().slice(0, 1).toUpperCase() || 'A')
/** 已上传头像时用图片；否则首字 + 中性底色 */
const avatarSrc = computed(() => resolveAvatarUrl(user.profile?.avatarUrl))
const avatarFallbackStyle = computed(() =>
  avatarSrc.value ? undefined : { background: USER_AVATAR_FALLBACK_COLOR, color: '#fff' }
)
/** 个人设置弹窗 */
const profileOpen = ref(false)
/**
 * 角色标签：取 /me 返回的角色编码（去空）；无角色时平台管理员兜底文案。
 * 禁止写死「系统管理员」——那是种子用户的昵称，不是角色字段。
 */
const roleLabel = computed(() => {
  const roles = (user.profile?.roles ?? []).map((item) => item.trim()).filter(Boolean)
  if (roles.length) {
    return roles[0]
  }
  return user.profile?.platformAdmin ? '平台管理员' : ''
})

/** 仅后端菜单目录；工作台在模板里单独顶层渲染。 */
const directories = computed(() =>
  (user.profile?.menus ?? [])
    .filter((node) => node.visible !== 0)
    .map(toDirectory)
    .filter((dir) => dir.children.length > 0)
)

/**
 * 顶层节点转为侧栏分组。type=4 无折叠；其余按可折叠目录处理。
 * 子级若仍是 type=4，再嵌一层纯文本标题并平铺其叶子。
 */
function toDirectory(node: MenuNode): NavDir {
  return {
    id: String(node.id),
    name: node.name,
    icon: node.icon || 'Setting',
    flat: node.type === TYPE_DIRECTORY_FLAT,
    children: collectChildren(node.children || [])
  }
}

/**
 * 收集侧栏子节点：页面进链接；无折叠分组保留标题并展开其叶子。
 */
function collectChildren(nodes: MenuNode[]): NavChild[] {
  const result: NavChild[] = []
  for (const item of nodes) {
    if (item.visible === 0) {
      continue
    }
    if (item.type === TYPE_DIRECTORY_FLAT) {
      const leaves = collectLeafLinks(item.children || [])
      if (leaves.length) {
        result.push({ kind: 'group', id: String(item.id), name: item.name, children: leaves })
      }
      continue
    }
    if (item.path && item.path !== '/system' && item.path !== TRIAL_MENU_PATH) {
      result.push({
        kind: 'link',
        id: String(item.id),
        name: item.name,
        path: item.path,
        icon: item.icon
      })
    }
  }
  return result
}

/** 只取可见且带路由的叶子页面。 */
function collectLeafLinks(nodes: MenuNode[]): NavItem[] {
  return nodes
    .filter((item) => item.visible !== 0 && item.path && item.path !== '/system' && item.path !== TRIAL_MENU_PATH)
    .map((item) => ({
      kind: 'link' as const,
      id: String(item.id),
      name: item.name,
      path: item.path,
      icon: item.icon
    }))
}

/** 侧栏图标统一走内置映射，未登记名称回退 Setting。 */
function iconOf(name?: string) {
  return resolveMenuIcon(name)
}

/**
 * 菜单里出现「非常驻图标」时，提前把候选集 chunk 拉下来。
 *
 * <p>非常驻图标是异步组件，渲染时才下载会先画一帧兜底图标；
 * 菜单数据到手就发起下载，等侧栏真正绘制时基本已就位。
 *
 * @param nodes 后端菜单树（未裁剪）
 */
function preloadMenuIconChunk(nodes: MenuNode[]) {
  const names: string[] = []
  const walk = (list: MenuNode[]) => {
    for (const node of list) {
      if (node.icon) {
        names.push(node.icon)
      }
      if (node.children?.length) {
        walk(node.children)
      }
    }
  }
  walk(nodes)
  void preloadMenuIcons(names)
}

// 菜单树一变（登录后首次到手 / 重新拉取权限）就预载非常驻图标；全都在常驻表里则不发请求
watch(
  () => user.profile?.menus,
  (menus) => {
    if (menus?.length) {
      preloadMenuIconChunk(menus)
    }
  },
  { immediate: true }
)

function isOpen(id: string) {
  return directoryOpen.value[id] === true
}

/** 详情挂在菜单路径下，例如 /agent/list/1 仍高亮「Agent 管理」。 */
function isCurrent(path: string) {
  if (!path) {
    return false
  }
  return menuPathOf(route.path) === path || route.path.startsWith(`${path}/`)
}

/**
 * 二级页与菜单路径不一致时的别名表。
 * 知识库文档管理是独立二级路由 /rag/kb/:id/docs（不进侧栏），
 * 但它语义上属于「知识库」菜单，靠这张表让侧栏仍高亮 /access/kb。
 */
const PATH_ALIAS: Record<string, string> = {
  '/rag/kb': '/access/kb'
}

/**
 * 取当前路由对应的菜单路径：命中别名表时返回菜单侧路径，否则原样返回。
 *
 * @param path 当前路由路径
 * @return 用于比对的路径
 */
function menuPathOf(path: string) {
  for (const [prefix, menuPath] of Object.entries(PATH_ALIAS)) {
    if (path === prefix || path.startsWith(`${prefix}/`)) {
      return menuPath
    }
  }
  return path
}

/** 折叠目录。当前页所在目录会在路由变化时重新展开。 */
function toggle(id: string) {
  directoryOpen.value = { ...directoryOpen.value, [id]: !isOpen(id) }
}

/**
 * 目录按钮点击：仅展开态切换折叠；收起态靠悬停飞出层，避免误触折叠。
 */
function onDirClick(id: string) {
  if (collapsed.value) {
    return
  }
  toggle(id)
}

/** 子节点是否包含当前路由（含嵌套无折叠分组内的叶子）。 */
function childMatchesPath(child: NavChild, path: string) {
  // 先做别名归一：/rag/kb/:id/docs 归到 /access/kb，目录展开与高亮才不会漏
  const target = menuPathOf(path)
  if (child.kind === 'group') {
    return child.children.some((leaf) => leaf.path === target || target.startsWith(`${leaf.path}/`))
  }
  return child.path === target || target.startsWith(`${child.path}/`)
}

/** 收起态高亮：当前路由落在该目录任一子页时，目录图标标为选中。 */
function dirContainsCurrent(dir: NavDir) {
  return dir.children.some((item) => childMatchesPath(item, route.path))
}

watch(
  () => route.path,
  (path) => {
    const next = { ...directoryOpen.value }
    for (const dir of directories.value) {
      if (!dir.flat && dir.children.some((item) => childMatchesPath(item, path))) {
        next[dir.id] = true
      }
    }
    directoryOpen.value = next
  },
  { immediate: true }
)

/** query 收成单个字符串。数组取第一项。 */
function queryText(raw: LocationQueryValue | LocationQueryValue[] | undefined) {
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value : ''
}

/**
 * trial=1 时打开抽屉。详情用路径上的 id，列表用 ?agentId=。
 * 已经为同一个 Agent 打开时不再覆盖，避免把详情传入的名称冲掉。
 */
function agentIdForTrial() {
  const matched = route.path.match(/^\/agent\/list\/(\d+)$/)
  if (matched) {
    const parsed = Number(matched[1])
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  }
  if (route.path === '/agent/list') {
    const parsed = Number(queryText(route.query.agentId))
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
  }
  return undefined
}

watch(
  () => route.fullPath,
  () => {
    if (queryText(route.query.trial) !== '1') {
      return
    }
    if (!user.hasPerm('runtime:trial:run') && !user.hasPerm('runtime:trial:list')) {
      return
    }
    const agentId = agentIdForTrial()
    if (!agentId || (trialOpen.value && trialTarget.value.agentId === agentId)) {
      return
    }
    openAgentTrial({ agentId })
  },
  { immediate: true }
)

/** 关掉抽屉时拿掉 trial，避免刷新又弹开。列表上的 agentId 只服务于这次试跑。 */
watch(trialOpen, (opened) => {
  if (opened || queryText(route.query.trial) !== '1') {
    return
  }
  const next: LocationQuery = { ...route.query }
  delete next.trial
  if (route.path === '/agent/list') {
    delete next.agentId
  }
  router.replace({ path: route.path, query: next })
})

function toggleCollapse() {
  // 工作台只切换本页临时态：不写 localStorage，离开后回到用户的全局偏好
  if (onWorkspace.value) {
    workspaceExpanded.value = !workspaceExpanded.value
    return
  }
  collapsedGlobal.value = !collapsedGlobal.value
  localStorage.setItem(COLLAPSE_KEY, collapsedGlobal.value ? '1' : '0')
}

/**
 * 侧栏底部用户下拉命令（原顶栏整份迁入，菜单项不丢）。
 *
 * @param command 菜单项标识
 */
async function onAccountCommand(command: string | number) {
  if (command === 'profile') {
    profileOpen.value = true
    return
  }
  if (command !== 'logout') {
    return
  }
  await user.logout()
  await router.replace('/login')
}
</script>

<style scoped>
.shell {
  display: flex;
  flex-direction: row;
  height: 100vh;
  overflow: hidden;
  background: var(--ah-bg);
}

.brand-zone {
  display: flex;
  align-items: center;
  flex: none;
  height: 60px;
  padding: 0 16px;
  border-bottom: 1px solid var(--ah-border-soft, #f2f6fc);
  box-sizing: border-box;
}

.brand {
  min-width: 0;
}

.dir-btn,
.collapse-btn {
  border: 0;
  background: transparent;
  color: var(--ah-text-secondary);
  font: inherit;
  cursor: pointer;
}

.aside {
  display: flex;
  flex-direction: column;
  width: var(--ah-sidebar);
  height: 100vh;
  flex: none;
  background: #fff;
  border-right: 1px solid var(--ah-border);
  transition: width 0.3s ease;
}

.aside.is-collapsed {
  width: var(--ah-sidebar-collapsed);
}

/*
 * 工作流编辑专注模式：左侧主导航宽度归零（沿用 .aside 的宽度过渡），
 * 主区留白同步压缩，让画布贴着窗口边缘。
 */
.shell.is-editor-focus .aside {
  width: 0;
  overflow: hidden;
  border-right-color: transparent;
}

.shell.is-editor-focus .main {
  padding: 8px 10px;
  scrollbar-gutter: auto;
}

.nav {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 12px 0;
}

.dir-btn,
.nav-link {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  margin: 2px 12px;
  padding: 0 12px;
  border-radius: var(--ah-radius-md);
  color: #4b5563;
  text-decoration: none;
}

.dir-ref {
  width: 100%;
}

.dir-btn,
.nav-top {
  width: calc(100% - 24px);
  font-weight: 600;
}

/* 无折叠分组：略小、浅灰、加粗，不可点 */
.dir-heading {
  display: flex;
  align-items: center;
  height: 28px;
  margin: 10px 12px 2px;
  padding: 0 12px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  pointer-events: none;
  user-select: none;
}

.dir-heading-nested {
  margin-top: 6px;
  margin-left: 28px;
  padding-left: 12px;
}

.children.is-flat .nav-link {
  margin-left: 12px;
}

.caret {
  margin-left: auto;
  font-size: 12px;
  color: var(--ah-text-muted);
}

.dir-btn:hover,
.nav-link:hover {
  background: #f3f4f6;
}

.dir-btn.is-current {
  background: var(--ah-primary);
  color: #fff;
}

.children .nav-link {
  margin-left: 28px;
  font-weight: 400;
}

.nav-link.router-link-active,
.nav-link.is-current {
  background: var(--ah-primary);
  color: #fff;
}

.foot {
  display: flex;
  flex: none;
  width: 100%;
  padding: 0;
  border-top: 1px solid var(--ah-border-soft, #f2f6fc);
}

/* 单行：用户区占满左侧，折叠按钮钉在最右 */
.account-row {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  height: 64px;
  padding: 0 8px 0 10px;
  box-sizing: border-box;
}

.account-dropdown {
  flex: 1;
  min-width: 0;
}

.foot :deep(.account-dropdown.el-dropdown) {
  display: block;
  width: 100%;
}

/* 用户区：头像 → 名称 → 下拉箭头；箭头外侧紧挨收起按钮 */
.account {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 48px;
  min-width: 0;
  margin: 0;
  padding: 0 6px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
  box-sizing: border-box;
}

.account:hover {
  background: #f5f6fa;
}

.avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  overflow: hidden;
  flex: none;
  border-radius: 50%;
  background: #e4e2ff;
  color: var(--ah-primary-hover);
  font-size: 13px;
  font-weight: 650;
}

.avatar.has-photo {
  background: transparent;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.account-caret {
  flex: none;
  color: var(--ah-text-secondary);
  font-size: 12px;
}

.account-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
  text-align: left;
}

.user-name {
  overflow: hidden;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-tag {
  overflow: hidden;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 最右侧收起/展开：只出图标，不占整行文案 */
.collapse-btn {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: 8px;
}

.collapse-btn:hover {
  background: #f3f4f6;
  color: var(--ah-text);
}

.collapse-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}

/* 右侧列：页签条定高，主区吃满剩余高度并独立滚动；折叠时宽度随 flex 平滑让位 */
.content {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
  height: 100vh;
  transition: width 0.3s ease;
}

.main {
  flex: 1;
  min-width: 0;
  overflow: auto;
  /* 滚动条始终占位，避免有无滚动条时主区宽度抖动导致卡片换行错位 */
  scrollbar-gutter: stable;
  /* 弹窗锁滚另见 styles/index.css */
  padding: 16px 16px 20px;
  background: #f5f7fa;
}

/*
 * 整屏页面（工作台）：去掉主区留白、滚动与外层滚动条占位，
 * 高度与滚动全部交给页面内部（左会话 / 右对话各自滚），避免出现双滚动条。
 */
.main.is-flush {
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
  scrollbar-gutter: auto;
}

.is-collapsed .nav-label,
.is-collapsed .caret,
.is-collapsed .account-meta,
.is-collapsed .account-caret {
  display: none;
}

/* 收起态：品牌区只居中角标 */
.is-collapsed .brand-zone {
  justify-content: center;
  padding: 0 8px;
}

/* 收起态：顶层入口 / 可折叠目录 / 不折叠分组下的子菜单图标居中 */
.is-collapsed .dir-ref {
  display: flex;
  justify-content: center;
  width: 100%;
}

.is-collapsed .dir-btn,
.is-collapsed .nav-link.nav-top,
.is-collapsed .children .nav-link {
  justify-content: center;
  width: calc(100% - 16px);
  margin-left: 8px;
  margin-right: 8px;
  padding: 0;
}

/* 不折叠分组标题（含嵌套分组标题）收起时隐藏，子链仍保留 */
.is-collapsed .dir-heading {
  display: none;
}

/*
 * 收起态 64px：头像与展开按钮纵向叠放，避免并排挤爆。
 * 上头像（仍可下拉退出），下展开图标。
 */
.is-collapsed .account-row {
  flex-direction: column;
  justify-content: center;
  gap: 2px;
  height: auto;
  min-height: 64px;
  padding: 8px 0;
}

.is-collapsed .account-dropdown {
  flex: none;
  width: 100%;
}

.is-collapsed .account {
  justify-content: center;
  width: 100%;
  height: 40px;
  padding: 0;
}

.is-collapsed .collapse-btn {
  width: 32px;
  height: 32px;
}
</style>

<!-- 飞出层挂到 body，需非 scoped -->
<style>
.aside-flyout.el-popper {
  padding: 6px;
  border: 1px solid var(--ah-border, #e5e7eb);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
}

.aside-flyout .flyout-title {
  padding: 6px 10px 8px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.aside-flyout .flyout-group-title {
  padding: 8px 10px 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
  font-weight: 700;
}

.aside-flyout .flyout-link {
  display: block;
  padding: 8px 10px;
  border-radius: 8px;
  color: #4b5563;
  font-size: 13px;
  text-decoration: none;
  line-height: 1.3;
}

.aside-flyout .flyout-link:hover {
  background: #f3f4f6;
  color: var(--ah-text, #111827);
}

.aside-flyout .flyout-link.is-current {
  background: var(--ah-primary, #635bff);
  color: #fff;
}
</style>
