<template>
  <div class="page">
    <!-- 页头承载标题/说明 + 主操作「新建用户」；筛选条只留查询/重置 -->
    <PageHeader title="用户管理" description="维护平台账号、所属部门和角色。">
      <template #extra>
        <el-button v-if="user.hasPerm('system:user:add')" type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建用户
        </el-button>
      </template>
    </PageHeader>
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="onSearch">
        <el-form-item label="用户名">
          <el-input v-model="query.username" class="filter-input" clearable placeholder="登录名" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" class="filter-select" clearable placeholder="全部">
            <el-option label="正常" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
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
      <el-table v-loading="loading" :data="rows" empty-text="没有用户">
        <!-- 用户名/昵称/部门给足宽度并单行省略，避免中文名换行撑高行高 -->
        <el-table-column prop="username" label="用户名" min-width="150" show-overflow-tooltip align="center" />
        <el-table-column prop="nickname" label="昵称" min-width="140" show-overflow-tooltip align="center" />
        <el-table-column prop="deptName" label="部门" min-width="150" show-overflow-tooltip align="center" />
        <el-table-column label="角色" min-width="220" show-overflow-tooltip align="center">
          <template #default="{ row }">{{ row.roleNames?.join('、') || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '正常' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="280" align="center">
          <template #default="{ row }">
            <el-button v-if="user.hasPerm('system:user:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="user.hasPerm('system:user:role')" link type="primary" @click="openRoles(row)">角色</el-button>
            <el-button v-if="user.hasPerm('system:user:resetPwd')" link type="primary" @click="onResetPwd(row)">重置密码</el-button>
            <el-button v-if="user.hasPerm('system:user:remove')" link type="danger" @click="onDelete(row)">删除</el-button>
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

    <el-dialog v-model="editor.open" :title="editor.id ? '编辑用户' : '新增用户'" width="480px" @closed="editor.open = false">
      <el-form label-width="72px">
        <el-form-item label="用户名">
          <el-input v-model="editor.form.username" :disabled="!!editor.id" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="editor.form.nickname" />
        </el-form-item>
        <el-form-item label="部门">
          <el-tree-select
            v-model="editor.form.deptId"
            :data="depts"
            check-strictly
            clearable
            :props="{ label: 'name', value: 'id', children: 'children' }"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editor.form.email" />
        </el-form-item>
        <el-form-item label="手机">
          <el-input v-model="editor.form.phone" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editor.form.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!editor.id" label="角色">
          <el-select v-model="editor.form.roleIds" multiple style="width: 100%">
            <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <!-- 编辑态未改任何字段时取消 / 保存都不可点；新增态恒可点 -->
        <el-button :disabled="!canSaveUser" @click="editor.open = false">取消</el-button>
        <el-button type="primary" :loading="editor.saving" :disabled="!canSaveUser" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialog.open" title="分配角色" width="420px">
      <el-select v-model="roleDialog.roleIds" multiple style="width: 100%">
        <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
      </el-select>
      <template #footer>
        <el-button @click="roleDialog.open = false">取消</el-button>
        <el-button type="primary" @click="saveRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'SystemUser' })
/**
 * 用户管理。所属域：admin。
 * 查询淡蓝 + 放大镜；重置白底描边 + 刷新图标；新建在页头 #extra + Plus。
 * 新增不传密码，初始口令见 README。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { assignUserRoles, createUser, deleteUser, pageUsers, resetUserPassword, updateUser } from '@/api/user'
import { pageRoles } from '@/api/role'
import { deptTree } from '@/api/dept'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useObjectDirty } from '@/composables/useFormDirty'
import { useUserStore } from '@/stores/user'
import type { DeptRow, RoleRow, UserRow } from '@/types'

const user = useUserStore()
const loading = ref(false)
const rows = ref<UserRow[]>([])
const total = ref(0)
const roles = ref<RoleRow[]>([])
const depts = ref<DeptRow[]>([])
const query = reactive({ current: 1, size: 10, username: '', status: undefined as number | undefined })
const editor = reactive({
  open: false,
  saving: false,
  id: 0,
  form: emptyForm()
})
const roleDialog = reactive({ open: false, userId: 0, roleIds: [] as number[] })

/**
 * 用户编辑弹窗的脏检查。
 * <p>
 * 新建态没有「初始值」可比，按钮恒可点；编辑态要求至少改过一个填写 / 选择类字段。
 * 表单整体替换（editor.form = …）时需要用返回的 markClean 重新定基线，
 * 因此下面 openCreate / openEdit 都会显式调用一次。
 */
const editorDirty = useObjectDirty(() => editor.form)
/** 取消 / 保存是否可点：新增恒可点，编辑要求有改动 */
const canSaveUser = computed(() => !editor.id || editorDirty.isDirty.value || editor.saving)

function emptyForm() {
  return { username: '', nickname: '', deptId: undefined as number | undefined, email: '', phone: '', status: 1, roleIds: [] as number[] }
}

/** 按当前条件从第一页拉取。 */
async function load() {
  loading.value = true
  try {
    const { data } = await pageUsers({
      current: query.current,
      size: query.size,
      username: query.username || undefined,
      status: query.status
    })
    rows.value = data.data.records
    total.value = data.data.total
  } finally {
    loading.value = false
  }
}

/** 点查询：回到第一页再拉。 */
function onSearch() {
  query.current = 1
  void load()
}

/** 清空筛选条件后重新查询。 */
function onReset() {
  query.username = ''
  query.status = undefined
  query.current = 1
  void load()
}

function openCreate() {
  editor.id = 0
  editor.form = emptyForm()
  editor.open = true
  // 新建恒可点，但仍定一次基线，避免后续切编辑态时残留脏标记
  editorDirty.markClean()
}

function openEdit(row: UserRow) {
  editor.id = row.id
  editor.form = {
    username: row.username,
    nickname: row.nickname || '',
    deptId: row.deptId,
    email: row.email || '',
    phone: row.phone || '',
    status: row.status,
    roleIds: []
  }
  editor.open = true
  // 回填完成即定为干净基线：打开弹窗不该显示可保存
  editorDirty.markClean()
}

async function saveUser() {
  editor.saving = true
  try {
    const payload = {
      username: editor.form.username,
      nickname: editor.form.nickname || null,
      deptId: editor.form.deptId || null,
      email: editor.form.email || null,
      phone: editor.form.phone || null,
      status: editor.form.status,
      roleIds: editor.form.roleIds
    }
    if (editor.id) {
      await updateUser(editor.id, payload)
    } else {
      await createUser(payload)
      ElMessage.success('已创建。初始密码为 admin123')
    }
    editor.open = false
    await load()
  } finally {
    editor.saving = false
  }
}

function openRoles(row: UserRow) {
  roleDialog.userId = row.id
  roleDialog.roleIds = [...(row.roleIds || [])]
  roleDialog.open = true
}

async function saveRoles() {
  await assignUserRoles(roleDialog.userId, roleDialog.roleIds)
  roleDialog.open = false
  await load()
}

async function onResetPwd(row: UserRow) {
  await ElMessageBox.confirm(`将 ${row.username} 的密码重置为 admin123？`, '重置密码')
  await resetUserPassword(row.id)
  ElMessage.success('已重置')
}

async function onDelete(row: UserRow) {
  await ElMessageBox.confirm(`删除用户 ${row.username}？`, '删除')
  await deleteUser(row.id)
  await load()
}

onMounted(async () => {
  await load()
  if (user.hasPerm('system:role:list')) {
    const { data } = await pageRoles({ current: 1, size: 100 })
    roles.value = data.data.records
  }
  if (user.hasPerm('system:dept:list')) {
    const { data } = await deptTree()
    depts.value = data.data
  }
})
</script>

