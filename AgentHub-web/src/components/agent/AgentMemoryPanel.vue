<template>
  <!--
    Agent 记忆策略面板。所属域：memory（嵌在 agent 详情 Tab）。
    行为/窗口/条数落 memory_agent_policy；摘要与抽取模型的绑模型入口就在本页对应区块旁，
    存储仍走 agent_model_bind(purpose=SUMMARY|MEMORY)，与「模型配置」共用同一套槽交互。
    SUMMARY 跟「上下文压缩」，MEMORY 仅当「启用长期记忆」为开时展示（关闭整块隐藏）。
    开关类（启用长期记忆 / 允许抽取 / 批抽 / 显式记住）一律即时落库，不点亮页底取消/保存。
  -->
  <div v-loading="loading" class="memory-panel">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="block-alert" />
    <template v-if="policy">
      <el-form label-width="120px" class="memory-form" @submit.prevent>
        <!-- 短期记忆：多轮上下文基础设施，开关锁定开启 -->
        <section class="block">
          <h3 class="section-title">短期记忆</h3>
          <el-form-item label="启用短期记忆">
            <el-switch :model-value="1" :active-value="1" :inactive-value="0" disabled />
            <span class="switch-note">多轮对话上下文默认开启并持久化，不可关闭</span>
          </el-form-item>
          <div class="field-grid">
            <el-form-item label="窗口轮次">
              <el-input-number
                v-model="policy.ctxMaxTurns"
                :min="1"
                :max="50"
                controls-position="right"
                :disabled="!canEdit"
              />
            </el-form-item>
            <el-form-item label="窗口字符">
              <el-input-number
                v-model="policy.ctxMaxChars"
                :min="256"
                :max="64000"
                :step="1000"
                controls-position="right"
                :disabled="!canEdit"
              />
            </el-form-item>
            <el-form-item label="窗口空闲 TTL">
              <el-input-number
                v-model="policy.ctxIdleTtlSec"
                :min="60"
                :max="86400"
                :step="60"
                controls-position="right"
                :disabled="!canEdit"
              />
            </el-form-item>
            <el-form-item label="短期条数上限">
              <el-input-number
                v-model="policy.maxShortItems"
                :min="1"
                :max="500"
                controls-position="right"
                :disabled="!canEdit"
              />
            </el-form-item>
            <el-form-item label="短期 TTL">
              <el-input-number
                v-model="policy.shortDefaultTtlSec"
                :min="60"
                :max="604800"
                :step="3600"
                controls-position="right"
                :disabled="!canEdit"
              />
            </el-form-item>
          </div>
        </section>

        <!-- 上下文压缩：行为开关 + 摘要模型绑定入口（同页，不再外链到模型配置） -->
        <section class="block">
          <h3 class="section-title">上下文压缩</h3>
          <div class="model-slot">
            <PurposeSlotBlock
              purpose="SUMMARY"
              :rows="models"
              :can-bind="canBind"
              :disabled="!summaryAvailable"
              :disabled-text="'模型中心还没有可用的摘要模型实例'"
              :hint="MEMORY_SLOT_HINT.SUMMARY"
              @add="emit('add-model', $event)"
              @reorder="(purpose, ids) => emit('reorder-model', purpose, ids)"
              @remove="emit('remove-model', $event)"
              @prompt="(purpose, row) => emit('prompt-model', purpose, row)"
            />
          </div>
        </section>

        <!-- 长期记忆：总开关 + 条数 + 触发开关竖排 -->
        <section class="block">
          <h3 class="section-title">长期记忆</h3>
          <!-- 总开关即时落库：成功后按已落库状态刷新下方区块显隐，不留下未保存脏状态 -->
          <el-form-item label="启用长期记忆">
            <el-switch
              :model-value="policy.enableLong === 1"
              :loading="savingSwitch === 'enableLong'"
              :disabled="!canEdit || savingSwitch !== ''"
              @change="(on: string | number | boolean) => saveSwitch('enableLong', on === true)"
            />
          </el-form-item>

          <div v-if="longEnabled" class="long-block">
            <div class="field-grid field-grid--narrow">
              <el-form-item label="长期条数上限">
                <el-input-number
                  v-model="policy.maxLongItems"
                  :min="1"
                  :max="500"
                  controls-position="right"
                  :disabled="!canEdit"
                />
              </el-form-item>
            </div>

            <div class="trigger-stack">
              <!-- 三个触发开关都即时落库：拨动即请求，底栏保持不脏 -->
              <el-form-item label="允许抽取">
                <el-switch
                  :model-value="policy.enableLongExtract === 1"
                  :loading="savingSwitch === 'enableLongExtract'"
                  :disabled="!canEdit || savingSwitch !== ''"
                  @change="(on: string | number | boolean) => saveSwitch('enableLongExtract', on === true)"
                />
              </el-form-item>
              <el-form-item label="空闲/删除时批抽">
                <el-switch
                  :model-value="policy.longExtractOnSessionEnd === 1"
                  :loading="savingSwitch === 'longExtractOnSessionEnd'"
                  :disabled="!canEdit || savingSwitch !== ''"
                  @change="(on: string | number | boolean) => saveSwitch('longExtractOnSessionEnd', on === true)"
                />
              </el-form-item>
              <el-form-item label="显式记住">
                <el-switch
                  :model-value="policy.longExtractOnExplicit === 1"
                  :loading="savingSwitch === 'longExtractOnExplicit'"
                  :disabled="!canEdit || savingSwitch !== ''"
                  @change="(on: string | number | boolean) => saveSwitch('longExtractOnExplicit', on === true)"
                />
              </el-form-item>
            </div>

            <el-form-item label="备注">
              <el-input
                v-model="policy.remark"
                maxlength="500"
                :disabled="!canEdit"
                placeholder="可选"
              />
            </el-form-item>
          </div>

          <!-- 长期关闭时备注仍可编辑，放在策略底部 -->
          <el-form-item v-if="!longEnabled" label="备注">
            <el-input
              v-model="policy.remark"
              maxlength="500"
              :disabled="!canEdit"
              placeholder="可选"
            />
          </el-form-item>

          <!--
            MEMORY（抽取模型 + 配置提示词）：仅当「启用长期记忆」为开时整块展示。
            关闭时整块隐藏而不是灰显——需求明确要求「关闭则整块隐藏，勿灰显可点」，
            因为长期记忆关闭时该模型不会被调用，摆一排禁用控件只会让用户误以为坏了。
            已绑数据保留在库内，重新打开开关时原样回显。
          -->
          <div v-if="longEnabled" class="model-slot">
            <PurposeSlotBlock
              purpose="MEMORY"
              :rows="models"
              :can-bind="canBind"
              :disabled="!memoryAvailable"
              :disabled-text="'模型中心还没有可用的记忆抽取模型实例'"
              :hint="MEMORY_SLOT_HINT.MEMORY"
              @add="emit('add-model', $event)"
              @reorder="(purpose, ids) => emit('reorder-model', purpose, ids)"
              @remove="emit('remove-model', $event)"
              @prompt="(purpose, row) => emit('prompt-model', purpose, row)"
            />
          </div>
        </section>
      </el-form>

      <!--
        本 Tab 自带 sticky 底栏：只提交数字 / 文本类字段。
        未脏时取消与保存都不可点——开关已即时落库，不该让人以为还要再点一次保存。
      -->
      <div v-if="canEdit" class="sticky-bar">
        <el-button :disabled="!isDirty || saving" @click="cancel">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!isDirty" @click="save">
          保存记忆策略
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * Agent 详情「记忆配置」Tab。所属域：memory。
 * 行为/窗口/条数落 memory_agent_policy；摘要（SUMMARY）与抽取（MEMORY）模型的绑定入口在本页，
 * 但读写仍走 agent_model_bind（与「模型配置」同一写路径），策略表实例列不再作为 UI 真相。
 * 展示规则：SUMMARY 跟「上下文压缩」，不依赖开关；MEMORY 整块（绑定行 + 配置提示词）
 * 仅当「启用长期记忆」为开时出现，关闭即隐藏且不清库内已绑数据。
 * 绑定的增删与排序由外层 AgentDetail 处理（那里已持有 bind API），本组件只抛事件。
 * 开关即时落库（saveSwitch），文字 / 数字字段走底栏；取消恢复进入本 Tab / 上次保存后的快照。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getMemoryPolicy, saveMemoryPolicy } from '@/api/memory'
import { useFormDirty } from '@/composables/useFormDirty'
import type { MemoryPolicy } from '@/types/memory'
import type { ModelBind } from '@/types/agent'
import { MEMORY_SLOT_HINT } from '@/types/model'
import PurposeSlotBlock from '@/components/agent/PurposeSlotBlock.vue'

const props = defineProps<{
  agentId: number
  canEdit: boolean
  /** 该 Agent 全部模型绑定（含 SUMMARY / MEMORY），由外层统一加载 */
  models: ModelBind[]
  /** 是否有绑定权限（agent:bind）；与 canEdit 分开，避免无 bind 权限时误开添加入口 */
  canBind: boolean
  /** 模型中心是否有已启用 SUMMARY 实例；false 时该槽灰显 */
  summaryAvailable: boolean
  /** 模型中心是否有已启用 MEMORY 实例；false 时该槽灰显 */
  memoryAvailable: boolean
}>()

const emit = defineEmits<{
  /** 打开添加模型弹窗（purpose 固定为 SUMMARY 或 MEMORY） */
  'add-model': [purpose: string]
  /** 槽内换序，交给外层换算 priority 落库 */
  'reorder-model': [purpose: string, orderedIds: number[]]
  /** 移除绑定，交给外层调用删除接口 */
  'remove-model': [row: ModelBind]
  /** 打开该槽主模型的 L3 提示词配置（记忆页的摘要 / 抽取用途同样可挂模板） */
  'prompt-model': [purpose: string, row: ModelBind]
}>()

const loading = ref(false)
const saving = ref(false)
/** 正在即时提交的开关字段名；空串表示没有在提交 */
const savingSwitch = ref('')
const error = ref('')
const policy = ref<MemoryPolicy>()
/**
 * 最近一次落库的完整策略行（含开关）。
 * 开关即时保存时用它拼包：这样写库的是「库里的值 + 本次那个开关」，
 * 不会把用户还没提交的数字 / 备注草稿顺手写进去。
 */
const savedPolicy = ref<MemoryPolicy>()
/** 切换 Agent 后丢弃旧请求结果，避免串写。 */
let active = true

/** 长期记忆是否开启；为 true 时才展开抽取相关配置。 */
const longEnabled = computed(() => policy.value?.enableLong === 1)

/**
 * 策略行的「可编辑草稿」快照：只含填写 / 选择类字段。
 * 开关字段（enableLong / enableLongExtract / longExtractOnSessionEnd / longExtractOnExplicit）
 * 是即时落库语义，一律不进快照，拨开关不会点亮底栏。
 *
 * @param p 策略行
 * @return 用于比较与回滚的纯数据；p 为空时返回 null
 */
function draftOf(p?: MemoryPolicy): MemoryDraft | null {
  if (!p) {
    return null
  }
  return {
    maxShortItems: p.maxShortItems,
    maxLongItems: p.maxLongItems,
    ctxMaxTurns: p.ctxMaxTurns,
    ctxMaxChars: p.ctxMaxChars,
    ctxIdleTtlSec: p.ctxIdleTtlSec,
    shortDefaultTtlSec: p.shortDefaultTtlSec,
    remark: (p.remark || '').trim()
  }
}

interface MemoryDraft {
  maxShortItems: number
  maxLongItems: number
  ctxMaxTurns: number
  ctxMaxChars: number
  ctxIdleTtlSec: number
  shortDefaultTtlSec: number
  remark: string
}

/** 把草稿写回策略行；开关与模型列不动。 */
function restoreDraft(snap: MemoryDraft) {
  const target = policy.value
  if (!target) {
    return
  }
  target.maxShortItems = snap.maxShortItems
  target.maxLongItems = snap.maxLongItems
  target.ctxMaxTurns = snap.ctxMaxTurns
  target.ctxMaxChars = snap.ctxMaxChars
  target.ctxIdleTtlSec = snap.ctxIdleTtlSec
  target.shortDefaultTtlSec = snap.shortDefaultTtlSec
  target.remark = snap.remark
}

const { isDirty, markClean, reset: discardDraft } = useFormDirty<MemoryDraft>(
  () => draftOf(policy.value),
  restoreDraft
)

watch(
  () => props.agentId,
  () => {
    active = true
    load()
  },
  { immediate: true }
)

/** 深拷贝策略行，避免表单与基线共享同一对象。 */
function clonePolicy(data: MemoryPolicy): MemoryPolicy {
  return {
    ...data,
    enableShort: 1,
    enableLong: data.enableLong ?? 0,
    remark: data.remark || ''
  }
}

/** 拉取策略并刷新快照基线。 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const policyRes = await getMemoryPolicy(props.agentId)
    if (!active) {
      return
    }
    const data = policyRes.data.data
    // 短期为基础设施：读入后固定为开启；模型实例列保留原值但不在 UI 暴露
    const next = clonePolicy(data)
    policy.value = next
    savedPolicy.value = clonePolicy(data)
    // 接口回填后必须重置基线，否则异步回填会被误判成用户改动
    markClean()
  } catch {
    if (!active) {
      return
    }
    error.value = '记忆策略加载失败'
    policy.value = undefined
    savedPolicy.value = undefined
  } finally {
    if (active) {
      loading.value = false
    }
  }
}

/**
 * 取消：恢复为上次成功保存后的快照，不请求接口；开关与模型绑定不动。
 */
function cancel() {
  if (!props.canEdit) {
    return
  }
  discardDraft()
}

/**
 * 拼提交体。默认取「当前表单」（保存底栏用）；传 useSaved=true 时改用已落库快照（即时开关用）。
 *
 * @param overrides 需要覆盖的字段
 * @param useSaved  是否以已落库快照为底，而不是当前草稿
 * @return 可直接提交的策略行
 */
function buildBody(overrides?: Partial<MemoryPolicy>, useSaved = false): MemoryPolicy | null {
  const source = useSaved ? savedPolicy.value : policy.value
  if (!source) {
    return null
  }
  return {
    ...source,
    agentId: props.agentId,
    enableShort: 1,
    // 模型实例列沿用库内原值：真相在 agent_model_bind，策略表不由此页改写
    summaryModelInstanceId: source.summaryModelInstanceId,
    longExtractModelInstanceId: source.longExtractModelInstanceId,
    remark: (overrides?.remark ?? source.remark)?.trim() || '',
    ...overrides
  }
}

/**
 * 开关即时保存：拨动即请求，成功后按已落库状态回填（含刷新长期记忆区块显隐）。
 * 失败回滚开关 UI 并提示，底栏脏态始终不受影响。
 *
 * @param key   开关字段名
 * @param value 目标是否开启
 */
async function saveSwitch(key: SwitchKey, value: boolean) {
  const current = policy.value
  const snapshot = savedPolicy.value
  if (!current || !snapshot || !props.canEdit) {
    return
  }
  const nextValue = value ? 1 : 0
  if (snapshot[key] === nextValue) {
    return
  }
  const previous = current[key]
  // 先乐观翻转，开关立即到位；失败再回滚
  current[key] = nextValue
  savingSwitch.value = key
  try {
    // 以快照拼包：本次只改这一个开关，不把未提交的数字 / 备注草稿写进去
    const body = buildBody({ [key]: nextValue } as Partial<MemoryPolicy>, true)
    if (!body) {
      return
    }
    const { data } = await saveMemoryPolicy(body)
    if (!active || !policy.value) {
      return
    }
    const landed = clonePolicy(data.data)
    savedPolicy.value = landed
    // 只把开关列写回表单；数字 / 备注保持用户编辑中的草稿
    policy.value = {
      ...policy.value,
      enableLong: landed.enableLong,
      enableLongExtract: landed.enableLongExtract,
      longExtractOnSessionEnd: landed.longExtractOnSessionEnd,
      longExtractOnExplicit: landed.longExtractOnExplicit
    }
    ElMessage.success('已保存')
  } catch {
    if (policy.value) {
      policy.value[key] = previous
    }
  } finally {
    savingSwitch.value = ''
  }
}

/** 可由本页即时提交的开关字段。 */
type SwitchKey = 'enableLong' | 'enableLongExtract' | 'longExtractOnSessionEnd' | 'longExtractOnExplicit'

/**
 * 保存策略里的数字 / 文本字段。开关与模型实例列按当前已落库值一起回传（不变更）。
 *
 * @returns 是否保存成功
 */
async function save(): Promise<boolean> {
  if (!props.canEdit || !isDirty.value) {
    return false
  }
  const body = buildBody()
  if (!body) {
    return false
  }
  saving.value = true
  try {
    const { data } = await saveMemoryPolicy(body)
    const next = clonePolicy(data.data)
    policy.value = next
    savedPolicy.value = clonePolicy(data.data)
    // 保存成功后刷新基线：底栏灭脏
    markClean()
    ElMessage.success('记忆策略已保存')
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

/** 外层切换 Agent / 卸载时停止写回。 */
function deactivate() {
  active = false
}

defineExpose({ save, cancel, isDirty, saving, load, deactivate })
</script>

<style scoped>
.memory-panel {
  min-height: 120px;
  display: flex;
  flex-direction: column;
}

.sticky-bar {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
  padding: 12px 0;
  background: #fff;
  border-top: 1px solid var(--ah-border, #e8eaf0);
}

.block-alert {
  margin-bottom: 12px;
}

.memory-form {
  max-width: 960px;
}

.block + .block {
  margin-top: 8px;
}

.section-title {
  margin: 16px 0 12px;
  font-size: 14px;
  font-weight: 650;
  color: var(--ah-text-primary, #1f2430);
}

.block:first-child .section-title {
  margin-top: 0;
}

/* 绑模型槽区：与行为字段之间用淡分隔线断开，避免误读为同一个开关 */
.model-slot {
  margin: 4px 0 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--ah-border, #eceef4);
}

.switch-note {
  margin-left: 12px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}

/* label+控件对齐的 2～3 列网格 */
.field-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}

.field-grid--narrow {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  max-width: 640px;
}

.field-grid :deep(.el-form-item),
.trigger-stack :deep(.el-form-item) {
  margin-bottom: 14px;
}

.field-grid :deep(.el-input-number) {
  width: 100%;
}

/* 触发开关独立竖排，勿与数字挤同一行 */
.trigger-stack {
  display: flex;
  flex-direction: column;
  max-width: 420px;
  margin-top: 4px;
}

.long-block {
  margin-bottom: 8px;
  padding: 12px 14px 4px;
  border: 1px solid var(--ah-border, #e8eaf0);
  border-radius: 10px;
  background: #fafafc;
}

@media (max-width: 900px) {
  .field-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .field-grid,
  .field-grid--narrow {
    grid-template-columns: 1fr;
  }
}
</style>
