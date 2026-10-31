<template>
  <!--
    按用途槽展示模型绑定。所属域：agent。
    【生成】CHAT / ROUTER / REWRITE；多模态 / 音频 / 安全组无可用实例时整组灰显。
    单槽渲染交给 PurposeSlotBlock（与「记忆配置」Tab 共用，保证 ≤3 / 上→下优先级规则不漂移）。
    SUMMARY / MEMORY 的入口已迁到「记忆配置」Tab，本 Tab 既不展示也不提供添加；
    EMBEDDING 跟知识库 / 向量实例、RERANK 归「知识库」Tab，两者都不在本页管理。
  -->
  <div class="purpose-slots">
    <el-collapse v-model="openGroups">
      <el-collapse-item v-for="group in visibleGroups" :key="group.key" :name="group.key">
        <template #title>
          <span class="group-title">{{ group.label }}</span>
          <!-- 整组没有任何可用实例时给一句灰字，说明为什么这里绑不上，而不是只把按钮禁用 -->
          <span v-if="group.disabled" class="group-note">{{ group.disabledText }}</span>
        </template>
        <PurposeSlotBlock
          v-for="code in group.codes"
          :key="code"
          :purpose="code"
          :rows="models"
          :can-bind="canBind"
          :disabled="group.disabled"
          :disabled-text="group.disabledText"
          :hint="PURPOSE_SLOT_HINT[code]"
          @add="emit('add', $event)"
          @reorder="(purpose, ids) => emit('reorder', purpose, ids)"
          @remove="emit('remove', $event)"
          @prompt="(purpose, row) => emit('prompt', purpose, row)"
        />
      </el-collapse-item>
    </el-collapse>

    <!--
      遗留绑定收容：EMBEDDING（以及旧版隐藏、现已不在册的码）已不在本 Tab 管理，
      但后端按「Agent + 实例」去重，留着会导致该实例无法绑到其它槽，因此给出显式清理入口。
      提示文案指向正确的配置位置，避免用户以为是页面 bug。
      正常 Agent 的 RERANK 不在这里出现（由「知识库」Tab 接管）；只有 Router 父节点没有该 Tab，
      它的历史 RERANK 行才在这里兜底清理。
      记忆 Tab 的 SUMMARY / MEMORY 属于在册用途（入口在记忆页），同样不在此列。
    -->
    <div v-if="legacyRows.length" class="legacy-bar">
      <span class="legacy-text">
        以下绑定已不在本页管理：嵌入请到「知识库」选型（向量实例与维度在那里锁定）；
        重排请到「知识库」Tab 的检索链路配置。这些历史行仍会影响实例去重，可在此移除：
      </span>
      <span v-for="row in legacyRows" :key="row.id" class="legacy-chip">
        {{ capabilityOptionLabel(row.effectivePurpose || row.purpose || row.capability || '') }} ·
        {{ row.displayName || '实例已删除' }}
        <button v-if="canBind" type="button" class="legacy-remove" title="移除该遗留绑定" @click="emit('remove', row)">
          ×
        </button>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Agent 模型用途槽。所属域：agent。
 * 只负责分组折叠、灰显判断与遗留绑定清理提示；单槽交互在 PurposeSlotBlock，落库交给详情页。
 * 生成组含 CHAT / ROUTER / REWRITE；SUMMARY / MEMORY 入口在「记忆配置」Tab，
 * EMBEDDING / RERANK 不在本 Tab（前者跟知识库 / 向量实例，后者入口在「知识库」Tab）。
 * 多模态 / 音频 / 安全组在「模型中心没有任何可用实例」时整组灰显，避免用户反复点开空下拉。
 */
import { computed, ref } from 'vue'
import type { ModelBind } from '@/types/agent'
import {
  MEMORY_SLOT_PURPOSES,
  PURPOSE_SLOT_GROUPS,
  PURPOSE_SLOT_HINT,
  capabilityOptionLabel,
  normalizeCapability
} from '@/types/model'
import PurposeSlotBlock from '@/components/agent/PurposeSlotBlock.vue'

const props = defineProps<{
  models: ModelBind[]
  runMode: string
  canBind: boolean
  /**
   * 模型中心当前已启用实例覆盖的用途码。
   * 传 undefined / null 表示「未知」，此时不对分组做灰显：
   * 一次探测失败不该把整组锁死，宁可让用户点开看到空下拉。
   */
  availableCodes?: string[] | null
}>()

const emit = defineEmits<{
  add: [purpose: string]
  /** 槽内新顺序（绑定 id 数组，从高优先级到低优先级），由详情页换算 priority 落库 */
  reorder: [purpose: string, orderedIds: number[]]
  remove: [row: ModelBind]
  /** 打开该槽主模型的 L3 提示词配置（CHAT 在基本信息区，非 CHAT 走弹窗） */
  prompt: [purpose: string, row: ModelBind]
}>()

const openGroups = ref(['generation'])

/**
 * 本 Tab 在册的用途槽码（含仅 Router 模式可见的 ROUTER），用于识别被移出的遗留绑定。
 * SUMMARY / MEMORY 虽然在别处配置，但仍是合法用途，不算遗留，故并入在册集合。
 */
const slotCodes = new Set<string>([
  ...PURPOSE_SLOT_GROUPS.flatMap((group) => group.codes),
  ...MEMORY_SLOT_PURPOSES
])

/**
 * 需要在本页兜底清理的历史绑定：EMBEDDING（跟知识库 / 向量实例走），以及任何不在册的码。
 * RERANK 通常不算遗留——它已由「知识库」Tab 的检索链路继续管理，在此再给移除按钮就变成双入口；
 * 例外：Router 父节点没有「知识库」Tab（只做分发），它的历史 RERANK 行在本页兜底展示并可移除，
 * 否则会永远占着 (agent, instance) 去重位且无处可删。
 * ROUTER 只是按模式隐藏，仍属在册槽位，同样不计入。
 */
const legacyRows = computed(() =>
  props.models.filter((row) => {
    const purpose = effectiveOf(row)
    if (!purpose || slotCodes.has(purpose)) {
      return false
    }
    if (purpose === 'RERANK') {
      return props.runMode === 'ROUTER'
    }
    return true
  })
)

/** 已启用实例覆盖的用途码集合；null 表示未知，不做灰显判断。 */
const availableSet = computed(() => (props.availableCodes ? new Set(props.availableCodes) : null))

/**
 * 可见分组：ROUTER 槽按运行模式过滤；组内没有任何可用实例时整组灰显。
 * 灰显口径是「该组所有码都没有已启用实例」；单码缺实例只影响它自己的添加按钮。
 */
const visibleGroups = computed(() =>
  PURPOSE_SLOT_GROUPS.map((group) => {
    const codes = group.codes.filter((code) => code !== 'ROUTER' || props.runMode === 'ROUTER')
    const set = availableSet.value
    const disabled = !!set && codes.length > 0 && codes.every((code) => !set.has(code))
    return {
      ...group,
      codes,
      disabled,
      disabledText: disabled ? `模型中心还没有可用的${group.label}模型实例` : ''
    }
  }).filter((group) => group.codes.length > 0)
)

/**
 * 绑定行的有效用途：purpose 优先，否则跟实例 capability（旧码已规范化）。
 *
 * @param row 绑定行
 * @return 规范用途码
 */
function effectiveOf(row: ModelBind) {
  return normalizeCapability(row.effectivePurpose || row.purpose || row.capability)
}
</script>

<style scoped>
.purpose-slots {
  display: flex;
  flex-direction: column;
}

/* 折叠标题：组名与灰字说明同一行，说明不抢标题权重 */
.group-title {
  font-weight: 600;
}

.group-note {
  margin-left: 8px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

/* 同组内槽位之间留白由子组件自身承担，这里只控制相邻槽的间距 */
.purpose-slots :deep(.slot + .slot) {
  margin-top: 14px;
}

/* 遗留绑定提示：低饱和警示条，不与正式槽位抢视觉 */
.legacy-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 16px;
  padding: 8px 10px;
  background: #fffbf5;
  border: 1px solid #fbe6c8;
  border-radius: 6px;
  font-size: 12px;
}

.legacy-text {
  color: #a8741a;
}

.legacy-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 6px;
  color: var(--ah-text-secondary, #6b7280);
  background: #fff;
  border: 1px solid #f0e2cc;
  border-radius: 4px;
}

.legacy-remove {
  padding: 0 2px;
  color: #c0c4cc;
  font-size: 13px;
  line-height: 1;
  background: transparent;
  border: none;
  cursor: pointer;
}

.legacy-remove:hover {
  color: var(--el-color-danger, #f56c6c);
}
</style>
