<template>
  <div class="page">
    <PageHeader title="工作台" description="从这里进入已授权的页面。" />
    <div v-if="entries.length" class="grid">
      <router-link v-for="item in entries" :key="item.path" :to="item.path" class="entry">
        <span class="entry-mark">
          <el-icon><component :is="iconOf(item.icon)" /></el-icon>
        </span>
        <span class="entry-text">
          <strong :title="item.name">{{ item.name }}</strong>
          <em :title="hintOf(item.path)">{{ hintOf(item.path) }}</em>
        </span>
      </router-link>
    </div>
    <el-card v-else class="table-card" shadow="never">当前账号没有管理菜单。</el-card>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'Workspace' })
/**
 * 工作台。所属域：前端入口（登录默认落地页）。
 * 只给已有管理页做入口，不请求新接口，也不展示看板数据。
 */
import { computed } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import { resolveMenuIcon } from '@/components/menu/menuIcons'
import { TRIAL_MENU_PATH } from '@/composables/agentTrial'
import { useUserStore } from '@/stores/user'
import type { MenuNode } from '@/types'

const user = useUserStore()

const hints: Record<string, string> = {
  '/system/user': '账号、部门和角色',
  '/system/role': '菜单权限和数据范围',
  '/system/menu': '目录、无折叠分组、菜单和按钮',
  '/system/dept': '部门树',
  '/system/app': '调用方应用和密钥',
  '/system/login-log': '登录成功与失败',
  '/system/oper-log': '控制台操作摘要',
  '/access/models': '供应商、凭证和模型实例',
  '/access/tools': '内置与 MCP 工具源，同步后手动启用',
  '/access/skills': '可复用的 Skill 正文',
  '/agent/list': '配置 ReAct / Plan-Execute / Router 及绑定'
}

const entries = computed(() => flatten(user.profile?.menus ?? []))

/** 与侧栏同一套叶子菜单，避免工作台多出未授权入口。 */
function flatten(nodes: MenuNode[]): MenuNode[] {
  const list: MenuNode[] = []
  for (const node of nodes) {
    if (node.visible === 0) {
      continue
    }
    const children = (node.children || []).filter((item) => item.visible !== 0 && item.path !== TRIAL_MENU_PATH)
    if (children.length) {
      list.push(...flatten(children))
    } else if (node.path && node.path !== '/system' && node.path !== TRIAL_MENU_PATH) {
      list.push(node)
    }
  }
  return list
}

/** 图标统一走内置映射，未登记名称回退 Setting。 */
function iconOf(name?: string) {
  return resolveMenuIcon(name)
}

/**
 * 每张卡片的一行说明；未登记路径给通用文案。
 *
 * @param path 菜单路由
 */
function hintOf(path: string) {
  return hints[path] || '打开页面'
}
</script>

<style scoped>
/* 卡片等高：统一最小高与固定内边距，长标题单行省略，避免同一行的卡片高低不齐 */
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  grid-auto-rows: 1fr;
  gap: 12px;
}

.entry {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 72px;
  padding: 16px;
  background: var(--ah-surface);
  border: 1px solid var(--ah-border);
  border-radius: var(--ah-radius-lg);
  box-shadow: var(--ah-shadow);
  color: inherit;
  text-decoration: none;
  overflow: hidden;
}

.entry:hover {
  border-color: #bfdbfe;
}

.entry-mark {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 10px;
  background: #eff4fe;
  color: var(--ah-primary);
}

/* 文案区可收缩，省略号才生效 */
.entry-text {
  flex: 1;
  min-width: 0;
}

.entry strong,
.entry em {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.entry em {
  margin-top: 4px;
  color: var(--ah-text-secondary);
  font-style: normal;
  font-size: 12px;
}
</style>
