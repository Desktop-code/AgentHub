<template>
  <div class="kb-panel">
    <div class="panel-head">
      <div class="head-text">
        <strong>知识库</strong>
        <span class="muted">
          勾选该 Agent 可检索的知识库。停用绑定后该库立即从运行时下线；还需在「工具」页启用 searchKnowledgeBase 才会真正调用。
        </span>
      </div>
      <el-button v-if="user.hasPerm('rag:kb:edit')" type="primary" size="small" @click="dialogOpen = true">
        <el-icon class="btn-icon"><Plus /></el-icon>
        添加知识库
      </el-button>
    </div>

    <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="block-alert" />

    <el-table v-loading="loading" :data="rows" empty-text="还没有绑定知识库" size="small">
      <el-table-column prop="kbName" label="知识库" min-width="160" show-overflow-tooltip />
      <el-table-column label="库状态" min-width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :class="statusClass(row.kbStatus)">
            {{ KB_STATUS_LABEL[row.kbStatus || ''] || row.kbStatus || '未知' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="检索能力" min-width="110">
        <template #default="{ row }">
          {{ KB_CAPABILITY_LABEL[row.kbCapability || ''] || '—' }}
        </template>
      </el-table-column>
      <el-table-column label="绑定" width="90" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.status === 1"
            :disabled="!user.hasPerm('rag:kb:edit') || savingId === row.id"
            @change="(on: string | number | boolean) => onToggle(row, on === true)"
          />
        </template>
      </el-table-column>
      <el-table-column label="检索覆盖" min-width="180">
        <template #default="{ row }">
          <el-popover placement="left" :width="420" trigger="click">
            <template #reference>
              <el-button link type="primary" size="small">{{ row.overridesJson ? '查看' : '默认' }}</el-button>
            </template>
            <div class="overrides-box">
              <el-input
                v-model="overrideDraft"
                type="textarea"
                :rows="5"
                class="mono"
                placeholder="留空表示沿用知识库默认"
              />
              <div class="overrides-actions">
                <el-button size="small" @click="resetOverride(row)">还原</el-button>
                <el-button size="small" type="primary" :loading="savingId === row.id" @click="saveOverride(row)">
                  保存
                </el-button>
              </div>
            </div>
          </el-popover>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center">
        <template #default="{ row }">
          <el-button
            v-if="user.hasPerm('rag:kb:edit')"
            link
            type="danger"
            :loading="removingId === row.id"
            @click="onRemove(row)"
          >
            解绑
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <KbBindDialog v-model="dialogOpen" :bound-ids="boundIds" @submit="onCreate" />

    <!--
      检索链路：重排属检索后处理，与 topK / RRF / 改写开关同一条链，故放在本 Tab。
      模型配置的用途槽已不再提供 RERANK 入口，这里是唯一配置处。
    -->
    <div class="chain-block">
      <div class="chain-head">
        <strong>检索链路</strong>
        <span class="muted">
          重排（Rerank）作用于检索结果后处理；开启后按所选实例精排候选切片。嵌入模型仍跟随各知识库的向量实例。
        </span>
      </div>
      <el-form label-width="96px" class="chain-form" @submit.prevent>
        <el-form-item label="启用重排">
          <el-switch
            :model-value="rerankOpen"
            :disabled="!canEditRag || rerankSaving"
            @change="(on: string | number | boolean) => onRerankToggle(on === true)"
          />
          <span class="switch-note">关闭后各知识库仍可按自身配置决定是否重排</span>
        </el-form-item>
        <el-form-item v-if="rerankOpen" label="重排模型">
          <el-select
            :model-value="rerankInstanceId"
            filterable
            clearable
            placeholder="选择用途为 RERANK 的已启用实例"
            :disabled="!canEditRag || rerankSaving"
            style="width: 320px"
            @update:model-value="onRerankModelChange"
          >
            <el-option
              v-for="item in rerankOptions"
              :key="item.id"
              :label="`${item.displayName} · ${item.modelId}`"
              :value="item.id"
            />
          </el-select>
          <span class="hint">候选来自模型中心已启用的 RERANK 实例；没有候选请先到「模型管理」新建</span>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Agent 详情的知识库绑定面板。所属域：rag。
 * 职责：列已绑库、启停绑定、编辑检索覆盖项、解绑；并承接检索链路的重排配置。
 * 约束：
 * <ul>
 *   <li>不在这里建知识库——建库是全局资产，入口在「知识库」页；</li>
 *   <li>重排是 Agent 侧唯一配置入口（模型配置的用途槽已不提供 RERANK）；</li>
 *   <li>嵌入不在此选，跟随所绑知识库的向量实例。</li>
 * </ul>
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { bindAgentKb, getAgentRerank, listAgentKbs, saveAgentRerank, unbindAgentKb, updateAgentKb } from '@/api/ragBind'
import { pageInstances } from '@/api/model'
import KbBindDialog from '@/components/agent/KbBindDialog.vue'
import { useUserStore } from '@/stores/user'
import type { InstanceRow } from '@/types/model'
import { KB_CAPABILITY_LABEL, KB_STATUS_LABEL, type KbBindRow } from '@/types/rag'

const props = defineProps<{
  agentId: number
}>()

const user = useUserStore()
const loading = ref(false)
const errorText = ref('')
const rows = ref<KbBindRow[]>([])
const dialogOpen = ref(false)
const savingId = ref(0)
const removingId = ref(0)
/** 覆盖项编辑草稿：一次只编辑一行，弹层关闭后由下一次点击重置 */
const overrideDraft = ref('')

const boundIds = computed(() => rows.value.map((row) => row.kbId))

/** 重排配置：模型 id 是真值来源，rerankOpen 只控制表单展开与开关视觉 */
const rerankModelId = ref<number | null>(null)
const rerankOpen = ref(false)
const rerankOptions = ref<InstanceRow[]>([])
const rerankSaving = ref(false)
const rerankInstanceId = computed(() => rerankModelId.value || undefined)
/** 与绑定的「解绑」共用 rag:kb:edit 权限 */
const canEditRag = computed(() => user.hasPerm('rag:kb:edit'))

/**
 * 拉取检索链路的重排配置。
 * 未配置时字段为空，界面表现为「开关关、模型下拉不展开」；
 * 拉取失败按未配置处理（并保持开关关闭），避免界面显示一个并未落库的选择。
 */
async function loadRerank() {
  if (!props.agentId) {
    return
  }
  try {
    const { data } = await getAgentRerank(props.agentId)
    rerankModelId.value = data.data?.modelInstanceId ?? null
    rerankOpen.value = !!rerankModelId.value
  } catch {
    rerankModelId.value = null
    rerankOpen.value = false
  }
}

/**
 * 拉取可选重排实例。只取启用的前 100 条，够控制台用；
 * 模型中心改动后重新进入本 Tab 即可刷新。
 */
async function loadRerankOptions() {
  try {
    const { data } = await pageInstances({ current: 1, size: 100, capability: 'RERANK' })
    rerankOptions.value = (data.data?.records || []).filter((item) => item.status === 1)
  } catch {
    rerankOptions.value = []
  }
}

/**
 * 开关重排。
 * 打开时必须有可用实例：没有就提示并保持关闭，避免存出「开着但没有模型」的半截状态；
 * 有关闭时立刻写 0（后端把该绑定逻辑删掉）。
 *
 * @param on 目标是否启用
 */
async function onRerankToggle(on: boolean) {
  if (on) {
    if (!rerankOptions.value.length) {
      await loadRerankOptions()
    }
    if (!rerankOptions.value.length) {
      ElMessage.warning('模型中心还没有可用的 RERANK 实例，请先在「模型管理」创建并启用')
      rerankOpen.value = false
      return
    }
    rerankOpen.value = true
    // 已有选择就沿用，否则用第一个候选，保证「一开即可用」
    await submitRerank(rerankModelId.value || rerankOptions.value[0].id)
    return
  }
  rerankOpen.value = false
  rerankModelId.value = null
  await submitRerank(0)
}

/**
 * 切换重排模型：选中即落库，清空等同关闭。
 *
 * @param value el-select 回传的实例 id
 */
async function onRerankModelChange(value: number | undefined) {
  if (!value) {
    rerankOpen.value = false
    rerankModelId.value = null
    await submitRerank(0)
    return
  }
  await submitRerank(value)
}

/**
 * 提交重排配置。失败回滚到服务端现状，避免界面与实际不一致。
 *
 * @param modelInstanceId 实例 id；0 表示关闭
 */
async function submitRerank(modelInstanceId: number) {
  rerankSaving.value = true
  try {
    const { data } = await saveAgentRerank(props.agentId, modelInstanceId)
    rerankModelId.value = data.data?.modelInstanceId ?? null
    rerankOpen.value = !!rerankModelId.value
    ElMessage.success(modelInstanceId > 0 ? '已保存重排配置' : '已关闭重排')
  } catch {
    await loadRerank()
  } finally {
    rerankSaving.value = false
  }
}

function statusClass(status?: string | null) {
  if (status === 'READY') {
    return 'tag-on'
  }
  if (status === 'INIT_FAILED') {
    return 'tag-error'
  }
  return 'tag-off'
}

async function load() {
  if (!props.agentId) {
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    const { data } = await listAgentKbs(props.agentId)
    rows.value = data.data || []
  } catch {
    errorText.value = '知识库绑定加载失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 启停绑定。停用后后端运行时立即跳过该库。
 *
 * @param row    绑定行
 * @param enabled 目标是否启用
 */
async function onToggle(row: KbBindRow, enabled: boolean) {
  const next = enabled ? 1 : 0
  if (row.status === next) {
    return
  }
  savingId.value = row.id
  try {
    const { data } = await updateAgentKb(props.agentId, row.id, { status: next })
    row.status = data.data.status
    ElMessage.success(enabled ? '已启用' : '已停用')
  } finally {
    savingId.value = 0
  }
}

/** 点开弹层时把当前覆盖项填进草稿 */
function resetOverride(row: KbBindRow) {
  overrideDraft.value = row.overridesJson || ''
}

async function saveOverride(row: KbBindRow) {
  savingId.value = row.id
  try {
    const text = overrideDraft.value.trim()
    const { data } = await updateAgentKb(props.agentId, row.id, { overridesJson: text })
    row.overridesJson = data.data.overridesJson
    ElMessage.success(text ? '已保存覆盖项' : '已恢复默认')
  } finally {
    savingId.value = 0
  }
}

async function onRemove(row: KbBindRow) {
  try {
    await ElMessageBox.confirm(`解绑知识库「${row.kbName || row.kbId}」？`, '解绑', {
      confirmButtonText: '解绑',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  removingId.value = row.id
  try {
    await unbindAgentKb(props.agentId, row.id)
    ElMessage.success('已解绑')
    await load()
  } finally {
    removingId.value = 0
  }
}

/**
 * 新增绑定。
 *
 * @param kbId         知识库
 * @param overridesJson 覆盖项，可空
 * @param done         关闭弹窗 loading
 */
async function onCreate(kbId: number, overridesJson: string | undefined, done: () => void) {
  try {
    await bindAgentKb(props.agentId, { kbId, overridesJson })
    dialogOpen.value = false
    ElMessage.success('已添加')
    await load()
  } finally {
    done()
  }
}

watch(() => props.agentId, () => {
  void load()
  void loadRerank()
  void loadRerankOptions()
})
onMounted(() => {
  void load()
  void loadRerank()
  void loadRerankOptions()
})
</script>

<style scoped>
.kb-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.head-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.muted {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.6;
}

.block-alert {
  margin: 0;
}

.overrides-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.overrides-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mono :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.tag-on {
  --el-tag-text-color: #10b981;
  --el-tag-border-color: #a7f3d0;
  --el-tag-bg-color: #ecfdf5;
}

.tag-error {
  --el-tag-text-color: #ef4444;
  --el-tag-border-color: #fecaca;
  --el-tag-bg-color: #fef2f2;
}

.tag-off {
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}

/* 检索链路块：与知识库列表用分隔线隔开，明确是同一 Tab 的另一段配置 */
.chain-block {
  margin-top: 4px;
  padding-top: 14px;
  border-top: 1px solid var(--ah-border, #e8eaf0);
}

.chain-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 12px;
}

.chain-form {
  max-width: 720px;
}

.switch-note {
  margin-left: 12px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.hint {
  margin-left: 10px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}
</style>
