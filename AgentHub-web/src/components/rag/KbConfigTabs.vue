<template>
  <!--
    三个 Tab 覆盖知识库弹窗的全部配置面：基础信息由父级用 #base 插槽传入（新建 / 编辑字段不同），
    入库与切片、检索与重排两块参数在这里自持；底部取消 / 保存始终留在弹窗 footer，不跟 Tab 走。
  -->
  <el-tabs :model-value="tab" class="kb-config-tabs" @update:model-value="onTabChange">
    <el-tab-pane label="基础信息" name="base">
      <slot name="base" />
    </el-tab-pane>

    <el-tab-pane label="入库与切片" name="ingest">
      <!-- 口径说明放在最上方：避免用户以为改完保存就会重切已有文档 -->
      <p class="kb-notice">改这些参数只影响之后新增的文档和切片；保存本弹窗不会重切已有文档。</p>

      <section class="kb-card">
        <h4 class="kb-card-title">切片参数</h4>
        <div class="kb-grid-3">
          <div class="kb-field">
            <div class="kb-label">
              切片长度
              <code class="kb-key">chunkSize</code>
              <span class="kb-range">{{ INGEST_RANGE.chunkSize.min }}–{{ INGEST_RANGE.chunkSize.max }}</span>
            </div>
            <el-input-number
              :model-value="ingest.chunkSize"
              :min="INGEST_RANGE.chunkSize.min"
              :max="INGEST_RANGE.chunkSize.max"
              :step="50"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setChunkSize(v)"
            />
            <div class="kb-hint">字符数，默认 450；改小时重叠与最小片长同步收紧。</div>
          </div>

          <div class="kb-field">
            <div class="kb-label">
              相邻切片重叠
              <code class="kb-key">chunkOverlap</code>
              <span class="kb-range">0–{{ overlapMax }}</span>
            </div>
            <el-input-number
              :model-value="ingest.chunkOverlap"
              :min="INGEST_RANGE.chunkOverlap.min"
              :max="overlapMax"
              :step="10"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setIngestInt('chunkOverlap', v, 0)"
            />
            <div class="kb-hint">与上一片重复的字符数，默认 100；不超过切片长度的一半。</div>
          </div>

          <div class="kb-field">
            <div class="kb-label">
              过短阈值
              <code class="kb-key">minChunkSize</code>
              <span class="kb-range">0–{{ ingest.chunkSize }}</span>
            </div>
            <el-input-number
              :model-value="ingest.minChunkSize"
              :min="INGEST_RANGE.minChunkSize.min"
              :max="ingest.chunkSize"
              :step="10"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setIngestInt('minChunkSize', v, 0)"
            />
            <div class="kb-hint">短于此长度的切片会与相邻片合并，默认 150。</div>
          </div>
        </div>
      </section>

      <!-- 示意块：只按这三个数字画比例，不读真实文档，也不代表实际切法 -->
      <section class="kb-card">
        <h4 class="kb-card-title">切片示意</h4>
        <div class="kb-chunks">
          <div
            v-for="item in chunkPreview"
            :key="item.index"
            class="kb-chunk"
            :style="{ flexGrow: item.grow }"
          >
            <span class="kb-chunk-name">切片 {{ item.index }}</span>
            <span class="kb-chunk-size">{{ item.size }}</span>
            <!-- 重叠区贴在右缘：宽度随 chunkOverlap / chunkSize 变化，最后一篇没有后继片 -->
            <span
              v-if="item.index < chunkPreview.length"
              class="kb-chunk-overlap"
              :style="{ width: `${overlapRatio}%` }"
            />
          </div>
        </div>
        <p class="kb-hint kb-hint-block">
          相邻切片重叠 {{ ingest.chunkOverlap }} 字符（不超过片长一半）；
          短于 {{ ingest.minChunkSize }} 字符的尾片会并入上一片。
        </p>
      </section>
    </el-tab-pane>

    <el-tab-pane label="检索与重排" name="retrieval">
      <section class="kb-card">
        <h4 class="kb-card-title">召回数量</h4>
        <div class="kb-grid-2">
          <div class="kb-field">
            <div class="kb-label">
              单库最终条数
              <code class="kb-key">topK</code>
              <span class="kb-range">{{ RETRIEVAL_RANGE.topK.min }}–{{ RETRIEVAL_RANGE.topK.max }}</span>
            </div>
            <el-input-number
              :model-value="retrieval.topK"
              :min="RETRIEVAL_RANGE.topK.min"
              :max="RETRIEVAL_RANGE.topK.max"
              :step="1"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setInt('topK', v)"
            />
            <div class="kb-hint">单个库最终返回几条，默认 5；对话里的模型不再决定条数。</div>
          </div>

          <div class="kb-field">
            <div class="kb-label">
              向量召回候选数
              <code class="kb-key">vectorTopK</code>
              <span class="kb-range">{{ RETRIEVAL_RANGE.vectorTopK.min }}–{{ RETRIEVAL_RANGE.vectorTopK.max }}</span>
            </div>
            <el-input-number
              :model-value="retrieval.vectorTopK"
              :min="RETRIEVAL_RANGE.vectorTopK.min"
              :max="RETRIEVAL_RANGE.vectorTopK.max"
              :step="1"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setInt('vectorTopK', v)"
            />
            <div class="kb-hint">向量一路先取多少条候选，默认 20；实际不会少于最终条数。</div>
          </div>

          <div class="kb-field">
            <div class="kb-label">
              关键词召回候选数
              <code class="kb-key">bm25TopK</code>
              <span class="kb-range">{{ RETRIEVAL_RANGE.bm25TopK.min }}–{{ RETRIEVAL_RANGE.bm25TopK.max }}</span>
            </div>
            <el-input-number
              :model-value="retrieval.bm25TopK"
              :min="RETRIEVAL_RANGE.bm25TopK.min"
              :max="RETRIEVAL_RANGE.bm25TopK.max"
              :step="1"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setInt('bm25TopK', v)"
            />
            <div class="kb-hint">关键词一路先取多少条候选，默认 20；库只支持向量时忽略。</div>
          </div>

          <div class="kb-field">
            <div class="kb-label">
              同篇最多几段
              <code class="kb-key">maxChunksPerDoc</code>
              <span class="kb-range">
                {{ RETRIEVAL_RANGE.maxChunksPerDoc.min }}–{{ RETRIEVAL_RANGE.maxChunksPerDoc.max }}
              </span>
            </div>
            <el-input-number
              :model-value="retrieval.maxChunksPerDoc"
              :min="RETRIEVAL_RANGE.maxChunksPerDoc.min"
              :max="RETRIEVAL_RANGE.maxChunksPerDoc.max"
              :step="1"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setInt('maxChunksPerDoc', v)"
            />
            <div class="kb-hint">同一篇文档最多占几段，防止一篇长文吃光名额，默认 3。</div>
          </div>

          <div class="kb-field">
            <div class="kb-label">
              多库合并后条数
              <code class="kb-key">mergedTopK</code>
              <span class="kb-range">{{ RETRIEVAL_RANGE.mergedTopK.min }}–{{ RETRIEVAL_RANGE.mergedTopK.max }}</span>
            </div>
            <div class="kb-inline">
              <el-input-number
                :model-value="retrieval.mergedTopK ?? undefined"
                :min="RETRIEVAL_RANGE.mergedTopK.min"
                :max="RETRIEVAL_RANGE.mergedTopK.max"
                :step="1"
                controls-position="right"
                placeholder="留空按各库之和"
                class="kb-number"
                @update:model-value="(v: number | null | undefined) => setMergedTopK(v)"
              />
              <!-- 数字框没有清空按钮，这里给一个显式「自动」入口，免得只能靠删空置空 -->
              <button v-if="retrieval.mergedTopK != null" type="button" class="kb-reset" @click="setMergedTopK(null)">
                改回自动
              </button>
            </div>
            <div class="kb-hint">留空表示自动：按各选中库 topK 之和计算，且不写这个键。</div>
          </div>
        </div>
      </section>

      <section class="kb-card">
        <h4 class="kb-card-title">高级融合</h4>
        <div class="kb-grid-2">
          <div class="kb-field">
            <div class="kb-label">
              RRF 平滑常数
              <code class="kb-key">rrfK</code>
              <span class="kb-range">{{ RETRIEVAL_RANGE.rrfK.min }}–{{ RETRIEVAL_RANGE.rrfK.max }}</span>
            </div>
            <el-input-number
              :model-value="retrieval.rrfK"
              :min="RETRIEVAL_RANGE.rrfK.min"
              :max="RETRIEVAL_RANGE.rrfK.max"
              :step="10"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setInt('rrfK', v)"
            />
            <div class="kb-hint">平滑常数，常用 60；越大越弱化排名差异。</div>
          </div>

          <div class="kb-field">
            <div class="kb-label">
              RRF 融合后条数
              <code class="kb-key">rrfTopN</code>
              <span class="kb-range">{{ RETRIEVAL_RANGE.rrfTopN.min }}–{{ RETRIEVAL_RANGE.rrfTopN.max }}</span>
            </div>
            <el-input-number
              :model-value="retrieval.rrfTopN"
              :min="RETRIEVAL_RANGE.rrfTopN.min"
              :max="RETRIEVAL_RANGE.rrfTopN.max"
              :step="1"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setInt('rrfTopN', v)"
            />
            <div class="kb-hint">两路融合后保留多少条，默认 20；运行时会不低于单库最终条数。</div>
          </div>

          <div class="kb-field">
            <div class="kb-label">
              向量分阈值
              <code class="kb-key">scoreThreshold</code>
              <span class="kb-range">{{ RETRIEVAL_RANGE.scoreThreshold.min }}–{{ RETRIEVAL_RANGE.scoreThreshold.max }}</span>
            </div>
            <el-input-number
              :model-value="retrieval.scoreThreshold"
              :min="RETRIEVAL_RANGE.scoreThreshold.min"
              :max="RETRIEVAL_RANGE.scoreThreshold.max"
              :step="0.05"
              :precision="2"
              controls-position="right"
              class="kb-number"
              @update:model-value="(v: number | undefined) => setDecimal(v)"
            />
            <div class="kb-hint">低于该分数的向量命中丢弃，默认 0.5；全被过滤时仍回退保留原召回。</div>
          </div>
        </div>

        <div class="kb-switches">
          <div class="kb-switch-row">
            <div class="kb-switch-info">
              <span class="kb-switch-title">
                尝试 RRF 融合
                <code class="kb-key">rrfEnabled</code>
              </span>
              <span class="kb-switch-desc">多路召回融合；库只支持向量时自动退化为纯向量排序。</span>
            </div>
            <el-switch :model-value="retrieval.rrfEnabled" @update:model-value="setBool('rrfEnabled', $event)" />
          </div>

          <div class="kb-switch-row">
            <div class="kb-switch-info">
              <span class="kb-switch-title">
                启用本库重排
                <code class="kb-key">rerankEnabled</code>
              </span>
              <span class="kb-switch-desc">新建库时默认开；需基础信息里选中 Rerank 实例才真正生效，Agent 侧配了重排以 Agent 为准。</span>
            </div>
            <el-switch :model-value="retrieval.rerankEnabled" @update:model-value="setBool('rerankEnabled', $event)" />
          </div>

          <div class="kb-switch-row">
            <div class="kb-switch-info">
              <span class="kb-switch-title">
                查询改写
                <code class="kb-key">rewriteEnabled</code>
              </span>
              <span class="kb-switch-desc">新建库时默认开；改写所用模型在 Agent「模型配置」的 REWRITE 槽选择。</span>
            </div>
            <el-switch :model-value="retrieval.rewriteEnabled" @update:model-value="setBool('rewriteEnabled', $event)" />
          </div>
        </div>
      </section>
    </el-tab-pane>
  </el-tabs>
</template>

<script setup lang="ts">
/**
 * 知识库新建 / 编辑弹窗的三 Tab 配置区。所属域：rag。
 *
 * 职责：
 * <ul>
 *   <li>承载「入库与切片」「检索与重排」两块参数，把「基础信息」留给父级用 #base 插槽渲染；</li>
 *   <li>把检索 / 入库参数拆成可读表单项，附切片示意（只按三个数字画比例，不是真实文档预览）；</li>
 *   <li>值只通过 update 事件回传给父级，组件自身不持有业务状态，切 Tab 不会丢值。</li>
 * </ul>
 * 约束：区间、默认值、相对关系全部来自 {@link @/utils/ragKbParams}，不在组件里另造一套；
 * 未展示的合法键（extra）由父级在保存时带回，组件不参与序列化。
 */
import { computed } from 'vue'
import {
  INGEST_RANGE,
  RETRIEVAL_RANGE,
  clampIngest,
  defaultIngestParams,
  defaultRetrievalParams
} from '@/utils/ragKbParams'
import type { IngestParams, RetrievalParams } from '@/utils/ragKbParams'

const props = defineProps<{
  /** 当前 Tab：base / ingest / retrieval；由父级持有，每次打开弹窗重置回 base */
  tab: string
  /** 当前检索参数 */
  retrieval: RetrievalParams
  /** 当前入库参数 */
  ingest: IngestParams
}>()

const emit = defineEmits<{
  'update:tab': [tab: string]
  'update:retrieval': [next: RetrievalParams]
  'update:ingest': [next: IngestParams]
}>()

/**
 * Tab 切换：el-tabs 回传 string | number，统一转成字符串再交给父级。
 *
 * @param name 选中的 Tab 名
 */
function onTabChange(name: string | number) {
  emit('update:tab', String(name))
}

/** 重叠上限随切片长度变化，与后端「不超过片长一半」一致 */
const overlapMax = computed(() => Math.max(0, Math.floor(props.ingest.chunkSize / 2)))

/** 示意块里重叠条占单片的比例；后端限制不超一半，这里再夹一次防脏值撑破布局 */
const overlapRatio = computed(() => {
  const size = props.ingest.chunkSize > 0 ? props.ingest.chunkSize : 1
  return Math.min(50, Math.max(0, (props.ingest.chunkOverlap / size) * 100))
})

/**
 * 切片示意块：只按三个输入画比例，不读真实文档。
 * 前两片按当前片长画满，第三片刻成尾片（长度取过短阈值，正好表达「短于此值会并入上一片」）。
 * 占比按字符数占总量的比例；尾片留 18% 下限，避免阈值很小时肉眼看不见。
 *
 * @return 三片的展示数据（序号 / 文案 / flex 占比）
 */
const chunkPreview = computed(() => {
  const size = Math.max(1, props.ingest.chunkSize)
  // 尾片代表「刚够过短阈值」的短片；阈值 0 时也留 1，避免画成一条线
  const tail = Math.min(size, Math.max(1, props.ingest.minChunkSize))
  const total = size * 2 + tail
  return [
    { index: 1, size: `约 ${size} 字符`, grow: size / total },
    { index: 2, size: `约 ${size} 字符`, grow: size / total },
    { index: 3, size: `尾片 约 ${tail} 字符`, grow: Math.max(0.18, tail / total) }
  ]
})

/**
 * 写回检索参数整体对象（父级直接替换，脏检查才能看到变化）。
 *
 * @param key   字段名
 * @param value 新值
 */
function patchRetrieval<K extends keyof RetrievalParams>(key: K, value: RetrievalParams[K]) {
  const next: RetrievalParams = { ...props.retrieval }
  next[key] = value
  emit('update:retrieval', next)
}

/**
 * 整数输入：控件被清空时 el-input-number 会给出 undefined，这里回落该字段默认值。
 *
 * @param key   字段名
 * @param value 控件值
 */
function setInt<K extends 'topK' | 'maxChunksPerDoc' | 'vectorTopK' | 'bm25TopK' | 'rrfK' | 'rrfTopN'>(
  key: K,
  value: number | undefined
) {
  const fallback = defaultRetrievalParams()[key]
  patchRetrieval(key, (value ?? fallback) as RetrievalParams[K])
}

/**
 * 向量分阈值：清空回落 0.5，保留两位小数（与控件精度一致）。
 *
 * @param value 控件值
 */
function setDecimal(value: number | undefined) {
  const next = value == null || Number.isNaN(value) ? defaultRetrievalParams().scoreThreshold : value
  patchRetrieval('scoreThreshold', Math.round(next * 100) / 100)
}

/**
 * 多库合并条数：允许为空；清空即「按各库 topK 之和」。
 *
 * @param value 控件值
 */
function setMergedTopK(value: number | null | undefined) {
  patchRetrieval('mergedTopK', value == null ? null : Math.round(value))
}

/**
 * 布尔开关写回；Element Plus 可能给出 string | number | boolean，只有 true 才算开。
 *
 * @param key   字段名
 * @param value 开关值
 */
function setBool(key: 'rrfEnabled' | 'rerankEnabled' | 'rewriteEnabled', value: string | number | boolean) {
  patchRetrieval(key, value === true)
}

/**
 * 切片长度：改小后立即收紧重叠与最小片长，避免出现必然被后端改写的组合。
 *
 * @param value 控件值
 */
function setChunkSize(value: number | undefined) {
  const fallback = defaultIngestParams().chunkSize
  emit('update:ingest', clampIngest({ ...props.ingest, chunkSize: value ?? fallback }))
}

/**
 * 入库参数整数写入，并统一走 clampIngest 收敛相对关系。
 *
 * @param key      字段名
 * @param value    控件值
 * @param fallback 清空时的兜底
 */
function setIngestInt(key: 'chunkOverlap' | 'minChunkSize', value: number | undefined, fallback: number) {
  emit('update:ingest', clampIngest({ ...props.ingest, [key]: value ?? fallback }))
}
</script>

<style scoped>
/* Tab 条：与弹窗内容同宽；只留底边发丝线，选中项走 6px 圆角浅紫底（DESIGN 的页签口径） */
.kb-config-tabs :deep(.el-tabs__header) {
  margin: 0 0 16px;
}

/* 给 28px 的标签留出上下呼吸位，否则浅紫底会紧贴分隔线 */
.kb-config-tabs :deep(.el-tabs__nav) {
  gap: 4px;
  padding: 0 0 8px;
}

.kb-config-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background: var(--ah-border-soft, #f2f6fc);
}

.kb-config-tabs :deep(.el-tabs__item) {
  height: 28px;
  padding: 0 12px;
  border-radius: var(--ah-radius-md, 6px);
  color: var(--ah-text-muted, #6b7280);
  font-size: 13px;
  font-weight: 500;
  line-height: 28px;
  transition: background-color 150ms ease, color 150ms ease;
}

.kb-config-tabs :deep(.el-tabs__item:hover) {
  background: rgba(255, 255, 255, 0.7);
  color: var(--ah-text, #1f2937);
}

.kb-config-tabs :deep(.el-tabs__item.is-active) {
  background: var(--el-color-primary-light-9, #f4f3ff);
  color: var(--ah-primary, #635bff);
  font-weight: 600;
}

/* 选中态由浅紫底表达，去掉默认下划线指示条 */
.kb-config-tabs :deep(.el-tabs__active-bar) {
  display: none;
}

/* 不重切说明：只用琥珀色文字做提醒，不加底色与装饰图标，免得和下方分组卡抢层级 */
.kb-notice {
  margin: 0 0 14px;
  padding: 0 0 0 10px;
  border-left: 2px solid var(--ah-warning);
  color: var(--ah-warning);
  font-size: 12px;
  line-height: 1.6;
}

/* 分组卡片：浅灰底 + 8px 圆角，无描边 */
.kb-card {
  margin-bottom: 14px;
  padding: 16px;
  border-radius: var(--ah-radius-lg, 8px);
  background: var(--ah-bg, #f5f7fa);
}

.kb-card:last-child {
  margin-bottom: 0;
}

.kb-card-title {
  margin: 0 0 12px;
  color: var(--ah-text, #1f2937);
  font-size: 13px;
  font-weight: 600;
}

.kb-grid-2,
.kb-grid-3 {
  display: grid;
  gap: 4px 16px;
}

.kb-grid-2 {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.kb-grid-3 {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.kb-field {
  min-width: 0;
  padding: 6px 0;
}

.kb-label {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ah-text, #1f2937);
  font-size: 13px;
  line-height: 1.4;
}

/* 键名用等宽体，便于对照库里 JSON；它是辅助信息，用可读灰 */
.kb-key {
  color: var(--ah-text-muted, #6b7280);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.kb-range {
  margin-left: auto;
  color: var(--ah-text-muted, #6b7280);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.kb-number {
  width: 100%;
  max-width: 180px;
  margin-top: 6px;
}

.kb-inline {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kb-reset {
  padding: 0;
  border: none;
  background: none;
  color: var(--ah-primary, #635bff);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
}

.kb-reset:hover {
  color: var(--ah-primary-hover, #5146f5);
  text-decoration: underline;
}

.kb-hint {
  margin-top: 5px;
  color: var(--ah-text-muted, #6b7280);
  font-size: 12px;
  line-height: 1.5;
}

.kb-hint-block {
  margin: 10px 0 0;
}

/* 切片示意：三片并排，宽度按字符占比；重叠条贴在每片右缘 */
.kb-chunks {
  display: flex;
  align-items: stretch;
}

.kb-chunk {
  position: relative;
  /* basis 归零，宽度完全由行内 flex-grow（字符占比）决定，三片比例才准 */
  flex: 1 1 0;
  min-width: 0;
  padding: 12px 10px;
  border-radius: var(--ah-radius-md, 6px);
  background: var(--el-color-primary-light-9, #f4f3ff);
  text-align: center;
  overflow: hidden;
}

.kb-chunk + .kb-chunk {
  margin-left: 8px;
}

.kb-chunk-name {
  display: block;
  color: var(--ah-text, #1f2937);
  font-size: 12px;
  font-weight: 600;
}

.kb-chunk-size {
  display: block;
  margin-top: 2px;
  color: var(--ah-text-muted, #6b7280);
  font-size: 12px;
}

/* 重叠区：宽度按 overlap / chunkSize 走，压在右缘，最后一篇不画 */
.kb-chunk-overlap {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  border-radius: 0 var(--ah-radius-md, 6px) var(--ah-radius-md, 6px) 0;
  background: rgba(99, 91, 255, 0.16);
  pointer-events: none;
}

.kb-switches {
  margin-top: 6px;
  border-top: 1px solid var(--ah-border-soft, #f2f6fc);
}

.kb-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 0;
  border-bottom: 1px solid var(--ah-border-soft, #f2f6fc);
}

.kb-switch-row:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.kb-switch-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.kb-switch-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ah-text, #1f2937);
  font-size: 13px;
  font-weight: 500;
}

.kb-switch-desc {
  color: var(--ah-text-muted, #6b7280);
  font-size: 12px;
  line-height: 1.5;
}

/* 窄屏：两列 / 三列一起收成单列，Tab 条横向滚动，不挤压表单 */
@media (max-width: 680px) {
  .kb-grid-2,
  .kb-grid-3 {
    grid-template-columns: minmax(0, 1fr);
  }

  .kb-config-tabs :deep(.el-tabs__nav-wrap) {
    overflow-x: auto;
  }

  .kb-config-tabs :deep(.el-tabs__item) {
    padding: 0 10px;
  }
}
</style>
