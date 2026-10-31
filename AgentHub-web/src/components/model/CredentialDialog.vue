<template>
  <el-dialog :model-value="modelValue" title="凭证" width="640px" @close="onClose">
    <div class="cred-bar">
      <el-input v-model="keyword" class="cred-search" clearable placeholder="名称" maxlength="64" />
    </div>
    <el-table v-loading="loading" :data="filteredRows" empty-text="还没有凭证">
      <el-table-column prop="name" label="名称" min-width="120" align="center" />
      <el-table-column prop="apiKeyMask" label="Key" min-width="140" align="center" />
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            :disabled="!user.hasPerm('model:provider:edit')"
            @change="(on: string | number | boolean) => onToggle(row, on === true)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="center">
        <template #default="{ row }">
          <el-button
            v-if="user.hasPerm('model:provider:edit') || user.hasPerm('model:provider:remove')"
            link
            type="danger"
            @click="remove(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 底栏：点 + 展开一行草稿；取消/保存在右侧 -->
    <div v-if="user.hasPerm('model:provider:edit')" class="cred-footer">
      <template v-if="!draftOpen">
        <button type="button" class="cred-add-row" :disabled="adding" aria-label="添加凭证" @click="openDraft">
          +
        </button>
      </template>
      <template v-else>
        <el-input v-model="draft.name" class="draft-input" maxlength="64" placeholder="凭证名" />
        <el-input
          v-model="draft.apiKey"
          class="draft-input"
          type="password"
          show-password
          maxlength="512"
          placeholder="API Key"
        />
        <div class="draft-actions">
          <!-- 名称与 Key 都填了才可保存；空草稿时两个按钮都不可点 -->
          <el-button :disabled="!draftFilled" @click="cancelDraft">取消</el-button>
          <el-button type="primary" :loading="adding" :disabled="!draftFilled" @click="add">保存</el-button>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 某个供应商下的凭证。所属域：model。
 * 顶栏仅名称筛选；新增入口在列表底部「+」，展开后右侧取消/保存。
 * 保存成功只给一条轻提示：任何 UI 都不回显完整 Key，列表始终只显示掩码。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createCredential, deleteCredential, getProvider, updateCredential } from '@/api/model'
import { useUserStore } from '@/stores/user'
import type { CredentialRow } from '@/types/model'

const props = defineProps<{
  modelValue: boolean
  providerId?: number
}>()

const emit = defineEmits<{
  'update:modelValue': [open: boolean]
  changed: []
}>()

const user = useUserStore()
const loading = ref(false)
const adding = ref(false)
const draftOpen = ref(false)
const keyword = ref('')
const rows = ref<CredentialRow[]>([])
const draft = reactive({ name: '', apiKey: '' })

/** 按名称本地筛选，不额外打接口 */
const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) {
    return rows.value
  }
  return rows.value.filter((item) => (item.name || '').toLowerCase().includes(q))
})

/**
 * 底部草稿行是否已填写。
 * 用于取消 / 保存按钮的可用性：草稿行还没填任何内容时，保存必然被后端拒绝，
 * 取消也没有内容可丢，两个按钮都先置灰。
 */
const draftFilled = computed(() => !!draft.name.trim() || !!draft.apiKey.trim())

watch(
  () => [props.modelValue, props.providerId] as const,
  ([open]) => {
    if (open && props.providerId) {
      keyword.value = ''
      cancelDraft()
      load()
    }
  }
)

/** 关闭弹窗时收起草稿，避免下次打开残留。 */
function onClose() {
  cancelDraft()
  emit('update:modelValue', false)
}

/** 只拉掩码。明文不会从详情接口返回。 */
async function load() {
  if (!props.providerId) {
    return
  }
  loading.value = true
  try {
    const { data } = await getProvider(props.providerId)
    rows.value = data.data.credentials || []
  } finally {
    loading.value = false
  }
}

/**
 * 保存成功后的一次性反馈。
 * 只报「已保存」，不回显 Key 正文，也不把 Key 拼进任何文案或日志。
 */
function notifySaved() {
  ElMessage.success('已保存')
}

/** 展开底部新增行。 */
function openDraft() {
  draft.name = ''
  draft.apiKey = ''
  draftOpen.value = true
}

/** 收起草稿，不清已入库列表。 */
function cancelDraft() {
  draftOpen.value = false
  draft.name = ''
  draft.apiKey = ''
}

/** 保存草稿行 → 追加凭证。 */
async function add() {
  if (!props.providerId || !draft.name.trim() || !draft.apiKey.trim()) {
    ElMessage.warning('请填写凭证名和 API Key')
    return
  }
  adding.value = true
  try {
    await createCredential(props.providerId, {
      name: draft.name.trim(),
      apiKey: draft.apiKey.trim(),
      status: 1
    })
    // 清掉草稿里的明文，成功反馈只用固定文案（响应里已无明文）
    cancelDraft()
    await load()
    notifySaved()
    emit('changed')
  } finally {
    adding.value = false
  }
}

/**
 * 开关切换启停。
 *
 * @param row 当前行
 * @param enabled 目标是否启用
 */
async function onToggle(row: CredentialRow, enabled: boolean) {
  if (!props.providerId) {
    return
  }
  const next = enabled ? 1 : 0
  if (row.status === next) {
    return
  }
  await updateCredential(props.providerId, row.id, { name: row.name, status: next })
  await load()
  emit('changed')
}

/** 删除未被实例引用的凭证。 */
async function remove(row: CredentialRow) {
  if (!props.providerId) {
    return
  }
  try {
    await ElMessageBox.confirm(`删除凭证「${row.name}」？被实例引用时会失败。`, '删除凭证')
  } catch {
    return
  }
  await deleteCredential(props.providerId, row.id)
  await load()
  emit('changed')
}
</script>

<style scoped>
.cred-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.cred-search {
  width: 220px;
  flex: none;
}

.cred-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ah-border, #ebeef5);
}

.cred-add-row {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 36px;
  border: 1px dashed var(--ah-border, #dcdfe6);
  border-radius: 8px;
  background: #fafafa;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.cred-add-row:hover:not(:disabled) {
  color: var(--ah-primary, #635bff);
  border-color: #c5c2ff;
  background: #f4f3ff;
}

.cred-add-row:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.draft-input {
  width: 180px;
  flex: none;
}

.draft-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
</style>
