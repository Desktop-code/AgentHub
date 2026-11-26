<template>
  <div class="wf-records">
    <section v-if="!detailOpen" class="wf-records__block">
      <!--
        列表上方唯一的外链入口：这条工作流的调用追踪。
        落点与查询口径跟原顶栏「运行历史」完全一致，不在每条记录上再挂跳转。
      -->
      <div class="wf-records__trace">
        <el-link type="primary" :underline="false" @click="emit('traces')">
          调用追踪
          <el-icon class="el-icon--right"><TopRight /></el-icon>
        </el-link>
      </div>
      <div v-loading="historyLoading" class="wf-records__list">
        <div v-if="!historyLoading && !rows.length" class="wf-records__muted">还没有调试记录</div>
        <!-- 一行一条：行首向下箭头表示「点开展开这条的运行过程」 -->
        <button
          v-for="item in rows"
          :key="item.row.id"
          type="button"
          class="wf-records__row"
          :class="{ 'is-active': selectedId === item.row.id }"
          @click="emit('open', item.row.id)"
        >
          <el-icon class="wf-records__arrow"><ArrowDown /></el-icon>
          <span class="wf-records__id">#{{ item.row.id }}</span>
          <el-tag size="small" :type="runTagType(item.row.status)" effect="plain">
            {{ runStatusLabel(item.row.status) }}
          </el-tag>
          <span v-if="item.version.versionText" class="wf-records__version">{{ item.version.versionText }}</span>
          <el-tag v-if="item.version.unpublished" size="small" type="info" effect="plain">未发布</el-tag>
          <span class="wf-records__time">{{ formatTime(item.row.createTime) }}</span>
          <!-- 超长单行截断，悬停看全文；没有记下问题的旧记录这一段留空 -->
          <span v-if="item.row.query" class="wf-records__query" :title="item.row.query">{{ item.row.query }}</span>
        </button>
      </div>
    </section>

    <section v-else class="wf-records__block">
      <!-- 过程页顶部仍保留这条记录的信息，行首向上箭头表示「点一下收起回列表」 -->
      <button type="button" class="wf-records__row is-open" @click="emit('back')">
        <el-icon class="wf-records__arrow"><ArrowUp /></el-icon>
        <template v-if="selectedRow">
          <span class="wf-records__id">#{{ selectedRow.id }}</span>
          <el-tag size="small" :type="runTagType(selectedRow.status)" effect="plain">
            {{ runStatusLabel(selectedRow.status) }}
          </el-tag>
          <span v-if="selectedVersion.versionText" class="wf-records__version">{{ selectedVersion.versionText }}</span>
          <el-tag v-if="selectedVersion.unpublished" size="small" type="info" effect="plain">未发布</el-tag>
          <span class="wf-records__time">{{ formatTime(selectedRow.createTime) }}</span>
          <span v-if="selectedRow.query" class="wf-records__query" :title="selectedRow.query">{{ selectedRow.query }}</span>
        </template>
        <template v-else>
          <span class="wf-records__id">#{{ selectedId }}</span>
        </template>
      </button>
      <slot name="detail" />
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 调试浮动框的「记录」页。所属域：workflow 的前端。
 *
 * <p>只放以往的调试，一行一条：`#号`、状态、版本、未发布标记、时间、这次调试的用户问题。
 * 页签已经叫「记录」，这里不再加标题；列表最上面只留一条「调用追踪」外链。
 * <p>已发布版本列表与切换入口在画布顶栏「发布」旁，这里不再重复；调用追踪只在列表上方留一条外链。
 * 点开一条后，过程区由父层用首页同一张卡渲染。
 */
import { computed } from 'vue'
import { ArrowDown, ArrowUp, TopRight } from '@element-plus/icons-vue'
import type { WorkflowRunSummary } from '@/types/workflow'
import { debugVersionTag, type DebugVersionTag } from './workflowDebugCard'

const props = defineProps<{
  /** 以往的调试 */
  history: WorkflowRunSummary[]
  /** 调试记录加载中 */
  historyLoading: boolean
  /** 当前点开的 run；没有点开时为空 */
  selectedId?: number | null
  /** 正在看某一条的过程，而不是记录列表 */
  detailOpen: boolean
}>()

const emit = defineEmits<{
  /** 点开一条调试记录 */
  open: [runId: number]
  /** 从过程回到记录列表 */
  back: []
  /** 点「调用追踪」：由父层带着这条工作流跳追踪页 */
  traces: []
}>()

/** 列表行：把版本标记先算好，模板里不再重复取 */
const rows = computed<Array<{ row: WorkflowRunSummary; version: DebugVersionTag }>>(() =>
  props.history.map((row) => ({
    row,
    version: debugVersionTag(row.releaseId, row.releaseVersion, row.draftBaseReleaseVersion)
  }))
)

/** 过程页头部那条记录：从已加载列表里按 id 找；找不到时退回只显示 id */
const selectedRow = computed<WorkflowRunSummary | null>(
  () => props.history.find((row) => row.id === props.selectedId) ?? null
)

/** 过程页头部这条记录的版本标记；与列表同一口径 */
const selectedVersion = computed<DebugVersionTag>(() => {
  const row = selectedRow.value
  return debugVersionTag(row?.releaseId, row?.releaseVersion, row?.draftBaseReleaseVersion)
})

/**
 * 调试记录的状态中文名。
 *
 * @param status run 状态码
 * @return 标签文案
 */
function runStatusLabel(status: string): string {
  const map: Record<string, string> = {
    CREATED: '已创建',
    RUNNING: '运行中',
    PAUSED_HUMAN: '等待人工',
    WAITING_REPLY: '等待回复',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELLED: '已取消'
  }
  return map[status] || status
}

/**
 * 状态标签颜色。进行中用主色，等待用警告色。
 *
 * @param status run 状态码
 * @return Element Plus tag 类型
 */
function runTagType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'PAUSED_HUMAN' || status === 'WAITING_REPLY') return 'warning'
  if (status === 'FAILED' || status === 'CANCELLED') return 'danger'
  if (status === 'RUNNING' || status === 'CREATED') return 'primary'
  return 'info'
}

/**
 * 列表时间截到秒。
 *
 * @param value 时间字符串
 * @return 展示文本
 */
function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<style scoped>
.wf-records {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.wf-records__block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.wf-records__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 展开 / 收起箭头：与行文字同色系但更弱，只做方向提示 */
.wf-records__arrow {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

/* 过程页头部这条记录：常态浅底，点一下收起回列表 */
.wf-records__row.is-open {
  background: var(--el-fill-color-light);
}

.wf-records__time,
.wf-records__muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.wf-records__trace {
  display: flex;
  align-items: center;
  font-size: 13px;
}

.wf-records__list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 36px;
}

.wf-records__row {
  width: 100%;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
}

.wf-records__row:hover,
.wf-records__row.is-active {
  background: var(--el-fill-color-light);
}

/* 除用户问题外都不让收缩，保证一行内各列位置稳定 */
.wf-records__id,
.wf-records__version,
.wf-records__time,
.wf-records__row .el-tag {
  flex: none;
}

/* 用户问题吃剩余宽度：单行截断，悬停由 title 给全文，不折行撑乱这一排 */
.wf-records__query {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
