<template>
  <!--
    MA 运行页右栏：右侧执行明细。所属域：runtime（展示层）。
    绑定中间当前选中的那一轮，右上可切【明细 | 链路】，默认明细：
    - 明细：按执行顺序一行一步，前面带 HH:mm:ss，右侧是耗时 / token，只说明
      「这一步是路由 / 哪个 Agent / LLM（含模型）/ 调了哪个工具」，不铺入参与摘要；
    - 链路：同一批步骤的树形结构（├── / └──），不显示时间列、入参、摘要与决策说明。
    两种视图都不含总结 / 结论回答；汇总步骤算路由 Agent 自己的收尾一步。
  -->
  <aside class="ma-chain">
    <!--
      页头固定两行：第一行只放标题与 Type 切换（切换永不参与挤压），
      第二行只放 Agent 角色图例；不再显示轮次 / 耗时 / 模板，避免占宽又易被裁。
    -->
    <header class="ma-chain__head">
      <div class="ma-chain__title-row">
        <span class="ma-chain__title">执行明细</span>
        <div class="ma-switch" role="tablist">
          <button
            v-for="tab in tabs"
            :key="tab.value"
            type="button"
            role="tab"
            class="ma-switch__item"
            :class="{ 'is-active': mode === tab.value }"
            :aria-selected="mode === tab.value"
            @click="mode = tab.value"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
      <div v-if="roleLegend.length" class="ma-chain__sub">
        <span v-for="item in roleLegend" :key="item.key" class="legend-item">
          <i class="legend-dot" :style="{ background: item.color }" aria-hidden="true" />
          {{ item.label }}
        </span>
      </div>
    </header>

    <!-- 明细：一行一步，右边依次是 token 消耗 / 耗时 / 执行状态图标 -->
    <div v-if="mode === 'detail'" class="ma-chain__body ma-detail">
      <template v-if="detailLines.length">
        <div v-for="line in detailLines" :key="line.key" class="ma-detail__row" :class="`is-${line.kind}`">
          <span class="ma-detail__time">{{ line.time }}</span>
          <span class="ma-detail__dot" :style="line.color ? { background: line.color } : undefined" aria-hidden="true" />
          <span class="ma-detail__label" :style="line.color ? { color: line.color } : undefined" :title="line.label">
            {{ line.label }}
          </span>
          <!-- 三列固定宽度，逐行对齐：token → 耗时 → 状态 -->
          <span class="ma-detail__tokens" :title="line.tokens ? `token 消耗 ${line.tokens}` : undefined">
            {{ line.tokens }}
          </span>
          <span class="ma-detail__duration" :title="line.duration ? `耗时 ${line.duration}` : undefined">
            {{ line.duration }}
          </span>
          <span class="ma-detail__state" :class="`is-${line.state || 'none'}`" :title="stateLabel(line.state)">
            <el-icon v-if="line.state === 'running'" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="line.state === 'success'"><Check /></el-icon>
            <el-icon v-else-if="line.state === 'error'"><Close /></el-icon>
            <el-icon v-else-if="line.state === 'cancelled'"><Minus /></el-icon>
          </span>
        </div>
      </template>
      <p v-else class="ma-chain__empty">{{ emptyText }}</p>
    </div>

    <!-- 链路：同一批步骤的树形结构；右侧同样是 token / 耗时 / 状态 -->
    <div v-if="mode === 'chain'" class="ma-chain__body ma-tree">
      <template v-if="chainLines.length">
        <div v-for="line in chainLines" :key="line.key" class="ma-tree__row" :class="`is-${line.kind}`">
          <span class="ma-tree__prefix" aria-hidden="true">{{ line.prefix }}</span>
          <span class="ma-tree__dot" :style="line.color ? { background: line.color } : undefined" aria-hidden="true" />
          <span class="ma-tree__label" :style="line.color ? { color: line.color } : undefined" :title="line.label">
            {{ line.label }}
          </span>
          <span class="ma-tree__tokens">{{ line.tokens }}</span>
          <span class="ma-tree__duration">{{ line.duration }}</span>
          <span class="ma-tree__state" :class="`is-${line.state || 'none'}`" :title="stateLabel(line.state)">
            <el-icon v-if="line.state === 'running'" class="is-loading"><Loading /></el-icon>
            <el-icon v-else-if="line.state === 'success'"><Check /></el-icon>
            <el-icon v-else-if="line.state === 'error'"><Close /></el-icon>
            <el-icon v-else-if="line.state === 'cancelled'"><Minus /></el-icon>
          </span>
        </div>
      </template>
      <p v-else class="ma-chain__empty">{{ emptyText }}</p>
    </div>
  </aside>
</template>

<script setup lang="ts">
/**
 * MA 运行页右栏执行明细。所属域：runtime（展示层）。
 *
 * <p>职责：把当前选中轮次的执行步骤按两种形态呈现：
 * 「明细」是扁平时序清单（时间 + 这一步在干什么），「链路」是树形父子结构。
 *
 * <p>约束：只读展示；数据由父组件按轮次传入，本组件不发请求、不解析正文；
 * 不输出结论文本、思维链与工具长结果；汇总步骤算路由 Agent 的收尾一步。
 */
import { computed, ref } from 'vue'
import { Check, Close, Loading, Minus } from '@element-plus/icons-vue'
import type { MaLineState, MaRunGroup } from '@/components/runtime/ma/maRunView'
import { buildChainLines, buildDetailLines } from '@/components/runtime/ma/maRunView'

const props = defineProps<{
  /** 当前选中的运行轮次；undefined 表示还没选 */
  group?: MaRunGroup
  /** 每秒刷新的当前时间戳，用于运行中耗时 */
  now: number
  /** 追踪数据整体加载失败说明 */
  error?: string
  /** 追踪数据是否仍在首次加载 */
  loading?: boolean
  /** 是否有 obs 追踪查看权限；缺权限时明确提示，不倒出「没有记录」 */
  canView?: boolean
}>()

/** 右栏视图：默认明细；切换后跨轮次保持 */
const mode = ref<'detail' | 'chain'>('detail')

/** 分段按钮定义；顺序即展示顺序 */
const tabs: { value: 'detail' | 'chain'; label: string }[] = [
  { value: 'detail', label: '明细' },
  { value: 'chain', label: '链路' }
]

/**
 * 一次算完，异常时返回空数组。
 *
 * <p>两个视图都在渲染期读这些结果；trace 数据形态异常（父子环、脏字段）时
 * 宁可显示空态，也不能让一次计算异常把整块右栏打空。
 *
 * @param build 具体构建函数
 * @param tag   出错时的日志标签
 */
function safeLines<T>(build: () => T[], tag: string): T[] {
  try {
    return build()
  } catch (error) {
    console.warn(`[ma-run] ${tag} 构建失败，已降级为空列表`, error)
    return []
  }
}

/** 明细行：按执行顺序一步一行，含 LLM 与工具调用 */
const detailLines = computed(() => safeLines(() => buildDetailLines(props.group, props.now), 'detail'))

/** 链路行：同一批步骤的树形结构；只排除检索工具内部记录，汇总归到路由节点下 */
const chainLines = computed(() => safeLines(() => buildChainLines(props.group, props.now), 'chain'))

/**
 * 角色图例：路由与业务 Agent 一卡一条，不合并。
 * 颜色取中栏卡片色条，本轮已保证 Agent 之间不撞色，因此无需按色归并。
 */
const roleLegend = computed(() => {
  const items: { key: string; label: string; color: string }[] = []
  for (const card of props.group?.cards ?? []) {
    items.push({ key: card.key, label: card.kind === 'router' ? '路由' : card.title, color: card.color })
  }
  return items
})

/**
 * 状态图标的可读文案：图标本身无文字，悬停时补一句，便于确认「绿勾＝成功」。
 *
 * @param state 行状态
 * @return 中文说明；无状态时为空串
 */
function stateLabel(state: MaLineState): string {
  if (state === 'running') {
    return '执行中'
  }
  if (state === 'success') {
    return '成功'
  }
  if (state === 'error') {
    return '失败'
  }
  if (state === 'cancelled') {
    return '已取消'
  }
  return ''
}

/** 空态文案：优先报错，其次权限 / 加载，最后才是「没有记录」 */const emptyText = computed(() => {
  if (props.error) {
    return props.error
  }
  if (props.canView === false) {
    return '没有执行明细查看权限'
  }
  if (!props.group) {
    return '选择中间任一轮，查看该轮的运行记录'
  }
  if (props.loading || props.group.trace?.loading) {
    return '执行明细采集中…'
  }
  // 本轮还在跑但 trace 尚未落库：说「采集中」，不能和执行中的卡片同时显示「暂无记录」
  if (props.group.status === 'running') {
    return '执行明细采集中…'
  }
  return '本轮暂无执行记录'
})
</script>

<style scoped>
/* 与整页统一白底：靠等宽字体与缩进区分层级，不铺灰色面板 */
.ma-chain {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  height: 100%;
  min-height: 0;
  background: #fff;
}

.ma-chain__head {
  flex: none;
  min-width: 0;
  padding: 12px 12px 8px;
  border-bottom: 1px solid #eef0f5;
}

/*
 * 第一行：标题 + Type 切换。
 * 标题可收缩并省略号，切换 flex: none 且靠右 —— 无论右栏多窄，按钮都在可视区内，
 * 不会被长文案挤出容器再被外层 overflow: hidden 裁掉。
 */
.ma-chain__title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.ma-chain__title {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 第二行：轮次 / 耗时 / 模板 + 角色图例。
 * 允许换行，长文案不会顶掉上面的按钮。
 */
.ma-chain__sub {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 10px;
  margin-top: 6px;
  min-width: 0;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
  line-height: 1.5;
}

/* 分段按钮：白底表示当前视图；不收缩，保证始终点得到 */
.ma-switch {
  display: inline-flex;
  flex: none;
  margin-left: auto;
  padding: 2px;
  background: #f4f5f9;
  border-radius: 8px;
}

.ma-switch__item {
  padding: 3px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 0.14s ease,
    color 0.14s ease;
}

.ma-switch__item:hover {
  color: var(--ah-primary, #635bff);
}

.ma-switch__item.is-active {
  background: #fff;
  color: var(--ah-primary, #635bff);
  font-weight: 650;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 图例圆点：与节点圆点同尺寸，颜色由行内 style 下发 */
.legend-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

/* 正文区左右各留 10px：右栏固定 500px，尽量把宽度让给内容 */
.ma-chain__body {
  flex: 1;
  min-height: 0;
  padding: 8px 10px 16px;
  overflow: auto;
}

/* ---------- 明细：扁平时序，一行一步 ---------- */
.ma-detail {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.9;
}

.ma-detail__row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  padding: 1px 4px;
  border-radius: 4px;
}

.ma-detail__row:hover {
  background: #f7f8fb;
}

/* 时间列固定宽度：只到 HH:mm:ss，避免每行左右跳动 */
.ma-detail__time {
  flex: none;
  width: 56px;
  color: #b0b4c0;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.ma-detail__dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #c4c9d6;
}

/*
 * 语义色：默认按节点类型取色；能归属到某个 Agent 时，行内 style 会覆盖成
 * 该 Agent 的角色色（含汇总＝路由紫），所以「谁在干活」一眼对得上颜色。
 */
.ma-detail__row.is-router .ma-detail__dot {
  background: #635bff;
}

.ma-detail__row.is-router .ma-detail__label {
  color: #635bff;
}

.ma-detail__row.is-llm .ma-detail__dot {
  background: #f79009;
}

.ma-detail__row.is-llm .ma-detail__label {
  color: #f79009;
}

.ma-detail__row.is-tool .ma-detail__dot {
  background: #12b76a;
}

.ma-detail__row.is-tool .ma-detail__label {
  color: #12b76a;
}

/* 名称占满中间：不显示工具入参 / 摘要，超长自己省略 */
.ma-detail__label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #4b5563;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 右栏行尾三列，固定宽度逐行对齐：
 * token 消耗 → 耗时 → 状态图标（转圈 / 绿勾 / 红叉）。
 * 数值用 tabular-nums，避免数字宽度不同导致整列抖动。
 */
.ma-detail__tokens,
.ma-detail__duration {
  flex: none;
  color: #9ca3af;
  font-size: 11px;
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.ma-detail__tokens {
  width: 40px;
}

.ma-detail__duration {
  width: 44px;
}

/* 状态位：固定 14px，保证没有图标时列也不塌，右侧留一点呼吸感 */
.ma-detail__state {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 14px;
  font-size: 12px;
}

.ma-detail__state.is-success {
  color: #12b76a;
}

.ma-detail__state.is-error {
  color: #f04438;
}

.ma-detail__state.is-running,
.ma-detail__state.is-cancelled {
  color: #b0b4c0;
}

/* ---------- 链路：树形结构，无时间列 ---------- */
.ma-tree {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.8;
}

.ma-tree__row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}

/* 树形前缀保留空格：├── / │   └── 必须靠等宽对齐 */
.ma-tree__prefix {
  flex: none;
  color: #c4c9d6;
  white-space: pre;
}

.ma-tree__dot {
  flex: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #c4c9d6;
}

.ma-tree__row.is-router .ma-tree__dot {
  background: #635bff;
}

.ma-tree__row.is-llm .ma-tree__dot {
  background: #f79009;
}

.ma-tree__row.is-tool .ma-tree__dot {
  background: #12b76a;
}

/* 名称占中间剩余宽度：链路不展示入参与摘要，超出自己省略 */
.ma-tree__label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: #4b5563;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 语义色与既有链路视图一致 */
.ma-tree__row.is-router .ma-tree__label {
  color: #635bff;
  font-weight: 650;
}

.ma-tree__row.is-agent .ma-tree__label {
  font-weight: 650;
}

.ma-tree__row.is-llm .ma-tree__label {
  color: #f79009;
}

.ma-tree__row.is-tool .ma-tree__label {
  color: #12b76a;
}

.ma-tree__row.is-other .ma-tree__label {
  color: #6b7280;
}

/*
 * 链路行尾三列：与明细同宽同序（token → 耗时 → 状态），
 * 两种视图切换时这一列不会左右跳。
 */
.ma-tree__tokens,
.ma-tree__duration {
  flex: none;
  color: #9ca3af;
  font-size: 11px;
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.ma-tree__tokens {
  width: 40px;
}

.ma-tree__duration {
  width: 44px;
}

.ma-tree__state {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 14px;
  font-size: 12px;
}

.ma-tree__state.is-success {
  color: #12b76a;
}

.ma-tree__state.is-error {
  color: #f04438;
}

.ma-tree__state.is-running,
.ma-tree__state.is-cancelled {
  color: #b0b4c0;
}

.ma-chain__empty {
  margin: 8px 0 0;
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.7;
}
</style>
