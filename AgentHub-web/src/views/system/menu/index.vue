<template>
  <div class="page">
    <!-- 本页无筛选：根级新建上移到页头，原空 filter-bar 一并移除 -->
    <PageHeader title="菜单管理" description="维护目录、无折叠分组、菜单和按钮权限码。">
      <template #extra>
        <el-button v-if="user.hasPerm('system:menu:add')" type="primary" @click="openCreate(0)">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建菜单
        </el-button>
      </template>
    </PageHeader>
    <el-card class="table-card" shadow="never">
      <!-- lazy：表格只挂当前层。受控 expand-row-keys 会在每次展开时按整棵树重建，这里不绑 -->
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="tableRows"
        row-key="id"
        lazy
        :load="loadChildren"
        :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
        empty-text="没有菜单"
        @expand-change="onExpandChange"
      >
        <!-- 树形缩进列保持左对齐，其余居中 -->
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column label="类型" min-width="120" align="center">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column prop="path" label="路由" min-width="160" align="center" />
        <el-table-column prop="permission" label="权限码" min-width="180" align="center" />
        <el-table-column prop="sort" label="排序" min-width="80" align="center" />
        <el-table-column label="状态" min-width="90" align="center">
          <template #default="{ row }">
            <StatusSwitch
              :status="row.status"
              :disabled="!user.hasPerm('system:menu:edit')"
              :commit="(status) => commitMenuStatus(row, status)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="220" align="center">
          <template #default="{ row }">
            <el-button v-if="user.hasPerm('system:menu:add')" link type="primary" @click="openCreate(row.id)">新增</el-button>
            <el-button v-if="user.hasPerm('system:menu:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="user.hasPerm('system:menu:remove')" link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editor.open" :title="editor.form.id ? '编辑菜单' : '新增菜单'" width="560px">
      <el-form label-width="88px">
        <el-form-item label="类型">
          <el-radio-group v-model="editor.form.type">
            <el-radio :value="1">目录</el-radio>
            <el-radio :value="4">目录（无折叠）</el-radio>
            <el-radio :value="2">菜单</el-radio>
            <el-radio :value="3">按钮</el-radio>
          </el-radio-group>
        </el-form-item>
        <!-- 上级：树形下拉，可回显当前父节点；编辑时禁选自己及子孙 -->
        <el-form-item label="上级">
          <el-tree-select
            v-model="editor.form.parentId"
            :data="parentOptions"
            :props="{ label: 'name', value: 'id', children: 'children', disabled: 'disabled' }"
            check-strictly
            default-expand-all
            placeholder="顶级"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="名称"><el-input v-model="editor.form.name" /></el-form-item>
        <!-- 目录 / 目录（无折叠）字段一致；仅按钮隐藏路由与组件 -->
        <el-form-item v-if="!isButtonType" label="路由"><el-input v-model="editor.form.path" /></el-form-item>
        <el-form-item v-if="!isButtonType" label="组件"><el-input v-model="editor.form.component" /></el-form-item>
        <el-form-item label="权限码"><el-input v-model="editor.form.permission" /></el-form-item>
        <!-- 图标改为内置选择器，仍是存名称字符串，侧栏按同名映射渲染 -->
        <el-form-item v-if="!isButtonType" label="图标">
          <MenuIconPicker v-model="editor.form.icon" />
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="editor.form.sort" :min="0" /></el-form-item>
        <el-form-item label="显示">
          <el-radio-group v-model="editor.form.visible">
            <el-radio :value="1">显示</el-radio>
            <el-radio :value="0">隐藏</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <!-- 编辑态未改任何字段时取消 / 保存都不可点；新增态恒可点 -->
        <el-button :disabled="!canSaveMenu" @click="editor.open = false">取消</el-button>
        <el-button type="primary" :disabled="!canSaveMenu" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'SystemMenu' })
/**
 * 菜单树管理。所属域：admin。
 * 类型：1 可折叠目录、2 菜单、3 按钮、4 无折叠分组。
 * 默认不展开；增删改后只刷新数据，保留当前展开行。
 * 表格按层懒加载：整棵树一次取回，展开只渲染下一层，避免每次点击重建全部按钮行。
 * 启停在列表开关上完成，新建默认启用；表单不再提供状态。
 * 上级用树形下拉（含「顶级」根选项）以便回显；编辑时自己与子孙禁选，避免把节点挂到自己的下级。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type TableInstance } from 'element-plus'
import { createMenu, deleteMenu, menuTree, updateMenu } from '@/api/menu'
import StatusSwitch from '@/components/admin/StatusSwitch.vue'
import PageHeader from '@/components/PageHeader.vue'
import MenuIconPicker from '@/components/menu/MenuIconPicker.vue'
import { patchTreeNode, useLazyTreeTable } from '@/composables/lazyTreeTable'
import { useObjectDirty } from '@/composables/useFormDirty'
import { useUserStore } from '@/stores/user'
import type { MenuRow } from '@/types'

/** 无折叠分组标题，侧栏只渲染纯文本 */
const TYPE_DIRECTORY_FLAT = 4
/** 虚拟根选项 id：提交时转成 0，表示顶级 */
const ROOT_OPTION_ID = -1

const user = useUserStore()
const loading = ref(false)
/** 完整树在 source，表格只挂已展开层 */
const {
  source: menuSource,
  tableRows,
  replace: replaceMenuTree,
  loadChildren,
  onExpandChange,
  forgetExpanded,
  restore: restoreMenuTree
} = useLazyTreeTable<MenuRow>()
const tableRef = ref<TableInstance>()
const editor = reactive({ open: false, form: emptyForm(0) })

/**
 * 菜单编辑弹窗的脏检查。
 * id 只用于「新增 / 编辑」判定，不参与比较；其余均为填写 / 选择类字段。
 */
const editorDirty = useObjectDirty(() => editor.form, ['id'])
/** 取消 / 保存是否可点：新增恒可点，编辑要求有改动 */
const canSaveMenu = computed(() => !editor.form.id || editorDirty.isDirty.value)

/** 按钮不填路由/组件/图标；目录与目录（无折叠）字段保持一致。 */
const isButtonType = computed(() => Number(editor.form.type) === 3)

/**
 * 上级下拉数据：包一层虚拟「顶级」根，方便把节点移回根层。
 * 编辑时把自己及全部子孙标记 disabled，从交互上就避免提交后被后端判环。
 */
const parentOptions = computed(() => {
  const blocked = new Set<number>()
  if (editor.form.id) {
    const self = findNode(menuSource.value, editor.form.id)
    if (self) {
      markSubtree(self, blocked)
    }
  }
  return [{ id: ROOT_OPTION_ID, name: '顶级（根目录）', disabled: false, children: toOptions(menuSource.value, blocked) }]
})

/**
 * 在菜单树里按 id 找节点。
 *
 * @param nodes 树
 * @param id    目标 id
 * @return 命中节点，没有则为 undefined
 */
function findNode(nodes: MenuRow[], id: number): MenuRow | undefined {
  for (const node of nodes) {
    if (node.id === id) {
      return node
    }
    const hit = node.children?.length ? findNode(node.children, id) : undefined
    if (hit) {
      return hit
    }
  }
  return undefined
}

/**
 * 把节点自己和全部下级登记为禁选（移动到自己子树会成环）。
 *
 * @param node    起点
 * @param blocked 禁选 id 集合
 */
function markSubtree(node: MenuRow, blocked: Set<number>) {
  blocked.add(node.id)
  for (const child of node.children || []) {
    markSubtree(child, blocked)
  }
}

/**
 * 树转下拉选项：标签带上类型前缀，便于区分目录和按钮。
 *
 * @param nodes   树
 * @param blocked 禁选 id 集合
 * @return el-tree-select 可用的选项
 */
function toOptions(nodes: MenuRow[], blocked: Set<number>): Array<Record<string, unknown>> {
  return nodes.map((node) => ({
    id: node.id,
    name: `${typeLabel(Number(node.type))} · ${node.name}`,
    disabled: blocked.has(node.id),
    children: node.children?.length ? toOptions(node.children, blocked) : undefined
  }))
}

function emptyForm(parentId: number) {
  return {
    id: 0,
    parentId,
    type: 2,
    name: '',
    path: '',
    component: '',
    permission: '',
    icon: '',
    sort: 0,
    visible: 1,
    status: 1,
    openType: 0
  }
}

function typeLabel(type: number) {
  if (type === 1) return '目录'
  if (type === TYPE_DIRECTORY_FLAT) return '目录（无折叠）'
  if (type === 3) return '按钮'
  return '菜单'
}

/** 只换树数据。展开记录留在 tree 里，刷新后按层还原。 */
async function load() {
  loading.value = true
  try {
    replaceMenuTree((await menuTree()).data.data || [])
  } finally {
    loading.value = false
  }
  await restoreMenuTree(tableRef.value)
}

/**
 * 列表开关启停。带上当前行的其余字段，避免更新接口把路由、权限码写成空。
 *
 * @param row    表格行（懒加载副本）
 * @param status 1 启用，0 停用
 */
async function commitMenuStatus(row: MenuRow, status: number) {
  const { data } = await updateMenu(row.id, {
    parentId: row.parentId ?? 0,
    type: Number(row.type),
    name: row.name,
    path: row.path ?? null,
    component: row.component ?? null,
    redirect: row.redirect ?? null,
    permission: row.permission ?? null,
    icon: row.icon ?? null,
    sort: row.sort ?? 0,
    visible: row.visible ?? 1,
    status,
    openType: row.openType ?? 0
  })
  const saved = data.data?.status ?? status
  row.status = saved
  patchTreeNode(menuSource.value, row.id, (node) => {
    node.status = saved
  })
}

/**
 * 打开新增弹窗。
 *
 * @param parentId 预置上级 id，0 表示顶级（映射为虚拟根选项，否则下拉无法回显）
 */
function openCreate(parentId: number) {
  const form = emptyForm(parentId)
  form.parentId = parentId === 0 ? ROOT_OPTION_ID : parentId
  editor.form = form
  editor.open = true
  // 新建恒可点，仍定一次基线避免残留脏标记
  editorDirty.markClean()
}

/**
 * 打开编辑。type 统一成数字，避免接口偶发字符串导致单选与显隐错乱。
 * parentId 归一成下拉可识别的值：0 显示成虚拟「顶级」选项。
 */
function openEdit(row: MenuRow) {
  const parentId = row.parentId ?? 0
  editor.form = {
    id: row.id,
    parentId: parentId === 0 ? ROOT_OPTION_ID : parentId,
    type: Number(row.type),
    name: row.name,
    path: row.path || '',
    component: row.component || '',
    permission: row.permission || '',
    icon: row.icon || '',
    sort: row.sort ?? 0,
    visible: row.visible ?? 1,
    status: row.status ?? 1,
    openType: row.openType ?? 0
  }
  editor.open = true
  // 回填完成即定为干净基线：打开弹窗不该显示可保存
  editorDirty.markClean()
}

/**
 * 保存时把虚拟根选项还原成 0；目录与无折叠目录字段规则相同，不额外清空路由。
 */
async function save() {
  const parentId = Number(editor.form.parentId) === ROOT_OPTION_ID ? 0 : Number(editor.form.parentId) || 0
  const payload = {
    ...editor.form,
    parentId,
    type: Number(editor.form.type)
  }
  try {
    if (editor.form.id) {
      await updateMenu(editor.form.id, payload)
    } else {
      await createMenu(payload)
    }
    ElMessage.success('已保存')
    editor.open = false
    await load()
  } catch {
    // 请求层已提示（如「不能把菜单移动到自己的下级」），保留弹窗便于修正
  }
}

async function onDelete(row: MenuRow) {
  await ElMessageBox.confirm(`删除「${row.name}」？`, '删除')
  await deleteMenu(row.id)
  // 已删除节点从展开记录里摘掉，其余展开状态保留
  forgetExpanded(row.id)
  await load()
}

onMounted(load)
</script>
