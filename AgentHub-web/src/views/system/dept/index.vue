<template>
  <div class="page">
    <!-- 本页无筛选：根级新建上移到页头，原空 filter-bar 一并移除 -->
    <PageHeader title="部门管理" description="按部门树组织平台用户。路径由服务端维护，形如 0,1,3。">
      <template #extra>
        <el-button v-if="user.hasPerm('system:dept:add')" type="primary" @click="openCreate(0)">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建部门
        </el-button>
      </template>
    </PageHeader>
    <el-card class="table-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        :expand-row-keys="expandedKeys"
        empty-text="没有部门"
        @expand-change="onExpandChange"
      >
        <!-- 树形缩进列保持左对齐，其余居中 -->
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="path" label="路径" min-width="180" align="center" show-overflow-tooltip />
        <el-table-column prop="phone" label="电话" min-width="140" align="center" />
        <el-table-column prop="sort" label="排序" min-width="80" align="center" />
        <el-table-column label="状态" min-width="90" align="center">
          <template #default="{ row }">{{ row.status === 1 ? '正常' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="220" align="center">
          <template #default="{ row }">
            <el-button v-if="user.hasPerm('system:dept:add')" link type="primary" @click="openCreate(row.id)">新增</el-button>
            <el-button v-if="user.hasPerm('system:dept:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="user.hasPerm('system:dept:remove')" link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editor.open" :title="editor.form.id ? '编辑部门' : '新增部门'" width="480px">
      <el-form label-width="72px">
        <!-- 上级：树形下拉，可回显当前父节点；编辑时禁选自己与下级，避免成环 -->
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
        <el-form-item label="电话"><el-input v-model="editor.form.phone" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="editor.form.sort" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editor.form.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <!-- 编辑态未改任何字段时取消 / 保存都不可点；新增态恒可点 -->
        <el-button :disabled="!canSaveDept" @click="editor.open = false">取消</el-button>
        <el-button type="primary" :disabled="!canSaveDept" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'SystemDept' })
/**
 * 部门树。所属域：admin。
 * path 由后端按主键前缀维护（顶层从虚拟根 0 开始，逗号分隔，如 0,1,3），这里只展示。
 * 新增/修改都提供「上级」树形下拉并可回显；默认不展开，增删改后只刷新数据并保留展开行。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createDept, deleteDept, deptTree, updateDept } from '@/api/dept'
import PageHeader from '@/components/PageHeader.vue'
import { useObjectDirty } from '@/composables/useFormDirty'
import { useUserStore } from '@/stores/user'
import type { DeptRow } from '@/types'

/** 虚拟根选项 id：提交时还原为 0，表示顶级部门 */
const ROOT_OPTION_ID = -1

const user = useUserStore()
const loading = ref(false)
const rows = ref<DeptRow[]>([])
/** 受控展开键（与 row-key=id 同型）；刷新树时不清空。 */
const expandedKeys = ref<number[]>([])
const editor = reactive({
  open: false,
  form: { id: 0, parentId: ROOT_OPTION_ID, name: '', phone: '', sort: 0, status: 1 }
})

/**
 * 部门编辑弹窗的脏检查。
 * id 只是「新增 / 编辑」判定，不参与比较；其余均为填写 / 选择类字段。
 */
const editorDirty = useObjectDirty(() => editor.form, ['id'])
/** 取消 / 保存是否可点：新增恒可点，编辑要求有改动 */
const canSaveDept = computed(() => !editor.form.id || editorDirty.isDirty.value)

/**
 * 上级下拉数据：包一层虚拟「顶级」根，便于把部门移回根层。
 * 编辑时把自己及全部下级标为禁选，避免提交后被后端判环。
 */
const parentOptions = computed(() => {
  const blocked = new Set<number>()
  if (editor.form.id) {
    const self = findNode(rows.value, editor.form.id)
    if (self) {
      markSubtree(self, blocked)
    }
  }
  return [{ id: ROOT_OPTION_ID, name: '顶级（无上级）', disabled: false, children: toOptions(rows.value, blocked) }]
})

/**
 * 在部门树里按 id 找节点。
 *
 * @param nodes 树
 * @param id    目标 id
 * @return 命中节点，没有则为 undefined
 */
function findNode(nodes: DeptRow[], id: number): DeptRow | undefined {
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
 * 把节点自己和全部下级登记为禁选。
 *
 * @param node    起点
 * @param blocked 禁选 id 集合
 */
function markSubtree(node: DeptRow, blocked: Set<number>) {
  blocked.add(node.id)
  for (const child of node.children || []) {
    markSubtree(child, blocked)
  }
}

/**
 * 树转下拉选项。
 *
 * @param nodes   树
 * @param blocked 禁选 id 集合
 * @return el-tree-select 可用的选项
 */
function toOptions(nodes: DeptRow[], blocked: Set<number>): Array<Record<string, unknown>> {
  return nodes.map((node) => ({
    id: node.id,
    name: node.name,
    disabled: blocked.has(node.id),
    children: node.children?.length ? toOptions(node.children, blocked) : undefined
  }))
}

/** 仅替换树数据，不改 expandedKeys。 */
async function load() {
  loading.value = true
  try {
    rows.value = (await deptTree()).data.data
  } finally {
    loading.value = false
  }
}

/**
 * 记录用户手动展开/收起。
 *
 * @param row 当前行
 * @param expanded 是否展开
 */
function onExpandChange(row: DeptRow, expanded: boolean) {
  const key = row.id
  if (expanded) {
    if (!expandedKeys.value.includes(key)) {
      expandedKeys.value = [...expandedKeys.value, key]
    }
    return
  }
  expandedKeys.value = expandedKeys.value.filter((item) => item !== key)
}

/**
 * 打开新增弹窗。
 *
 * @param parentId 预置上级 id，0 表示顶级（映射为虚拟根选项）
 */
function openCreate(parentId: number) {
  editor.form = {
    id: 0,
    parentId: parentId === 0 ? ROOT_OPTION_ID : parentId,
    name: '',
    phone: '',
    sort: 0,
    status: 1
  }
  editor.open = true
  // 新建恒可点，仍定一次基线避免残留脏标记
  editorDirty.markClean()
}

/**
 * 打开编辑弹窗，上级按现有 parentId 回显；0 显示为虚拟「顶级」。
 *
 * @param row 当前部门
 */
function openEdit(row: DeptRow) {
  const parentId = row.parentId ?? 0
  editor.form = {
    id: row.id,
    parentId: parentId === 0 ? ROOT_OPTION_ID : parentId,
    name: row.name,
    phone: row.phone || '',
    sort: row.sort,
    status: row.status
  }
  editor.open = true
  // 回填完成即定为干净基线：打开弹窗不该显示可保存
  editorDirty.markClean()
}

/** 保存：虚拟根选项还原为 0；成功提示后刷新树。 */
async function save() {
  const parentId = Number(editor.form.parentId) === ROOT_OPTION_ID ? 0 : Number(editor.form.parentId) || 0
  const payload = { ...editor.form, parentId }
  try {
    if (editor.form.id) {
      await updateDept(editor.form.id, payload)
    } else {
      await createDept(payload)
    }
    ElMessage.success('已保存')
    editor.open = false
    await load()
  } catch {
    // 请求层已提示（如「不能把部门移动到自己的下级」），保留弹窗便于修正
  }
}

async function onDelete(row: DeptRow) {
  await ElMessageBox.confirm(`删除部门「${row.name}」？`, '删除')
  await deleteDept(row.id)
  expandedKeys.value = expandedKeys.value.filter((item) => item !== row.id)
  await load()
}

onMounted(load)
</script>
