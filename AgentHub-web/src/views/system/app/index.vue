<template>
  <div class="page">
    <!-- 页头承载标题/说明 + 主操作「新建应用」；筛选条只留查询/重置 -->
    <PageHeader title="应用管理" description="管理调用方应用。密钥只在创建或重置后显示一次。">
      <template #extra>
        <el-button v-if="user.hasPerm('system:app:add')" type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建应用
        </el-button>
      </template>
    </PageHeader>
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="onSearch">
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
      <el-table v-loading="loading" :data="rows" empty-text="没有应用">
        <el-table-column prop="name" label="名称" min-width="140" align="center" />
        <el-table-column prop="appKey" label="AppId" min-width="180" align="center" />
        <el-table-column prop="deptName" label="部门" min-width="120" align="center" />
        <el-table-column label="Agent" min-width="140" align="center">
          <template #default="{ row }">{{ row.agentIds?.join(', ') || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="90" align="center">
          <template #default="{ row }">{{ row.status === 1 ? '启用' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="280" align="center">
          <template #default="{ row }">
            <el-button v-if="user.hasPerm('system:app:edit')" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="user.hasPerm('system:app:edit')" link type="primary" @click="openGrant(row)">授权</el-button>
            <el-button v-if="user.hasPerm('system:app:edit')" link type="primary" @click="onResetSecret(row)">重置密钥</el-button>
            <el-button v-if="user.hasPerm('system:app:remove')" link type="danger" @click="onDelete(row)">删除</el-button>
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

    <el-dialog v-model="editor.open" :title="editor.id ? '编辑应用' : '新增应用'" width="460px">
      <el-form label-width="72px">
        <el-form-item label="名称"><el-input v-model="editor.form.name" /></el-form-item>
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
        <el-form-item label="备注"><el-input v-model="editor.form.remark" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editor.form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <!-- 编辑态未改任何字段时取消 / 保存都不可点；新增态恒可点 -->
        <el-button :disabled="!canSaveApp" @click="editor.open = false">取消</el-button>
        <el-button type="primary" :disabled="!canSaveApp" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="grant.open" title="授权 Agent" width="420px">
      <el-input v-model="grant.text" placeholder="Agent 主键，逗号分隔，例如 1,2" />
      <template #footer>
        <el-button @click="grant.open = false">取消</el-button>
        <el-button type="primary" @click="saveGrant">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'SystemApp' })
/**
 * 调用方应用。所属域：admin。
 * 密钥只在创建或重置后的弹窗里出现一次；新建入口在页头 #extra，筛选条只留查询/重置。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { createApp, deleteApp, grantAppAgents, pageApps, resetAppSecret, updateApp } from '@/api/app'
import { deptTree } from '@/api/dept'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import { useObjectDirty } from '@/composables/useFormDirty'
import { useUserStore } from '@/stores/user'
import type { AppRow, DeptRow } from '@/types'

const user = useUserStore()
const loading = ref(false)
const rows = ref<AppRow[]>([])
const total = ref(0)
const depts = ref<DeptRow[]>([])
const query = reactive({ current: 1, size: 10, name: '' })
const editor = reactive({
  open: false,
  id: 0,
  form: { name: '', deptId: undefined as number | undefined, remark: '', status: 1 }
})
const grant = reactive({ open: false, id: 0, text: '' })

/**
 * 应用编辑弹窗的脏检查（名称 / 部门 / 备注 / 状态均为填写或选择类字段）。
 * 取消 / 保存按钮据此置灰：编辑态未改动时两者都点不动。
 */
const editorDirty = useObjectDirty(() => editor.form)
/** 取消 / 保存是否可点：新增恒可点，编辑要求有改动 */
const canSaveApp = computed(() => !editor.id || editorDirty.isDirty.value)

async function load() {
  loading.value = true
  try {
    const { data } = await pageApps({ current: query.current, size: query.size, name: query.name || undefined })
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
  query.name = ''
  query.current = 1
  void load()
}

function openCreate() {
  editor.id = 0
  editor.form = { name: '', deptId: undefined, remark: '', status: 1 }
  editor.open = true
  // 新建恒可点，仍定一次基线避免残留脏标记
  editorDirty.markClean()
}

function openEdit(row: AppRow) {
  editor.id = row.id
  editor.form = { name: row.name, deptId: row.deptId, remark: row.remark || '', status: row.status }
  editor.open = true
  // 回填完成即定为干净基线：打开弹窗不该显示可保存
  editorDirty.markClean()
}

async function save() {
  const payload = { ...editor.form, deptId: editor.form.deptId || null }
  if (editor.id) {
    await updateApp(editor.id, payload)
  } else {
    const { data } = await createApp(payload)
    await ElMessageBox.alert(
      `AppId：${data.data.appKey}\n密钥：${data.data.appSecret}\n请立即保存，关闭后无法再查看。`,
      '应用已创建'
    )
  }
  editor.open = false
  await load()
}

function openGrant(row: AppRow) {
  grant.id = row.id
  grant.text = (row.agentIds || []).join(',')
  grant.open = true
}

async function saveGrant() {
  const agentIds = grant.text
    .split(/[,，\s]+/)
    .map((item) => Number(item))
    .filter((item) => Number.isFinite(item) && item > 0)
  await grantAppAgents(grant.id, agentIds)
  grant.open = false
  await load()
}

async function onResetSecret(row: AppRow) {
  await ElMessageBox.confirm(`重置「${row.name}」的密钥？旧密钥会立即失效。`, '重置密钥')
  const { data } = await resetAppSecret(row.id)
  await ElMessageBox.alert(data.data.appSecret || '', '新密钥只显示一次')
}

async function onDelete(row: AppRow) {
  await ElMessageBox.confirm(`删除应用「${row.name}」？`, '删除')
  await deleteApp(row.id)
  await load()
}

onMounted(async () => {
  await load()
  if (user.hasPerm('system:dept:list')) {
    depts.value = (await deptTree()).data.data
  }
})
</script>

