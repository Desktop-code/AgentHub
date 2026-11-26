<template>
  <div class="ome">
    <!-- 空态：说明不配时后端保留原始产出，避免用户以为不配就丢结果 -->
    <div v-if="!rows.length" class="ome__empty">
      尚未配置。不配时后端保留本节点原始产出。
    </div>

    <!-- 一行 = 一次「目标字段 ← 本节点产出字段」映射；来源固定本节点，不再出现前置节点 -->
    <div v-for="(row, index) in rows" :key="row.uid" class="ome__row">
      <div class="ome__head">
        <span class="ome__idx">{{ index + 1 }}</span>
        <el-input
          v-model="row.target"
          size="small"
          maxlength="64"
          placeholder="目标字段，如 answer"
          :disabled="disabled"
          @input="emitRows"
        />
        <el-tooltip content="删除该映射" placement="top">
          <button
            type="button"
            class="ome__del"
            :disabled="disabled"
            aria-label="删除该映射"
            @click="removeRow(index)"
          >
            <el-icon><Close /></el-icon>
          </button>
        </el-tooltip>
      </div>

      <!-- 与入参同款点选：来源隐藏，只选本节点产出字段；路径默认收在高级里 -->
      <MappingPathPicker
        :sources="pickerSources"
        source-value="output"
        :field-value="row.picked ? row.field : undefined"
        :mode="row.manual ? 'manual' : 'pick'"
        :manual-value="row.text"
        :path-text="pathOf(row)"
        :show-source="false"
        allow-manual
        allow-path-edit
        :field-label="row.manual ? '路径' : '本节点字段'"
        field-placeholder="选择本节点产出字段（留空取整段）"
        manual-placeholder="如 $.output.text；历史写法原样保留"
        empty-text="（未选择字段）"
        :disabled="disabled"
        @update:field-value="onFieldChange(row, $event)"
        @update:manual-value="onTextChange(row, $event)"
        @update:mode="onModeChange(row, $event)"
        @update:path-value="onPathChange(row, $event)"
      />
    </div>

    <el-button link type="primary" class="ome__add" :disabled="disabled" @click="addRow">
      + 添加映射
    </el-button>

    <div v-if="duplicateTargets.length" class="ome__warn">
      目标字段重复：{{ duplicateTargets.join('、') }}（保存时后一行覆盖前一行）
    </div>
    <div class="ome__hint">
      只从本节点产出取值（$.output.*）；路径由「目标字段 + 本节点字段」自动拼装，
      默认不铺在行下，需要核对或微调时打开「高级」或切到「手写路径」。
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 出参映射可视化编辑器。所属域：workflow 的前端画布层。
 *
 * <p>职责：把「目标字段 ← 本节点产出字段」的 UI 行，与后端 outputMapping 的扁平对象互转。
 * <p>为什么来源只放本节点产出：后端 {@code WorkflowNodeInputs.shapeOutput} 把本节点原始产出包成
 * {@code {output: raw}} 再解析映射，只有 {@code $.output.*} 取得到值；前置节点 / 运行入参
 * 在这里选了会落空，因此下拉根本不出现它们。
 * <p>点选交互复用 {@code MappingPathPicker}；路径默认收在「高级」里，
 * 历史写法（如 {@code $.input.*}）不丢，原样落到手写模式，后端契约与保存结构不变。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import MappingPathPicker from './MappingPathPicker.vue'
import {
  applyOutputPathEdit,
  buildOutputMapping,
  outputFieldPath,
  parseOutputRow,
  sameMapping
} from '@/utils/workflowMapping'
import type { MappingPickerSource, OutputMappingRow } from '@/utils/workflowMapping'

const props = defineProps<{
  /** 落库形态的出参映射：目标字段 → $.output.* / 历史写法 */
  modelValue?: Record<string, string>
  /** 只读角色禁用全部交互 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: Record<string, string>): void
}>()

/** 行 + 稳定 key：行可增删、可重排，不能用 index 做 key */
interface Row extends OutputMappingRow {
  uid: number
}

/** 行 uid 自增，只用于 v-for key */
let uidSeed = 0

const rows = ref<Row[]>([])

/**
 * 本节点可选的产出字段。
 * <p>后端约定原始产出至少含 {@code text}；历史映射里已经用到的字段也补进来，
 * 保证重新打开时下拉能回显，不会把用户原来的字段洗掉。
 */
const fieldKeys = computed(() => {
  const keys = new Set<string>(['text'])
  rows.value.forEach((row) => {
    if (!row.manual && row.field.trim()) {
      keys.add(row.field.trim())
    }
  })
  return [...keys]
})

/**
 * 出参只有本节点产出一个来源；不给前置节点 / 运行入参，避免选了后端取不到的路径。
 * 字段标签只写字段名，完整路径放在次要说明。
 */
const pickerSources = computed<MappingPickerSource[]>(() => [
  {
    value: 'output',
    label: '本节点产出',
    hint: '$.output',
    wholeLabel: '整段产出',
    allowCustomField: true,
    fields: fieldKeys.value.map((key) => ({
      value: key,
      label: key,
      hint: `$.output.${key}`
    }))
  }
])

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
function makeRow(patch: Partial<OutputMappingRow>): Row {
  uidSeed += 1
  return {
    uid: uidSeed,
    target: patch.target ?? '',
    field: patch.field ?? '',
    picked: patch.picked ?? false,
    manual: patch.manual ?? false,
    text: patch.text ?? ''
  }
}

/**
 * 把一条已存的出参值拆成 UI 行（带 uid）。
 *
 * @param target 目标字段
 * @param raw    落库值
 * @return UI 行
 */
function parseRow(target: string, raw: string): Row {
  return makeRow(parseOutputRow(target, raw))
}

/**
 * 计算单行的落库路径，用于实时回显。
 *
 * @param row UI 行
 * @return $.output.* 或手写原文
 */
function pathOf(row: Row): string {
  if (row.manual) {
    return row.text
  }
  // 新行还没选字段：回显留空，不与「整段产出」混淆
  return row.picked ? outputFieldPath(row.field) : ''
}

/** 行 → 扁平对象；空目标、空取值的行整行跳过 */
function build(): Record<string, string> {
  return buildOutputMapping(rows.value)
}

/** 上抛当前映射；父层直接覆盖 config.outputMapping */
function emitRows() {
  emit('update:modelValue', build())
}

/* ============ 交互 ============ */

/** 改产出字段：空串代表「取整段」，需要保留，不能当未填 */
function onFieldChange(row: Row, value: string) {
  row.field = String(value ?? '')
  row.picked = true
  row.manual = false
  emitRows()
}

/** 手写常量 / 历史路径：原文上抛，不阻断旧配置回显 */
function onTextChange(row: Row, value: string) {
  row.text = String(value ?? '')
  row.picked = true
  row.manual = true
  emitRows()
}

/**
 * 点选 / 手写来回切。
 * <p>切到手写时把当前点选路径作为初始文本，用户能直接改；
 * 切回点选时只有能归一到 {@code $.output.*} 才允许，否则留在手写，避免把历史路径猜错。
 *
 * @param row   UI 行
 * @param mode 目标模式
 */
function onModeChange(row: Row, mode: 'pick' | 'manual') {
  if (mode === 'manual') {
    row.text = row.manual ? row.text : pathOf(row)
    row.picked = true
    row.manual = true
    emitRows()
    return
  }
  const trimmed = row.text.trim()
  // 空手写值没有可点选的字段，回到未选择状态，不把 $.output 当成用户的选择
  if (!trimmed) {
    row.manual = false
    row.picked = false
    row.field = ''
    emitRows()
    return
  }
  const parsed = parseOutputRow(row.target, row.text)
  if (parsed.manual) {
    ElMessage.warning('当前是手写路径，无法自动对应到本节点字段；请先选择字段。')
    return
  }
  row.manual = false
  row.picked = true
  row.field = parsed.field
  emitRows()
}

/**
 * 高级面板里直接改出参路径。
 * <p>能归一成 {@code $.output.*} 就回到点选；历史 / 高级写法留到手写原样保存，
 * 清空则回到「还没选字段」，不猜整段产出。
 *
 * @param row   UI 行
 * @param value 高级面板里输入的路径
 */
function onPathChange(row: Row, value: string) {
  const next = applyOutputPathEdit(row, value)
  row.field = next.field
  row.picked = next.picked
  row.manual = next.manual
  row.text = next.text
  emitRows()
}

/** 追加一行空映射；目标是空的，用户填完目标与字段后才会落库 */
function addRow() {
  rows.value.push(makeRow({ target: '' }))
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
    rows.value = Object.entries(incoming).map(([target, value]) =>
      parseRow(target, String(value ?? ''))
    )
  },
  { immediate: true }
)
</script>

<style scoped>
.ome__empty {
  margin-bottom: 6px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.6;
}

/* 一行映射：窄面板下竖向堆叠，与入参编辑器的节奏一致 */
.ome__row {
  margin-bottom: 8px;
  padding: 8px;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  background: var(--ah-surface-muted);
}

.ome__head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.ome__idx {
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

.ome__del {
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

.ome__del:hover:not(:disabled) {
  background: var(--ah-danger-tint);
  color: var(--ah-danger);
}

.ome__del:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.ome__add {
  padding: 0;
  font-size: 12px;
}

.ome__warn {
  margin-top: 4px;
  color: var(--ah-danger);
  font-size: 12px;
  line-height: 1.5;
}

.ome__hint {
  margin-top: 4px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.5;
}
</style>
