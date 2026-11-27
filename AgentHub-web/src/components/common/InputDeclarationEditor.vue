<template>
  <div class="wide">
    <!-- 空态：说明声明不是「对话入口多字段表单」，避免误解成生产入口 -->
    <div v-if="showHints !== false && !fields.length" class="wide__empty">
      还没有声明入参。声明只是对外合同：编排按这些键出槽，对话入口仍只收一个问题。
    </div>

    <!-- 一行 = 一个入参：键（$.input 字段名）+ 展示名 + 必填 + 说明 -->
    <div v-for="(field, index) in fields" :key="field.uid" class="wide__row">
      <div class="wide__head">
        <span class="wide__idx">{{ index + 1 }}</span>
        <el-input
          v-model="field.key"
          size="small"
          class="wide__key"
          :maxlength="DECLARATION_KEY_MAX"
          placeholder="键（如 topic）"
          :disabled="disabled"
          @input="onEdit"
        />
        <el-checkbox v-model="field.required" :disabled="disabled" @change="onEdit">必填</el-checkbox>
        <button
          type="button"
          class="wide__del"
          :disabled="disabled"
          aria-label="删除该参数"
          @click="removeField(index)"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <el-input
        v-model="field.title"
        size="small"
        class="wide__line"
        :maxlength="DECLARATION_TITLE_MAX"
        placeholder="展示名（可空；不填就显示键名）"
        :disabled="disabled"
        @input="onEdit"
      />
      <el-input
        v-model="field.description"
        size="small"
        class="wide__line"
        :maxlength="DECLARATION_DESCRIPTION_MAX"
        placeholder="说明（可空）"
        :disabled="disabled"
        @input="onEdit"
      />
    </div>

    <div class="wide__actions">
      <el-button
        link
        type="primary"
        class="wide__add"
        :disabled="disabled || fields.length >= DECLARATION_MAX_FIELDS"
        @click="addField"
      >
        + 添加参数
      </el-button>
      <span v-if="fields.length >= DECLARATION_MAX_FIELDS" class="wide__cap">
        最多 {{ DECLARATION_MAX_FIELDS }} 个
      </span>
    </div>

    <div v-if="duplicateKeys.length" class="wide__warn">
      键重复：{{ duplicateKeys.join('、') }}。同一份声明里键必须唯一，请先改名或删除。
    </div>
    <div v-if="overlongKey" class="wide__warn">
      键「{{ overlongKey }}」超过 {{ DECLARATION_KEY_MAX }} 个字符。
    </div>
    <div v-if="showHints !== false" class="wide__hint">
      键就是编排里 <code>$.input</code> 下的字段名；保存后随定义 / 发布版本冻结。旧的开始节点没有声明也能照常运行与发布。
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 入参声明编辑表格（工作流开始节点 / Agent 详情共用）。所属域：common 前端组件。
 * <p>职责：把一份顶层 JSON Schema 声明与「键 / 展示名 / 必填 / 说明」表格互转；
 * 键重复、超长在这里先标红提示（服务端保存时仍会再校验一次）。
 * <p>约束：只改声明，不碰入参映射 —— 开始节点是对外合同，不是给自己配来源。
 * <p>上抛 {@code null} 表示「没有声明」，由调用方决定是清空列还是忽略。
 */
import { computed, ref, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'
import {
  DECLARATION_DESCRIPTION_MAX,
  DECLARATION_KEY_MAX,
  DECLARATION_MAX_FIELDS,
  DECLARATION_TITLE_MAX,
  buildInputDeclaration,
  duplicateDeclarationKeys,
  parseDeclarationFields
} from '@/utils/workflowInputDeclaration'
import type { InputDeclarationField } from '@/utils/workflowInputDeclaration'

const props = defineProps<{
  /** 声明对象或 JSON 文本；null / 空表示没有声明 */
  modelValue?: unknown
  /** 只读角色禁用全部交互（仍需展示现有声明） */
  disabled?: boolean
  /**
   * 是否显示组件内置的引导文案（空态说明 + 底部键名说明）。
   * <p>默认显示，供工作流开始节点使用；Agent 详情外层已经给了唯一一句合同说明，
   * 传 false 避免同一处堆三段重复解释。校验类提示（键重复 / 超长）不受影响。
   */
  showHints?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: ReturnType<typeof buildInputDeclaration>): void
}>()

/** 表格行：字段 + 稳定 key（键可以被用户改，不能用它做 v-for key） */
interface FieldRow extends InputDeclarationField {
  uid: number
}

/** 行 uid 自增，只用于 v-for key */
let uidSeed = 0

const fields = ref<FieldRow[]>([])

/** 表格里重复的键：只提示，不静默丢行 */
const duplicateKeys = computed(() => duplicateDeclarationKeys(fields.value))

/** 超长的键：后端会拒，这里提前指出是哪一行 */
const overlongKey = computed(
  () => fields.value.find((field) => field.key.trim().length > DECLARATION_KEY_MAX)?.key.trim() || ''
)

/**
 * 声明 → 稳定字符串。
 * <p>只比较「归一后的声明」：用户输入过程中的空白差异不算变化，
 * 外部回填时也不会因为同一份声明的键序不同而重置正在编辑的行。
 *
 * @param fieldsOrRaw 编辑行或声明原文
 * @return 归一后 JSON；没有声明为空串
 */
function signature(fieldsOrRaw: unknown): string {
  const built = Array.isArray(fieldsOrRaw)
    ? buildInputDeclaration(fieldsOrRaw as InputDeclarationField[])
    : buildInputDeclaration(parseDeclarationFields(fieldsOrRaw))
  return built ? JSON.stringify(built) : ''
}

/** 外部回填：只在归一结果真的变化时重建行，避免输入时重置光标 */
function syncFromModel() {
  if (signature(fields.value) === signature(props.modelValue)) {
    return
  }
  fields.value = parseDeclarationFields(props.modelValue).map((field) => {
    uidSeed += 1
    return { ...field, uid: uidSeed }
  })
}

/** 表格改动 → 上抛归一后的声明（空表 = null） */
function onEdit() {
  emit('update:modelValue', buildInputDeclaration(fields.value))
}

/** 追加一行空参数；键留给用户填，填好并输入后才进入声明 */
function addField() {
  if (fields.value.length >= DECLARATION_MAX_FIELDS) {
    return
  }
  uidSeed += 1
  fields.value.push({ key: '', title: '', required: false, description: '', uid: uidSeed })
}

/**
 * 删除一行；被删的键同时从声明里消失（其它行不受影响）。
 *
 * @param index 行下标
 */
function removeField(index: number) {
  fields.value.splice(index, 1)
  onEdit()
}

watch(() => props.modelValue, syncFromModel, { immediate: true })
</script>

<style scoped>
.wide__empty {
  margin-bottom: 6px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.6;
}

/* 一行参数：窄面板下竖向堆叠，宽表单里也不会被压扁 */
.wide__row {
  margin-bottom: 8px;
  padding: 8px;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  background: var(--ah-surface-muted);
}

.wide__head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.wide__idx {
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

.wide__key {
  flex: 1;
  min-width: 0;
}

.wide__line {
  margin-top: 6px;
}

.wide__del {
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

.wide__del:hover:not(:disabled) {
  background: var(--ah-danger-tint);
  color: var(--ah-danger);
}

.wide__del:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.wide__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.wide__add {
  padding: 0;
  font-size: 12px;
}

.wide__cap {
  color: var(--ah-text-faint);
  font-size: 12px;
}

.wide__warn {
  margin-top: 4px;
  color: var(--ah-danger);
  font-size: 12px;
  line-height: 1.5;
}

.wide__hint {
  margin-top: 4px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.5;
}

.wide__hint code {
  padding: 0 3px;
  border-radius: 3px;
  background: var(--ah-surface-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
