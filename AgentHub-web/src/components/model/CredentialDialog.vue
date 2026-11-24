<template>
  <el-dialog :model-value="modelValue" title="凭证" width="760px" @close="onClose">
    <div class="cred-bar">
      <el-input v-model="keyword" class="cred-search" clearable placeholder="名称" maxlength="64" />
    </div>
    <el-table v-loading="loading" :data="filteredRows" empty-text="还没有凭证">
      <el-table-column prop="name" label="名称" min-width="120" align="center" />
      <el-table-column label="Key" min-width="150" align="center">
        <template #default="{ row }">
          <!-- 任何接口都不回明文：列表永远只出掩码 -->
          <span class="cred-mask">{{ row.apiKeyMask }}</span>
        </template>
      </el-table-column>
      <!--
        引用列直接把「能不能删」摆出来：被实例引用时删除按钮会禁用，
        这里说明是几个实例、出路是重置 Key，避免用户点了才看到后端拒绝。
      -->
      <el-table-column label="实例引用" width="190" align="center">
        <template #default="{ row }">
          <span v-if="refCountOf(row) > 0" class="cred-ref is-locked">
            被 {{ refCountOf(row) }} 个实例引用，不能删除
          </span>
          <span v-else class="cred-ref muted">未被引用，可删除</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            :disabled="!user.hasPerm('model:provider:edit')"
            @change="(on: string | number | boolean) => onToggle(row, on === true)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" align="center">
        <template #default="{ row }">
          <!-- 重置 Key：被实例引用的凭证也允许，这正是「删不掉」时的唯一出路 -->
          <el-button
            v-if="user.hasPerm('model:provider:resetKey')"
            link
            type="primary"
            :disabled="adding || resetting"
            @click="openReset(row)"
          >
            重置 Key
          </el-button>
          <!-- 有引用时禁用删除，并把原因写在按钮提示里（引用列已常显条数） -->
          <el-tooltip
            v-if="canRemove"
            :disabled="refCountOf(row) === 0"
            :content="removeBlockedReason(row)"
            placement="top-end"
          >
            <!-- 禁用按钮不触发鼠标事件，需要外层 span 承接 tooltip 的悬停 -->
            <span class="cred-del-wrap">
              <el-button
                link
                type="danger"
                :disabled="refCountOf(row) > 0"
                @click="remove(row)"
              >
                删除
              </el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <!-- 重置行：替换已存密钥，不新增也不删除凭证；与「添加凭证」共用底栏位置，互斥展开 -->
    <div v-if="resetTarget" class="cred-footer">
      <span class="draft-label">
        重置「{{ resetTarget.name }}」的 Key
      </span>
      <el-input
        v-model="resetDraft.apiKey"
        class="draft-input"
        type="password"
        show-password
        maxlength="512"
        placeholder="新的 API Key"
      />
      <div class="draft-actions">
        <el-button :disabled="resetting" @click="cancelReset">取消</el-button>
        <el-button
          type="primary"
          :loading="resetting"
          :disabled="!resetDraft.apiKey.trim()"
          @click="submitReset"
        >
          保存新 Key
        </el-button>
      </div>
      <!--
        正在贴密钥的一刻是用户最没安全感的时候，必须当场给出存储承诺：
        写清「加密存放 + 保存后只显示掩码」，与新增行的说明同口径（一处改两处一起改）。
      -->
      <p class="draft-hint">
        密钥加密存放（AES-GCM），保存后列表只显示掩码，任何接口都不会回显明文；凭证与实例的绑定关系不变。
      </p>
    </div>

    <!-- 底栏：点 + 展开一行草稿；取消/保存在右侧 -->
    <div v-else-if="user.hasPerm('model:provider:edit')" class="cred-footer">
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
        <!-- 与重置行同一条说明：保存前先说清怎么存、存完能看到什么，避免用户担心明文外泄 -->
        <p class="draft-hint">
          密钥加密存放（AES-GCM），保存后列表只显示掩码，任何接口都不会回显明文。
        </p>
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * 某个供应商下的凭证。所属域：model。
 * 顶栏仅名称筛选；新增入口在列表底部「+」，展开后右侧取消/保存。
 * 重置 Key 走同一处底栏（与新增互斥）：替换已存密钥、不删除凭证，被实例引用时也可用。
 * 保存成功只给一条轻提示：任何 UI 都不回显完整 Key，列表始终只显示掩码。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createCredential,
  deleteCredential,
  getProvider,
  resetCredentialKey,
  updateCredential
} from '@/api/model'
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

/** 正在重置 Key 的凭证；null 表示当前没有重置行展开 */
const resetTarget = ref<CredentialRow | null>(null)
/** 重置请求进行中：防连点，也用来禁用新增入口 */
const resetting = ref(false)
/** 新 Key 的临时容器：只在本组件内存在，提交后立即清空，不写 store、不进日志 */
const resetDraft = reactive({ apiKey: '' })

/** 按名称本地筛选，不额外打接口 */
const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) {
    return rows.value
  }
  return rows.value.filter((item) => (item.name || '').toLowerCase().includes(q))
})

/** 删除入口权限：编辑凭证或删除供应商任一即可，与后端 @SaCheckPermission 的 OR 语义一致 */
const canRemove = computed(() => user.hasPerm('model:provider:edit') || user.hasPerm('model:provider:remove'))

/**
 * 取某条凭证的实例引用数。
 * 老响应可能没有该字段：按 0 处理（后端删除时仍会二次校验，这里只是提前禁用）。
 *
 * @param row 凭证行
 * @return 引用它的未删除实例数
 */
function refCountOf(row: CredentialRow): number {
  return row.instanceCount ?? 0
}

/**
 * 删除被禁用时的说明文案。
 * 把「几个实例、为什么不能删、该用什么代替」写全，用户不必悬停后自己推断。
 *
 * @param row 凭证行（引用数 > 0 才有意义）
 * @return 直接展示的原因句
 */
function removeBlockedReason(row: CredentialRow): string {
  return `已被 ${refCountOf(row)} 个模型实例引用，不能删除；请改用「重置 Key」替换密钥`
}

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
      cancelReset()
      load()
    }
  }
)

/** 关闭弹窗时收起两类草稿，避免下次打开残留明文。 */
function onClose() {
  cancelDraft()
  cancelReset()
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
    // 重置行持有的是旧的响应对象：刷新后换成本次的新行，名称等展示才不会停在旧快照；
    // 凭证已被删掉时收起重置行，避免对着不存在的 id 提交
    if (resetTarget.value) {
      const fresh = rows.value.find((item) => item.id === resetTarget.value?.id)
      if (fresh) {
        resetTarget.value = fresh
      } else {
        cancelReset()
      }
    }
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

/** 展开底部新增行。与重置行互斥，避免两条输入同时在页面上抢焦点。 */
function openDraft() {
  cancelReset()
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

/**
 * 展开某条凭证的重置行。
 * 这里只切输入行，不立刻提交；真正的替换在 submitReset 里二次确认后才调接口。
 *
 * @param row 目标凭证
 */
function openReset(row: CredentialRow) {
  cancelDraft()
  resetTarget.value = row
  resetDraft.apiKey = ''
}

/** 收起重置行并清掉明文。 */
function cancelReset() {
  resetTarget.value = null
  resetDraft.apiKey = ''
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
 * 提交重置：先用确认框讲清「替换已存密钥、不是删除凭证」，再把新 Key 交给现成 reset-key 接口。
 * 成功后列表仍是掩码，实例继续用新 Key 出站（凭证 id 与绑定关系都没变）。
 */
async function submitReset() {
  const target = resetTarget.value
  const apiKey = resetDraft.apiKey.trim()
  if (!props.providerId || !target || !apiKey) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将用新 Key 替换凭证「${target.name}」已保存的密钥。` +
        '这是替换密钥，不是删除凭证：凭证本身与实例绑定关系不变，已有实例会立刻改用新 Key 出站。',
      '重置 Key',
      { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  resetting.value = true
  try {
    await resetCredentialKey(props.providerId, target.id, apiKey)
    // 明文只活到这里：收起重置行即清空输入，成功提示不含 Key
    cancelReset()
    await load()
    ElMessage.success('Key 已重置')
    emit('changed')
  } finally {
    resetting.value = false
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
  // 按钮已经禁用，这里再兜一层：避免键盘回车或后续新增入口绕过禁用态打出必然失败的请求
  const refCount = refCountOf(row)
  if (refCount > 0) {
    ElMessage.warning(`该凭证已被 ${refCount} 个模型实例引用，不能删除；请改用「重置 Key」`)
    return
  }
  try {
    await ElMessageBox.confirm(`删除凭证「${row.name}」？删除后不可恢复。`, '删除凭证')
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

/* 掩码用等宽字：一眼看出是遮掩后的片段，不像可读正文 */
.cred-mask {
  color: var(--ah-text-secondary);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.cred-ref {
  font-size: 12px;
}

/* 有引用：用待办琥珀点明「需要处理」，不用故障红（不是错误） */
.cred-ref.is-locked {
  color: var(--ah-warning);
}

.cred-ref.muted {
  color: var(--ah-text-muted);
}

/* tooltip 需要一个能承接禁用的宿主：禁用按钮本身不触发鼠标事件 */
.cred-del-wrap {
  display: inline-flex;
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

.draft-label {
  color: var(--ah-text-secondary);
  font-size: 12px;
}

.cred-add-row {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 36px;
  border: 1px dashed var(--ah-border, #ebeef5);
  border-radius: 8px;
  background: var(--ah-surface-muted);
  color: var(--ah-text-secondary, #6b7280);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.cred-add-row:hover:not(:disabled) {
  color: var(--ah-primary, #635bff);
  border-color: var(--el-color-primary-light-5);
  background: var(--ah-primary-tint);
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

/*
 * 密钥存储说明：常驻在底栏输入行下方，12px 可读灰（承载信息，不用更浅的 faint）。
 * 占满整行（flex-basis 100%），不与输入框、按钮抢同一行的横向空间。
 */
.draft-hint {
  flex-basis: 100%;
  margin: 0;
  color: var(--ah-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
