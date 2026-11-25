<template>
  <!--
    工作流时间线：按投影整理好的条目树逐层画。普通步骤是步骤卡，循环是套在体节点外面的一层框。
    框里可以再嵌框（嵌套循环），同一循环的后续轮次追加在同一个框里。
    本组件只画结构，不推算进度、不请求接口。
  -->
  <div class="wf-timeline" :class="{ 'is-nested': depth > 0 }">
    <template
      v-for="(entry, position) in entries"
      :key="entry.kind === 'loop' ? 'loop:' + entry.loop.key : 'step:' + stepKeyOf(entry, position)"
    >
      <!-- 循环：一层分组框，标题写清轮次与状态 -->
      <div v-if="entry.kind === 'loop'" class="wf-loop" :class="'is-' + entry.loop.state">
        <button
          type="button"
          class="wf-loop-head"
          :aria-expanded="loopOpen(entry.loop.key)"
          @click.stop.prevent="toggleLoop(entry.loop.key)"
        >
          <span class="wf-loop-chevron" :class="{ open: loopOpen(entry.loop.key) }" aria-hidden="true" />
          <span class="wf-loop-title">{{ entry.loop.title }}</span>
          <span v-if="entry.loop.name && entry.loop.name !== entry.loop.code" class="wf-loop-code">
            {{ entry.loop.name }}
          </span>
          <span v-if="entry.loop.state === 'running'" class="wf-spin" aria-hidden="true">⏳</span>
        </button>
        <!--
          框内：本轮体节点按顺序排；上一轮整段保留在它前面。
          收起只影响显示范围，已流出的正文与已发生的轮次都还在。
        -->
        <div class="wf-loop-fold" :class="{ 'is-collapsed': !loopOpen(entry.loop.key) }">
          <div class="wf-loop-fold-body">
            <WorkflowTimeline
              :entries="entry.entries"
              :turn="turn"
              :card="card"
              :choice-enabled="choiceEnabled"
              :workbench="workbench"
              :peers="peers"
              :depth="depth + 1"
              @choose="emit('choose', $event)"
              @other="emit('other')"
            />
          </div>
        </div>
      </div>
      <!-- 普通步骤：编号沿用节点在图里的位置，框内框外都不重新从 1 数 -->
      <WorkflowStepItem
        v-else
        :turn="turn"
        :card="card"
        :node="entry.node"
        :peers="stepPeers"
        :order="entry.index + 1"
        :choice-enabled="choiceEnabled"
        :workbench="workbench"
        @choose="emit('choose', $event)"
        @other="emit('other')"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 工作流时间线的递归渲染。所属域：runtime（前端展示）。
 *
 * <p>职责：把 {@code buildWorkflowTimeline} 整理出的条目树画出来——循环是一层框，
 * 普通步骤交给 {@link WorkflowStepItem}。循环框内继续用本组件递归，因此嵌套循环
 * 会自然形成「外层框里套内层框」，不需要第二套投影或第二份时间线。
 *
 * <p>框的开合由用户在框标题上点开 / 收起，状态存在本组件内部（按「轮 + 参与者 + 框编码」）；
 * 投影刷新（直播追加、刷新回放）不会把它掰回去，已关掉的框保持关着。
 */
import { computed, inject, provide, reactive } from 'vue'
import type { ChatTurn, TrialWorkflowNodeView } from '@/types/runtime'
import type { ParticipantCard, ParticipantChoiceOption } from '@/components/runtime/workbench/workbenchParticipantFeed'
import WorkflowStepItem from '@/components/runtime/workbench/chat/WorkflowStepItem.vue'
import type { WfTimelineEntry } from '@/components/runtime/workbench/chat/workflowLoopFrame'

const props = withDefaults(
  defineProps<{
    /** 条目树 */
    entries: WfTimelineEntry[]
    /** 本轮助手消息 */
    turn: ChatTurn
    /** 参与者卡片（取终态与节点正文口径） */
    card: ParticipantCard
    /** 当前选项是否可点 */
    choiceEnabled: boolean
    /** 是否工作台皮肤 */
    workbench?: boolean
    /** 本层节点（算「第 N 轮」时只跟同层比） */
    peers?: TrialWorkflowNodeView[]
    /** 嵌套层级：0 为根层 */
    depth?: number
  }>(),
  { workbench: true, peers: () => [], depth: 0 }
)

const emit = defineEmits<{
  /** 用户点了停等选项 */
  choose: [option: ParticipantChoiceOption]
  /** 用户点了平台追加的「其它 / 自填」行；向上只转交「进入输入」，不提交 */
  other: []
}>()

/** 折叠覆盖表的注入键：各层共享同一份，同一个框在哪一层看都是同一个开合状态 */
const FOLD_KEY = 'wfLoopFold'

/**
 * 框的开合覆盖表。根层自己建，嵌套层注入祖先那份。
 *
 * <p>不用 `hasOwnProperty`：`reactive` 首次写入新键时要能被追踪，读值即可建立依赖。
 */
const injected = inject<Record<string, boolean> | null>(FOLD_KEY, null)
const foldOverrides = injected || reactive<Record<string, boolean>>({})
if (!injected) {
  provide(FOLD_KEY, foldOverrides)
}

/** 本层的步骤节点（同层比较「第 N 轮」） */
const stepPeers = computed(() => props.peers)

/**
 * 某个框的折叠键：轮 + 参与者 + 框编码，回放重建组件时仍落到同一个键上。
 *
 * @param code 循环编码
 * @return 折叠键
 */
function foldKeyOf(code: string): string {
  return `${props.turn.key}:${props.card.key}:loop:${code}`
}

/** 框当前是否展开：默认展开，用户点过就以点击为准 */
function loopOpen(code: string): boolean {
  const value = foldOverrides[foldKeyOf(code)]
  return typeof value === 'boolean' ? value : true
}

/**
 * 点一次框标题：只改显示范围，不动已产出的内容。
 *
 * @param code 循环编码
 */
function toggleLoop(code: string): void {
  foldOverrides[foldKeyOf(code)] = !loopOpen(code)
}

/**
 * 同层里的稳定键：步骤用「编码 + 轮次 + 位置」，避免同一节点多轮撞键。
 *
 * @param entry    条目
 * @param position 位置
 * @return 列表键
 */
function stepKeyOf(entry: WfTimelineEntry, position: number): string {
  if (entry.kind === 'loop') {
    return entry.loop.key
  }
  const node = entry.node
  return `${node.code || 'node'}#${node.attempt || 1}#${position}`
}
</script>

<style scoped src="../../../../styles/workflow-timeline.css"></style>
<style scoped src="../../../../styles/workflow-loop.css"></style>
