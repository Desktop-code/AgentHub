<template>
  <div class="ime">
    <!-- 空态：直接说明后端兜底顺序，避免用户以为不配就取不到值 -->
    <div v-if="!rows.length" class="ime__empty">
      尚未配置。不配时后端按 input.message → query → text → content 依次兜底。
    </div>

    <!-- 一行 = 一次「目标字段 ← 来源节点 / 来源字段」映射 -->
    <div v-for="(row, index) in rows" :key="row.uid" class="ime__row">
      <div class="ime__head">
        <span class="ime__idx">{{ index + 1 }}</span>
        <el-input
          v-model="row.target"
          size="small"
          maxlength="64"
          placeholder="目标字段，如 query"
          :disabled="disabled"
          @input="emitRows"
        />
        <el-tooltip content="删除该映射" placement="top">
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

      <div class="ime__line">
        <span class="ime__label">来源</span>
        <el-select
          :model-value="sourceValue(row)"
          size="small"
          class="ime__control"
          placeholder="选择来源"
          :disabled="disabled"
          @update:model-value="onSourceChange(row, $event)"
        >
          <el-option label="运行入参（$.input）" value="input" />
          <el-option
            v-for="item in sources"
            :key="item.id"
            :label="item.label"
            :value="`node:${item.id}`"
          />
          <el-option label="流程变量（$.vars）" value="vars" />
          <el-option label="自定义值 / 高级路径" value="custom" />
        </el-select>
      </div>

      <div class="ime__line">
        <span class="ime__label">字段</span>
        <!-- 自定义值：常量或手写路径，既要能存「你是一名运维专家」，也要兜住陌生路径 -->
        <el-input
          v-if="row.sourceKind === 'custom'"
          v-model="row.text"
          size="small"
          class="ime__control"
          placeholder="常量文本，或 $. 开头的路径"
          :disabled="disabled"
          @input="emitRows"
        />
        <!-- 其余来源用可搜索 + 可新建的下拉：字段无法穷举时仍能手输（含嵌套路径） -->
        <el-select
          v-else
          :model-value="row.sourceField"
          size="small"
          class="ime__control"
          filterable
          allow-create
          default-first-option
          :placeholder="fieldPlaceholder(row)"
          :disabled="disabled"
          @update:model-value="onFieldChange(row, $event)"
        >
          <el-option
            v-for="item in fieldOptions(row)"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>

      <!-- 实时回显最终落库路径：用户能一眼确认拼出来的字符串是否符合预期 -->
      <div class="ime__path" :title="pathOf(row)">{{ pathOf(row) || '（未选择来源）' }}</div>
    </div>

    <el-button link type="primary" class="ime__add" :disabled="disabled" @click="addRow">
      + 添加映射
    </el-button>

    <div v-if="duplicateTargets.length" class="ime__warn">
      目标字段重复：{{ duplicateTargets.join('、') }}（保存时后一行覆盖前一行）
    </div>
    <div class="ime__hint">
      路径由「来源 + 字段」自动拼装，无需手敲；字段下拉支持直接输入嵌套路径（如 data.items）。
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 入参映射可视化编辑器。所属域：workflow 的前端画布层。
 * <p>职责：把「目标字段 ← 前置节点 / 输出字段」的 UI 行，与后端要求的
 * 「目标字段 → $.input / $.outputs.&lt;code&gt; / $.vars 路径」扁平对象互转。
 * <p>为什么 UI 与落库结构分开：后端 WorkflowNodeInputs 只认字符串路径，
 * 对象化结构会被判为非法值；因此本组件持行级状态，向上只 emit 拼好的字符串对象。
 * <p>解析 / 拼装 / 兼容旧草稿都走 utils/workflowMapping 的纯函数，本组件只管交互与展示。
 */
import { computed, ref, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'
import {
  buildMappingObject,
  mappingRowPath,
  parseMappingRow,
  sameMapping
} from '@/utils/workflowMapping'
import type { MappingUiRow } from '@/utils/workflowMapping'
import type { WorkflowMappingSource } from '@/types/workflow'

const props = defineProps<{
  /** 落库形态的映射：目标字段 → 路径 / 常量 */
  modelValue?: Record<string, string>
  /** 当前节点的前置节点（已排除 START，START 统一由「运行入参」表示） */
  sources: WorkflowMappingSource[]
  /** 图内所有 START 节点编码：用于把 $.outputs.<start> 归一成 $.input */
  startCodes: string[]
  /** 只读角色禁用全部交互 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, string>): void
}>()

/** 行 + 稳定 key：行可增删、可重排，不能用 index 做 key */
interface Row extends MappingUiRow {
  uid: number
}

/** 运行入参的常用字段，与后端 WorkflowNodeInputs.message 的兜底顺序一致 */
const INPUT_FIELD_HINTS = ['message', 'query', 'text', 'content']

/** 行 uid 自增，只用于 v-for key */
let uidSeed = 0

const rows = ref<Row[]>([])

/** 节点 id → 来源项，拼路径与取输出字段都靠它 */
const sourceById = computed(() => {
  const map = new Map<string, WorkflowMappingSource>()
  props.sources.forEach((item) => map.set(item.id, item))
  return map
})

/** 纯函数所需的解析上下文 */
const context = computed(() => ({ sources: props.sources, startCodes: props.startCodes }))

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
    sourceKind: patch.sourceKind ?? 'input',
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
 *
 * @return 目标字段 → 路径
 */
function build(): Record<string, string> {
  return buildMappingObject(rows.value, context.value)
}

/** 上抛当前映射；父层直接覆盖 config.inputMapping */
function emitRows() {
  emit('update:modelValue', build())
}

/* ============ 下拉选项与交互 ============ */

/** 来源下拉的绑定值：kind 与节点 id 合成一个字符串 */
function sourceValue(row: Row): string {
  if (row.sourceKind === 'node') {
    return `node:${row.sourceNodeId ?? ''}`
  }
  return row.sourceKind
}

/**
 * 切换来源。
 * <p>换节点时字段必须清空：不同节点的输出字段互不通用，留着旧字段会拼出一条取不到值的路径。
 *
 * @param row   UI 行
 * @param value 下拉值（kind 或 node:&lt;id&gt;）
 */
function onSourceChange(row: Row, value: string) {
  const text = String(value ?? '')
  if (text.startsWith('node:')) {
    row.sourceKind = 'node'
    row.sourceNodeId = text.slice(5)
    row.sourceField = ''
  } else {
    row.sourceKind = text as MappingUiRow['sourceKind']
    row.sourceNodeId = undefined
    row.sourceField = ''
  }
  emitRows()
}

/** 改字段：空串代表「取整段」，需要保留，不能当未填 */
function onFieldChange(row: Row, value: string) {
  row.sourceField = String(value ?? '')
  emitRows()
}

/** 字段下拉占位文案：随来源变化，提示默认取值 */
function fieldPlaceholder(row: Row): string {
  if (row.sourceKind === 'node') {
    return '选择输出字段（留空取整段输出）'
  }
  if (row.sourceKind === 'vars') {
    return '输入变量名，如 orderId'
  }
  return '选择入参字段（留空取整个 $.input）'
}

/**
 * 字段下拉项。
 * <p>节点字段来源优先级：该节点 outputMapping 的键（真实产出结构）→ 无映射时只有 text
 * （后端 shapeOutput 未配置映射时保留 {text}）。当前值不在列表里时补一条，
 * 保证回显或手输的嵌套路径不会显示成空白。
 *
 * @param row UI 行
 * @return 下拉项，value 即 sourceField
 */
function fieldOptions(row: Row): Array<{ label: string; value: string }> {
  const options: Array<{ label: string; value: string }> = []
  if (row.sourceKind === 'node') {
    const node = sourceById.value.get(row.sourceNodeId ?? '')
    const code = node?.code ?? ''
    options.push({ label: `整段输出 $.outputs.${code}`, value: '' })
    const keys = node?.outputKeys?.length ? node.outputKeys : ['text']
    keys.forEach((key) => options.push({ label: `$.outputs.${code}.${key}`, value: key }))
  } else if (row.sourceKind === 'vars') {
    options.push({ label: '整个变量集合 $.vars', value: '' })
  } else {
    options.push({ label: '整个入参 $.input', value: '' })
    INPUT_FIELD_HINTS.forEach((key) => options.push({ label: `$.input.${key}`, value: key }))
  }
  // 当前值（回显字段 / 手输嵌套路径）不在候选里时补一条，否则下拉显示不出语义
  if (row.sourceField && !options.some((item) => item.value === row.sourceField)) {
    options.push({ label: row.sourceField, value: row.sourceField })
  }
  return options
}

/** 追加一行空映射；目标是空的，用户填完目标与来源后才会落库 */
function addRow() {
  rows.value.push(makeRow({ target: '', sourceKind: 'input', sourceField: '' }))
}

/** 删除一行并立即上抛，避免父层仍持有被删的键 */
function removeRow(index: number) {
  rows.value.splice(index, 1)
  emitRows()
}

/* ============ 同步 ============ */

/**
 * 外部回填（切换节点、载入草稿、保存后刷新）时重建行。
 * <p>自身刚上抛的变更会与 build() 相等，直接跳过，避免输入框被重置、光标跳动。
 */
watch(
  () => props.modelValue,
  (next) => {
    const incoming = next || {}
    if (sameMapping(incoming, build())) {
      return
    }
    rows.value = Object.entries(incoming).map(([target, value]) => parseRow(target, String(value ?? '')))
  },
  { immediate: true }
)

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
  color: #9ca3af;
  font-size: 11px;
  line-height: 1.6;
}

/* 一行映射：窄面板（300px）下用竖向堆叠代替三列表格，避免下拉被压成看不清 */
.ime__row {
  margin-bottom: 8px;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fafbfc;
}

.ime__head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.ime__idx {
  flex: none;
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: #eef2ff;
  color: #635bff;
  font-size: 10px;
  font-weight: 700;
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
  color: #9ca3af;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.ime__del:hover:not(:disabled) {
  background: #fef2f2;
  color: #dc2626;
}

.ime__del:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.ime__line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
}

/* 固定标签列：三个控件左边缘对齐，扫读时不会左右跳 */
.ime__label {
  flex: none;
  width: 28px;
  color: #9ca3af;
  font-size: 11px;
  line-height: 1.4;
}

.ime__control {
  flex: 1;
  min-width: 0;
}

.ime__path {
  margin-top: 5px;
  padding: 2px 6px;
  overflow: hidden;
  border-radius: 4px;
  background: #f3f4f6;
  color: #6b7280;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 10px;
  line-height: 1.6;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ime__add {
  padding: 0;
  font-size: 12px;
}

.ime__warn {
  margin-top: 4px;
  color: #dc2626;
  font-size: 11px;
  line-height: 1.5;
}

.ime__hint {
  margin-top: 4px;
  color: #9ca3af;
  font-size: 11px;
  line-height: 1.5;
}
</style>
