<template>
  <div class="ime">
    <!-- 空态：直接说明后端兜底顺序，避免用户以为不配就取不到值 -->
    <div v-if="!rows.length" class="ime__empty">
      尚未配置。不配时后端按 input.message → query → text → content 依次兜底。
    </div>

    <!-- 一行 = 一次「目标字段 ← 来源节点 / 来源字段」映射 -->
    <div
      v-for="(row, index) in rows"
      :key="row.uid"
      class="ime__row"
      :class="{ 'ime__row--missing': row.slot && missingRequiredKeys.includes(row.slot.key) }"
    >
      <div class="ime__head">
        <span class="ime__idx">{{ index + 1 }}</span>
        <!-- 定义槽：目标字段由工具定义 / AGENT 默认槽固定，只读展示，不允许改名 -->
        <div v-if="row.slot" class="ime__slot">
          <span class="ime__slot-key">{{ slotPrimary(row.slot) }}</span>
          <span v-if="slotSecondary(row.slot)" class="ime__slot-title">{{ slotSecondary(row.slot) }}</span>
          <span v-if="row.slot.required" class="ime__slot-required">必填</span>
        </div>
        <el-input
          v-else
          v-model="row.target"
          size="small"
          maxlength="64"
          class="ime__target"
          placeholder="目标字段（沿用节点入参名）"
          :disabled="disabled"
          @input="emitRows"
        />
        <!-- 换绑工具后旧键不在新定义里：仍显示并允许删除，不静默丢掉用户配过的来源 -->
        <el-tooltip v-if="!row.slot" content="删除该映射" placement="top">
          <button
            type="button"
            class="ime__del"
            :disabled="disabled"
            aria-label="删除该映射"
            @click="removeRow(index)"
          >
            <el-icon><Close /></el-icon>
          </button>
        </el-tooltip>
      </div>

      <!-- 工具定义里的说明原样展示；没有就只显示参数名，不翻译成中文 -->
      <div v-if="row.slot?.description" class="ime__slot-desc">{{ row.slot.description }}</div>
      <!-- 换绑工具后遗留的手填行：单独一行说明，不挤占目标字段宽度 -->
      <div v-if="showUndefinedBadge(row)" class="ime__undefined">未在工具定义中（可删除）</div>

      <!-- 来源 / 字段走三处共用的点选；自定义值走同一组件的手写模式 -->
      <MappingPathPicker
        :sources="pickerSources"
        :source-value="sourceValue(row)"
        :field-value="row.sourceField"
        :mode="row.sourceKind === 'custom' ? 'manual' : 'pick'"
        :manual-value="row.text"
        :path-text="pathOf(row)"
        :disabled="disabled"
        :field-label="mappingFieldLabel(row.sourceKind)"
        :manual-multiline="row.sourceKind === 'custom'"
        allow-path-edit
        :field-placeholder="fieldPlaceholder(row)"
        manual-placeholder="填写固定内容（可换行）；也可直接填 $. 开头的路径"
        :empty-text="row.sourceKind === 'unset' ? '（还没选择来源，这一行不会被保存）' : '（未选择来源）'"
        @update:source-value="onSourceChange(row, $event)"
        @update:field-value="onFieldChange(row, $event)"
        @update:manual-value="onTextChange(row, $event)"
        @update:path-value="onPathChange(row, $event)"
      />
    </div>

    <div class="ime__actions">
      <el-button link type="primary" class="ime__add" :disabled="disabled" @click="addRow">
        + 添加映射
      </el-button>
      <!-- 一期只补空着的行，不做整体覆盖；带入后给条数提示，不静默改 -->
      <el-button
        link
        type="primary"
        class="ime__same"
        :disabled="disabled || !rows.length"
        @click="fillSameName"
      >
        按同名带入
      </el-button>
    </div>

    <div v-if="missingRequiredKeys.length" class="ime__warn">
      必填入参还没配来源：{{ missingRequiredKeys.join('、') }}。草稿可以先存，发布会被拦截。
    </div>
    <div v-if="duplicateTargets.length" class="ime__warn">
      目标字段重复：{{ duplicateTargets.join('、') }}（保存时后一行覆盖前一行）
    </div>
    <div class="ime__hint">
      路径由「来源 + 字段」自动拼装，默认不铺在行下；需要核对或微调时打开「高级」。
      字段下拉支持直接输入嵌套路径（如 data.items）。
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 入参映射可视化编辑器。所属域：workflow 的前端画布层。
 * <p>职责：把「目标字段 ← 前置节点 / 输出字段」的 UI 行，与后端要求的
 * 「目标字段 → $.input / $.outputs.&lt;code&gt; / $.vars 路径」扁平对象互转。
 * <p>按定义出槽：TOOL 节点按所绑工具的 inputSchemaJson 列出顶层参数槽，
 * AGENT 节点给一个固定的「用户输入」槽；定义槽只读、不可删，编排者只选来源。
 * 未绑定工具 / schema 为空时仍是自由添加。
 * <p>点选交互收敛到 {@code MappingPathPicker}，与出参、循环打回反馈共用；
 * 来源 / 字段一律展示人话标签，完整路径默认收起在「高级」里，需要核对时再展开。
 * <p>解析 / 拼装 / 兼容旧草稿都走 utils/workflowMapping 的纯函数，
 * 槽位解析与空键剔除走 utils/workflowInputSlots，本组件只管交互与展示。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import MappingPathPicker from './MappingPathPicker.vue'
import {
  applyMappingPathEdit,
  buildMappingObject,
  isMappingRowEmpty,
  mappingFieldLabel,
  mappingRowPath,
  parseMappingRow,
  planSameNameFill,
  sameMapping
} from '@/utils/workflowMapping'
import {
  agentDefaultInputKey,
  agentInputSlotForKey,
  dropEmptyInputMapping,
  planSlotRows,
  resolveInputSlots,
  slotSignature
} from '@/utils/workflowInputSlots'
import type { InputSlotDefinition } from '@/utils/workflowInputSlots'
import type { MappingPickerSource, MappingUiRow } from '@/utils/workflowMapping'
import type { WorkflowMappingSource } from '@/types/workflow'

const props = defineProps<{
  /** 落库形态的映射：目标字段 → 路径 / 常量 */
  modelValue?: Record<string, string>
  /** 当前节点的前置节点（已排除 START，START 统一由「运行入参」表示） */
  sources: WorkflowMappingSource[]
  /** 图内所有 START 节点编码：用于把 $.outputs.<start> 归一成 $.input */
  startCodes: string[]
  /** 当前节点类型：AGENT 出默认「用户输入」槽，TOOL 按工具 schema 出槽 */
  nodeType?: string
  /** TOOL 节点所绑工具的 inputSchemaJson；空表示未绑定或没有定义，保持自由添加 */
  toolSchemaJson?: string | null
  /** 只读角色禁用全部交互 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, string>): void
}>()

/** 行 + 稳定 key：行可增删、可重排，不能用 index 做 key；slot 表示这是定义槽 */
interface Row extends MappingUiRow {
  uid: number
  slot?: InputSlotDefinition
}

/** 运行入参的常用字段，与后端 WorkflowNodeInputs.message 的兜底顺序一致 */
const INPUT_FIELD_HINTS = ['message', 'query', 'text', 'content']

/** 行 uid 自增，只用于 v-for key */
let uidSeed = 0

const rows = ref<Row[]>([])

/** 纯函数所需的解析上下文 */
const context = computed(() => ({ sources: props.sources, startCodes: props.startCodes }))

/**
 * AGENT 默认槽的键：打开编辑器时按已有映射定一次。
 * <p>不在每次输入时重算：否则用户手输目标字段命中 query 的瞬间，槽位会换键并抢走这一行。
 */
const agentSlotKey = agentDefaultInputKey(props.modelValue)

/**
 * 当前节点的定义槽。
 * <p>TOOL 按所绑工具的 inputSchemaJson；AGENT 按打开编辑器时的已有映射选一个默认键
 * （键在本次挂载内固定，避免手输目标字段时槽位中途换键）；其它节点保持自由添加。
 */
const slots = computed(() =>
  String(props.nodeType ?? '') === 'AGENT'
    ? [agentInputSlotForKey(agentSlotKey)]
    : resolveInputSlots(props.nodeType, props.toolSchemaJson)
)

/** 定义签名：换绑工具 / schema 同步后才重排行，逐次输入不会触发 */
const currentSlotSignature = computed(() => slotSignature(slots.value))

/** 必填但还没配来源的槽键：编辑器里直接标出；草稿仍可保存，发布由后端拦截 */
const missingRequiredKeys = computed(() =>
  slots.value
    .filter((slot) => slot.required)
    .filter((slot) => {
      const row = rows.value.find((item) => item.slot?.key === slot.key)
      // 没有对应行理论不会发生；按「没配」处理，避免提示漏报
      return !row || isMappingRowEmpty(row)
    })
    .map((slot) => slot.key)
)

/** 手填行是否要标「未在工具定义中」：只有 TOOL 且确实读到了工具定义时才标 */
function showUndefinedBadge(row: Row): boolean {
  return String(props.nodeType ?? '') === 'TOOL' && slots.value.length > 0 && !row.slot
}

/** 槽的主标签：AGENT 默认槽是「用户输入」，工具槽是参数名（不翻译） */
function slotPrimary(slot: InputSlotDefinition): string {
  return slot.origin === 'agent' ? slot.title : slot.key
}

/** 槽的次标签：AGENT 补落库键，工具槽补 title；没有就留空不显示 */
function slotSecondary(slot: InputSlotDefinition): string {
  return slot.origin === 'agent' ? slot.key : slot.title
}

/**
 * 三处共用的来源下拉数据：全部用节点名 / 字段名做人话标签，
 * 完整路径只放在 hint 里；custom 仍是手写入口。
 */
const pickerSources = computed<MappingPickerSource[]>(() => {
  const options: MappingPickerSource[] = [
    {
      value: 'input',
      label: '运行入参',
      hint: '$.input',
      wholeLabel: '整个入参',
      allowCustomField: true,
      fields: INPUT_FIELD_HINTS.map((key) => ({
        value: key,
        label: key,
        hint: `$.input.${key}`
      }))
    }
  ]
  props.sources.forEach((node) => {
    // 没配 outputMapping 时后端只保留 {text}，字段下拉仍要给一个真实可取的项
    const keys = node.outputKeys.length ? node.outputKeys : ['text']
    options.push({
      value: `node:${node.id}`,
      label: node.name || node.code,
      hint: node.code,
      wholeLabel: '整段输出',
      allowCustomField: true,
      fields: keys.map((key) => ({
        value: key,
        label: key,
        hint: `$.outputs.${node.code}.${key}`
      }))
    })
  })
  options.push({
    value: 'vars',
    label: '流程变量',
    hint: '$.vars',
    wholeLabel: '整个变量集合',
    allowCustomField: true,
    fields: []
  })
  options.push({
    value: 'custom',
    label: '自定义值 / 手写',
    hint: '常量或 $. 路径',
    fields: []
  })
  return options
})

/** 拓扑 / 输出字段签名：变化后尝试把之前解析失败的行升级成节点行 */
const structureKey = computed(() =>
  props.sources.map((item) => `${item.id}:${item.code}:${item.outputKeys.join('/')}`).join('|')
)

/** 重复的目标字段：落库对象会互相覆盖，这里提前提示 */
const duplicateTargets = computed(() => {
  const seen = new Set<string>()
  const duplicated = new Set<string>()
  rows.value.forEach((row) => {
    const target = row.target.trim()
    if (!target) {
      return
    }
    if (seen.has(target)) {
      duplicated.add(target)
    }
    seen.add(target)
  })
  return [...duplicated]
})

/* ============ 解析 / 拼装：委托纯函数工具 ============ */

/**
 * 新建一行 UI 状态。
 *
 * @param patch 行字段，缺省用空值
 * @return 带新 uid 的行
 */
function makeRow(patch: Partial<MappingUiRow>): Row {
  uidSeed += 1
  return {
    uid: uidSeed,
    target: patch.target ?? '',
    // 新行默认「未选择来源」：既能让「按同名带入」明确补空洞，也避免把默认值误存成 $.input
    sourceKind: patch.sourceKind ?? 'unset',
    sourceNodeId: patch.sourceNodeId,
    sourceField: patch.sourceField ?? '',
    text: patch.text ?? ''
  }
}

/**
 * 把一条已存的路径 / 常量拆成 UI 行（带 uid）。
 *
 * @param target 目标字段
 * @param raw    落库值
 * @return UI 行
 */
function parseRow(target: string, raw: string): Row {
  return makeRow(parseMappingRow(target, raw, context.value))
}

/**
 * 外部回填（切换节点、载入草稿、保存后刷新）时重建行。
 * <p>定义槽先按定义顺序占位（同名键沿用已存的来源），其余已存行按原顺序跟在后面；
 * 还没配来源的槽也保留在行里，用户可以直接给它选来源，它们不会进 mapping。
 *
 * @param mapping 落库形态的映射
 */
function rebuildRows(mapping: Record<string, string>) {
  const parsed = Object.entries(mapping || {}).map(([target, value]) =>
    parseRow(target, String(value ?? ''))
  )
  const planned = planSlotRows(parsed, slots.value, (target) => makeRow({ target }))
  rows.value = planned.map(({ row, slot }) => ({ ...row, target: slot ? slot.key : row.target, slot }))
}

/**
 * 定义变化（换绑工具 / AGENT 默认键变化）后重整行。
 * <p>只调整归属与顺序，不动目标与来源，因此不需要上抛：
 * 同名键保留已配来源，新槽先空着，不再属于新定义的旧键降为「未在工具定义中」的手填行。
 */
function syncSlotRows() {
  const planned = planSlotRows(rows.value, slots.value, (target) => makeRow({ target }))
  const next = planned.map(({ row, slot }) =>
    slot ? { ...row, target: slot.key, slot } : { ...row, slot: undefined }
  )
  // 结构没变时保持原数组，避免多余的 v-for 重渲染
  const signature = (list: Row[]) =>
    list.map((row) => `${row.uid}:${row.slot?.key ?? ''}:${row.target}`).join('|')
  if (signature(next) === signature(rows.value)) {
    return
  }
  rows.value = next
}

/**
 * 计算单行的落库路径，用于实时回显。
 *
 * @param row UI 行
 * @return 路径 / 常量；来源缺失返回空串
 */
function pathOf(row: Row): string {
  return mappingRowPath(row, context.value)
}

/**
 * 行 → 扁平对象；空目标、空取值的行整行跳过。
 * <p>只展示、还没配来源的定义槽不会写成空字符串键：空串会挡住 AGENT 无映射时的兜底取值。
 *
 * @return 目标字段 → 路径
 */
function build(): Record<string, string> {
  return dropEmptyInputMapping(buildMappingObject(rows.value, context.value))
}

/** 上抛当前映射；父层直接覆盖 config.inputMapping */
function emitRows() {
  emit('update:modelValue', build())
}

/* ============ 下拉选项与交互 ============ */

/** 来源下拉的绑定值：kind 与节点 id 合成一个字符串；未选择返回空串走占位 */
function sourceValue(row: Row): string {
  if (row.sourceKind === 'unset') {
    return ''
  }
  if (row.sourceKind === 'node') {
    return row.sourceNodeId ? `node:${row.sourceNodeId}` : ''
  }
  return row.sourceKind
}

/**
 * 切换来源。
 * <p>换来源时字段必须清空：不同节点的输出字段互不通用，留着旧字段会拼出一条取不到值的路径。
 *
 * @param row   UI 行
 * @param value 下拉值（kind 或 node:&lt;id&gt;）
 */
function onSourceChange(row: Row, value: string) {
  const text = String(value ?? '')
  if (!text) {
    row.sourceKind = 'unset'
    row.sourceNodeId = undefined
    row.sourceField = ''
    emitRows()
    return
  }
  if (text.startsWith('node:')) {
    row.sourceKind = 'node'
    row.sourceNodeId = text.slice(5)
  } else {
    row.sourceKind = text as MappingUiRow['sourceKind']
    row.sourceNodeId = undefined
  }
  row.sourceField = ''
  emitRows()
}

/** 改字段：空串代表「取整段」，需要保留，不能当未填 */
function onFieldChange(row: Row, value: string) {
  row.sourceField = String(value ?? '')
  emitRows()
}

/** 手写常量 / 高级路径：原文上抛，路径模型不变 */
function onTextChange(row: Row, value: string) {
  row.text = String(value ?? '')
  emitRows()
}

/**
 * 高级面板里直接改路径。
 * <p>能归一成运行入参 / 前置节点 / 流程变量就回到点选；否则按固定值原文保留，
 * 保存结构仍是「目标字段 → 路径字符串」。
 *
 * @param row   UI 行
 * @param value 高级面板里输入的路径 / 常量
 */
function onPathChange(row: Row, value: string) {
  const next = applyMappingPathEdit(row, value, context.value)
  row.sourceKind = next.sourceKind
  row.sourceNodeId = next.sourceNodeId
  row.sourceField = next.sourceField
  row.text = next.text
  emitRows()
}

/** 字段下拉占位文案：随来源变化，提示留空的含义 */
function fieldPlaceholder(row: Row): string {
  if (row.sourceKind === 'unset') {
    return '先选择来源'
  }
  if (row.sourceKind === 'node') {
    return '选择输出字段（留空取整段输出）'
  }
  if (row.sourceKind === 'vars') {
    return '输入变量名（留空取整个变量集合）'
  }
  if (row.sourceKind === 'custom') {
    return '常量文本，或 $. 开头的路径'
  }
  return '选择入参字段（留空取整个入参）'
}

/** 追加一行空映射；来源留空，用户填完目标与来源后才会落库 */
function addRow() {
  rows.value.push(makeRow({ target: '' }))
}

/** 删除一行并立即上抛，避免父层仍持有被删的键；定义槽不允许删除 */
function removeRow(index: number) {
  const row = rows.value[index]
  if (!row || row.slot) {
    return
  }
  rows.value.splice(index, 1)
  emitRows()
}

/**
 * 按同名带入：只补「目标已填、来源还空着」的行，用前置节点 outputKeys 同名命中。
 * <p>已有来源的行不覆盖；不新增行；结果用条数提示，不静默改动。
 */
function fillSameName() {
  const plan = planSameNameFill(rows.value, props.sources)
  if (!plan.length) {
    ElMessage.info('没有可带入的同名字段：只补还没选来源的行。')
    return
  }
  plan.forEach((item) => {
    const row = rows.value[item.rowIndex]
    if (!row) {
      return
    }
    row.sourceKind = 'node'
    row.sourceNodeId = item.sourceNodeId
    row.sourceField = item.sourceField
  })
  emitRows()
  ElMessage.success(`已按同名补了 ${plan.length} 项；已有来源的行没有改动。`)
}

/* ============ 同步 ============ */

/** 首次回填要无条件建行（外部空映射与初始空行等价，不能靠 sameMapping 跳过） */
let initialized = false

/**
 * 外部回填（切换节点、载入草稿、保存后刷新）时重建行。
 * <p>自身刚上抛的变更会与 build() 相等，直接跳过，避免输入框被重置、光标跳动。
 */
watch(
  () => props.modelValue,
  (next) => {
    if (!initialized) {
      initialized = true
      rebuildRows(next || {})
      return
    }
    const incoming = next || {}
    if (sameMapping(incoming, build())) {
      return
    }
    rebuildRows(incoming)
  },
  { immediate: true }
)

/**
 * 定义变化（工具 schema 到位 / 换绑工具 / AGENT 默认键变化）后重整行。
 * <p>选项是异步加载的：草稿先到时还没有工具定义，schema 到位后这里补出槽位，
 * 不主动 emit，避免一次无关重渲染就把草稿标记成已修改。
 */
watch(currentSlotSignature, () => {
  syncSlotRows()
})

/**
 * 拓扑或输出字段变化后，把之前「节点还没加载出来」而落到 custom 的 $.outputs 行升级成节点行。
 * <p>只动以 $.outputs 开头的 custom 行：用户手写的常量 / 其它路径保持原样，
 * 且不主动 emit，避免一次无关重渲染就把草稿标记成已修改。
 */
watch(structureKey, () => {
  const unresolved = rows.value.filter(
    (row) => row.sourceKind === 'custom' && row.text.trim().startsWith('$.outputs')
  )
  if (!unresolved.length) {
    return
  }
  const upgraded = new Map<number, Row>()
  unresolved.forEach((row) => {
    const parsed = parseRow(row.target, row.text)
    if (parsed.sourceKind === 'node') {
      upgraded.set(row.uid, { ...parsed, uid: row.uid })
    }
  })
  if (!upgraded.size) {
    return
  }
  rows.value = rows.value.map((row) => upgraded.get(row.uid) ?? row)
})
</script>

<style scoped>
.ime__empty {
  margin-bottom: 6px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.6;
}

/* 一行映射：窄面板（300px）下用竖向堆叠代替三列表格，避免下拉被压成看不清 */
.ime__row {
  margin-bottom: 8px;
  padding: 8px;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  background: var(--ah-surface-muted);
}

.ime__head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

/* 手填行的目标字段：占满剩余宽度，不被右侧徽标 / 删除按钮挤没 */
.ime__target {
  flex: 1;
  min-width: 0;
}

/* 定义槽：只读展示，参数名 + title + 必填标记一行内收 */
.ime__slot {
  display: flex;
  flex: 1;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

.ime__slot-key {
  overflow: hidden;
  color: var(--ah-text);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ime__slot-title {
  overflow: hidden;
  color: var(--ah-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ime__slot-required {
  flex: none;
  padding: 0 5px;
  border-radius: 4px;
  background: var(--ah-danger-tint);
  color: var(--ah-danger);
  font-size: 11px;
  line-height: 16px;
}

/* 工具定义里的说明：原样展示，不翻译；过长时换行 */
.ime__slot-desc {
  margin-bottom: 4px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

/* 换绑工具后遗留的手填行：标出来但保留可删，不静默丢用户配置 */
.ime__undefined {
  margin-bottom: 4px;
  color: var(--ah-warning);
  font-size: 11px;
  line-height: 1.5;
}

/* 必填槽还没配来源：整行给一条淡红描边，和发布拦截口径一致 */
.ime__row--missing {
  border-color: var(--ah-danger-tint);
  box-shadow: 0 0 0 1px var(--ah-danger-tint) inset;
}

.ime__idx {
  flex: none;
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: var(--ah-primary-tint);
  color: var(--ah-primary);
  font-size: 12px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
}

.ime__del {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--ah-text-faint);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.ime__del:hover:not(:disabled) {
  background: var(--ah-danger-tint);
  color: var(--ah-danger);
}

.ime__del:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.ime__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.ime__add,
.ime__same {
  padding: 0;
  font-size: 12px;
}

.ime__warn {
  margin-top: 4px;
  color: var(--ah-danger);
  font-size: 12px;
  line-height: 1.5;
}

.ime__hint {
  margin-top: 4px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.5;
}
</style>
