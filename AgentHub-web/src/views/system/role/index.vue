<template>
  <div class="page">
    <!-- 页头承载标题/说明 + 主操作「新建角色」；筛选条只留查询/重置 -->
    <PageHeader title="角色管理" description="配置角色、菜单权限和数据范围。">
      <template #extra>
        <el-button v-if="user.hasPerm('system:role:add')" type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建角色
        </el-button>
      </template>
    </PageHeader>
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="onSearch">
        <el-form-item label="编码">
          <el-input v-model="query.code" class="filter-input" clearable />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="query.name" class="filter-input" clearable />
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" @click="onSearch">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" @click="onReset">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </div>
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="rows" empty-text="没有角色">
        <el-table-column prop="code" label="编码" min-width="160" show-overflow-tooltip align="center" />
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip align="center" />
        <el-table-column prop="dataScopeLabel" label="数据范围" min-width="160" align="center" />
        <el-table-column label="状态" min-width="100" align="center">
          <template #default="{ row }">{{ row.status === 1 ? '正常' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="280" align="center">
          <template #default="{ row }">
            <el-button v-if="user.hasPerm('system:role:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="user.hasPerm('system:role:menu')" link type="primary" @click="openMenus(row)">菜单</el-button>
            <el-button v-if="user.hasPerm('system:role:edit')" link type="primary" @click="openScope(row)">数据范围</el-button>
            <el-button v-if="user.hasPerm('system:role:remove')" link type="danger" @click="onDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <TablePager
        v-model:current="query.current"
        v-model:size="query.size"
        :total="total"
        @change="load"
      />
    </el-card>

    <el-dialog v-model="editor.open" :title="editor.id ? '编辑角色' : '新增角色'" width="460px">
      <el-form ref="formRef" :model="editor.form" :rules="rules" label-width="88px">
        <el-form-item label="编码" prop="code">
          <el-input v-model="editor.form.code" :disabled="!!editor.id" placeholder="字母开头，如 developer" />
        </el-form-item>
        <el-form-item label="名称" prop="name"><el-input v-model="editor.form.name" /></el-form-item>
        <el-form-item label="数据范围">
          <el-select v-model="editor.form.dataScope" style="width: 100%">
            <el-option v-for="item in scopes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="editor.form.sort" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editor.form.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="editor.form.remark" type="textarea" /></el-form-item>
      </el-form>
      <template #footer>
        <!-- 编辑态未改任何字段时取消 / 保存都不可点；新增态恒可点 -->
        <el-button :disabled="!canSaveRole" @click="editor.open = false">取消</el-button>
        <el-button type="primary" :disabled="!canSaveRole" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="menuDialog.open" title="分配菜单" width="420px">
      <el-tree ref="menuTreeRef" :data="menus" node-key="id" show-checkbox default-expand-all :props="{ label: 'name', children: 'children' }" />
      <template #footer>
        <el-button @click="menuDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveMenus">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scopeDialog.open" title="数据范围" width="460px">
      <el-form label-width="88px">
        <el-form-item label="范围">
          <el-select v-model="scopeDialog.dataScope" style="width: 100%">
            <el-option v-for="item in scopes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="scopeDialog.dataScope === 5" label="部门">
          <el-tree-select
            v-model="scopeDialog.deptIds"
            :data="depts"
            multiple
            check-strictly
            show-checkbox
            :props="{ label: 'name', value: 'id', children: 'children' }"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scopeDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveScope">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'SystemRole' })
/**
 * 角色管理。所属域：admin。
 * 数据范围：1 本人，2 本部门，3 本部门及以下，4 全部，5 自定义。筛选条布局与用户管理一致。
 */
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type ElTree } from 'element-plus'
import { assignRoleMenus, createRole, deleteRole, getRole, pageRoles, updateRole, updateRoleScope } from '@/api/role'
import { menuTree } from '@/api/menu'
import { deptTree } from '@/api/dept'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useObjectDirty } from '@/composables/useFormDirty'
import { useUserStore } from '@/stores/user'
import type { DeptRow, MenuRow, RoleRow } from '@/types'

const user = useUserStore()
const loading = ref(false)
const rows = ref<RoleRow[]>([])
const total = ref(0)
const menus = ref<MenuRow[]>([])
const depts = ref<DeptRow[]>([])
const menuTreeRef = ref<InstanceType<typeof ElTree>>()
const query = reactive({ current: 1, size: 10, code: '', name: '' })
const scopes = [
  { value: 1, label: '仅本人' },
  { value: 2, label: '本部门' },
  { value: 3, label: '本部门及以下' },
  { value: 4, label: '全部' },
  { value: 5, label: '自定义部门' }
]
const editor = reactive({
  open: false,
  id: 0,
  form: { code: '', name: '', dataScope: 1, sort: 0, status: 1, remark: '' }
})
/** 表单实例：名称唯一以前端先挡一层，最终以后端校验为准 */
const formRef = ref<FormInstance>()
/** 编码与名称必填；名称重复由后端返回「角色名称已存在」 */
const rules: FormRules = {
  code: [{ required: true, message: '请填写角色编码', trigger: 'blur' }],
  name: [{ required: true, message: '请填写角色名称', trigger: 'blur' }]
}
const menuDialog = reactive({ open: false, id: 0 })
const scopeDialog = reactive({ open: false, id: 0, dataScope: 1, deptIds: [] as number[] })

/**
 * 角色编辑弹窗的脏检查（编码 / 名称 / 数据范围 / 排序 / 状态 / 备注均为填写或选择类字段）。
 * 取消 / 保存据此置灰：编辑态未改动时两者都点不动。
 */
const editorDirty = useObjectDirty(() => editor.form)
/** 取消 / 保存是否可点：新增恒可点，编辑要求有改动 */
const canSaveRole = computed(() => !editor.id || editorDirty.isDirty.value)

async function load() {
  loading.value = true
  try {
    const { data } = await pageRoles({
      current: query.current,
      size: query.size,
      code: query.code || undefined,
      name: query.name || undefined
    })
    rows.value = data.data.records
    total.value = data.data.total
  } finally {
    loading.value = false
  }
}

function onSearch() {
  query.current = 1
  void load()
}

function onReset() {
  query.code = ''
  query.name = ''
  query.current = 1
  void load()
}

function openCreate() {
  editor.id = 0
  editor.form = { code: '', name: '', dataScope: 1, sort: 0, status: 1, remark: '' }
  editor.open = true
  // 新建恒可点，仍定一次基线避免残留脏标记
  editorDirty.markClean()
}

function openEdit(row: RoleRow) {
  editor.id = row.id
  editor.form = { code: row.code, name: row.name, dataScope: row.dataScope, sort: row.sort, status: row.status, remark: row.remark || '' }
  editor.open = true
  // 回填完成即定为干净基线：打开弹窗不该显示可保存
  editorDirty.markClean()
}

async function save() {
  // 先跑前端必填校验，再提交；名称重复由后端统一拦截
  const valid = await formRef.value?.validate().catch(() => false)
  if (valid === false) {
    return
  }
  const payload = { ...editor.form, name: editor.form.name.trim() }
  try {
    if (editor.id) {
      await updateRole(editor.id, payload)
    } else {
      await createRole(payload)
    }
    ElMessage.success('已保存')
    editor.open = false
    await load()
  } catch {
    // 请求层已提示（如「角色名称已存在」），保持弹窗开启便于修改
  }
}

async function openMenus(row: RoleRow) {
  const { data } = await getRole(row.id)
  menuDialog.id = row.id
  menuDialog.open = true
  await nextTick()
  menuTreeRef.value?.setCheckedKeys(data.data.menuIds || [])
}

async function saveMenus() {
  const keys = (menuTreeRef.value?.getCheckedKeys(false) || []) as number[]
  const half = (menuTreeRef.value?.getHalfCheckedKeys() || []) as number[]
  await assignRoleMenus(menuDialog.id, [...keys, ...half])
  menuDialog.open = false
}

async function openScope(row: RoleRow) {
  const { data } = await getRole(row.id)
  scopeDialog.id = row.id
  scopeDialog.dataScope = data.data.dataScope
  scopeDialog.deptIds = data.data.customDeptIds || []
  scopeDialog.open = true
}

async function saveScope() {
  await updateRoleScope(scopeDialog.id, scopeDialog.dataScope, scopeDialog.deptIds)
  scopeDialog.open = false
  await load()
}

async function onDelete(row: RoleRow) {
  await ElMessageBox.confirm(`删除角色 ${row.name}？`, '删除')
  await deleteRole(row.id)
  await load()
}

onMounted(async () => {
  await load()
  if (user.hasPerm('system:menu:list')) {
    menus.value = (await menuTree()).data.data
  }
  if (user.hasPerm('system:dept:list')) {
    depts.value = (await deptTree()).data.data
  }
})
</script>

