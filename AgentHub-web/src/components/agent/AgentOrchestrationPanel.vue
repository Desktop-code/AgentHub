<template>
  <div class="orch-panel">
    <!-- 顶部：模板 + 一句话说明；与底部策略卡同源，避免两处展示不一致 -->
    <section class="orch-card orch-head">
      <div class="head-main">
        <h3>编排：{{ agentName }}</h3>
        <p class="head-sub">
          五种模板：路由并行（判路由 → 多子并行）、固定串行（跳过判路由，按排序依次执行）、
          固定并行（跳过判路由，全部子吃同一份原问并行）、评审环（执行者出稿 → 评审 → 有限回修）、
          路由后串行（判路由选出专家与子问 → 按判定顺序串行）。
          本页只读写配置，执行语义仍由
          <code>RouterTrialExecutor</code> 负责。
        </p>
      </div>
      <div class="head-tags">
        <el-tag type="primary" effect="plain">{{ templateLabel }}</el-tag>
        <!-- 共享草稿区会影响子能看到的工具：页头显式标一下，避免翻到策略区才发现 -->
        <el-tag v-if="contextPolicy === MA_CONTEXT_SHARED_SCRATCH" type="warning" effect="plain">
          上下文 · 共享草稿
        </el-tag>
        <el-tag :type="agentStatus === 1 ? 'success' : 'info'" effect="plain">
          {{ agentStatus === 1 ? '根已启用' : '根已停用' }}
        </el-tag>
      </div>
    </section>

    <!-- 关系概览：轻量静态树，不引入 X6 / VueFlow；串行模板用箭头示意，子多时自然换行 -->
    <section class="orch-card">
      <div class="card-head">
        <div>
          <h4>关系概览</h4>
          <span class="hint">
            根 → 候选子（一层）；{{ serialTemplate ? '串行模板按判定/排序依次执行' : isCriticTemplate ? '评审环由执行者出稿、评审专家判定' : '并行模板同时派发' }}；灰色表示停用或不可用
          </span>
        </div>
        <span class="hint">候选 {{ routes.length }} · 启用 {{ enabledCount }}</span>
      </div>
      <div class="map">
        <div class="root-node" :class="{ off: agentStatus !== 1 }">
          <strong>{{ agentName }}</strong>
          <span>编排根 · ROUTER</span>
          <el-tag v-if="agentStatus !== 1" type="info" size="small" effect="plain">已停用</el-tag>
        </div>
        <div class="map-stem" :class="{ 'is-sequence': serialTemplate }" />
        <div v-if="routes.length" class="map-children" :class="{ 'is-sequence': serialTemplate }">
          <template v-for="(row, index) in routes" :key="row.id">
            <div
              class="child-node"
              :class="{
                off: row.status !== 1 || row.childStatus !== 1 || !isModeAllowed(row.childRunMode),
                blocked: row.childAllowOrchestrated === 0
              }"
            >
              <strong :title="childLabel(row)">{{ childLabel(row) }}</strong>
              <span class="child-key">{{ row.routeKey || 'route_key 待生成' }}</span>
              <div class="child-tags">
                <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'" effect="plain">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
                <!-- 子运行模式：ReAct 走默认标签，计划执行用绿色区分，避免和「失败」红混淆 -->
                <el-tag
                  size="small"
                  :type="row.childRunMode === MA_CHILD_MODE_PLAN ? 'success' : 'primary'"
                  effect="plain"
                >
                  {{ childRunModeText(row.childRunMode) }}
                </el-tag>
                <!-- 子后来被缩回仅 ReAct 或关掉「允许被编排调用」：保存前由校验区报硬错误 -->
                <el-tag
                  v-if="row.childRunMode && !isModeAllowed(row.childRunMode)"
                  size="small"
                  type="warning"
                  effect="plain"
                >
                  未获允许
                </el-tag>
                <el-tag
                  v-if="row.childAllowOrchestrated === 0"
                  size="small"
                  type="warning"
                  effect="plain"
                >
                  未允许被编排
                </el-tag>
              </div>
            </div>
            <!-- 串行模板：节点之间画箭头，提示「有先后顺序」，并行模板不画 -->
            <span v-if="serialTemplate && index < routes.length - 1" class="seq-arrow">→</span>
          </template>
        </div>
        <el-empty v-else description="还没有候选子 Agent" :image-size="48" />
        <!-- 评审环轻量示意：执行者 → 评审专家 → 虚线回修；静态节点，不引入 X6 -->
        <div v-if="isCriticTemplate" class="critic-map">
          <span class="critic-chip">
            <strong>{{ executorLabel }}</strong>
            <span class="critic-role">执行者</span>
          </span>
          <span class="critic-arrow">→ 评审 →</span>
          <span class="critic-chip" :class="{ off: !config.maCriticAgentId }">
            <strong>{{ criticLabel }}</strong>
            <span class="critic-role">评审专家</span>
          </span>
          <span class="critic-arrow dashed">⇠ 不同意则回修</span>
        </div>
        <!-- 路由后串行轻量示意：JUDGE 先选人，再按判定顺序串行；静态节点，不引入 X6 -->
        <div v-if="isRouterThenSequence" class="critic-map">
          <span class="critic-chip">
            <strong>JUDGE</strong>
            <span class="critic-role">判路由选专家与子问</span>
          </span>
          <span class="critic-arrow">→ 串行 →</span>
          <span class="critic-chip" :class="{ off: !enabledCount }">
            <strong>{{ enabledCount }}</strong>
            <span class="critic-role">个启用专家</span>
          </span>
          <span class="critic-arrow dashed">后步只看前置摘要</span>
        </div>
      </div>
    </section>

    <div class="orch-grid">
      <!-- 下左：候选列表；增删改/启停即时落库，与现网 /routes API 等价 -->
      <section class="orch-card">
        <div class="card-head">
          <div>
            <h4>候选列表</h4>
            <span class="hint">route_key / 意图 / 子 Agent / 启停</span>
          </div>
          <el-button v-if="canOperate" type="primary" @click="routeOpen = true">+ 添加候选</el-button>
        </div>
        <el-alert
          v-if="!savedRouter"
          title="当前运行模式是 Router 但尚未保存：保存后才能维护候选。"
          type="warning"
          show-icon
          :closable="false"
          class="block-alert"
        />
        <el-table class="cand-table" :data="routes" size="small" empty-text="还没有候选子 Agent">
          <el-table-column label="route_key" width="130">
            <template #default="{ row }">
              <code class="cand-key">{{ row.routeKey || '待生成' }}</code>
            </template>
          </el-table-column>
          <el-table-column label="意图" min-width="150" show-overflow-tooltip>
            <template #default="{ row }">{{ row.intent || '—' }}</template>
          </el-table-column>
          <el-table-column label="子 Agent" min-width="150">
            <template #default="{ row }">
              <div class="cand-name" :title="childLabel(row)">{{ childLabel(row) }}</div>
              <div class="cand-sub">
                {{ childRunModeText(row.childRunMode) }} · {{ row.childStatus === 1 ? '启用' : '停用' }}
                <span v-if="row.childRunMode && !isModeAllowed(row.childRunMode)" class="cand-blocked"> · 未获本编排允许</span>
                <span v-if="row.childAllowOrchestrated === 0" class="cand-blocked"> · 未允许被编排调用</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="启停" width="76" align="center">
            <template #default="{ row }">
              <el-switch
                v-if="canOperate"
                :model-value="row.status === 1"
                :loading="rowBusy === row.id"
                @change="(on: string | number | boolean) => onToggle(row, on === true)"
              />
              <el-tag v-else size="small" :type="row.status === 1 ? 'success' : 'info'" effect="plain">
                {{ row.status === 1 ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column v-if="canOperate" label="操作" width="112" align="center">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="removeRow(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 下右：策略；选择类字段进页面底栏保存，开关同样随底栏提交 -->
      <section class="orch-card">
        <div class="card-head">
          <div>
            <h4>策略</h4>
            <span class="hint">改完点页面底部「保存」生效</span>
          </div>
        </div>
        <el-form label-width="126px" class="strategy-form">
          <el-form-item label="编排模板">
            <el-select
              :model-value="config.maTemplate || MA_TEMPLATE_ROUTER_PARALLEL"
              :disabled="!canEdit"
              style="width: 100%"
              @change="onTemplateChange"
            >
              <el-option label="路由并行（判路由 → 多子并行）" value="ROUTER_PARALLEL" />
              <el-option label="固定串行（跳过判路由，按排序依次执行）" value="FIXED_SEQUENCE" />
              <el-option label="固定并行（跳过判路由，全部子一起跑）" value="FIXED_PARALLEL" />
              <el-option label="评审环（执行者出稿 → 评审 → 有限回修）" value="CRITIC_LOOP" />
              <el-option label="路由后串行（判路由 → 按判定顺序串行）" value="ROUTER_THEN_SEQUENCE" />
            </el-select>
            <div class="field-hint">
              五种模板都可保存；非法值回落「路由并行」。固定模板 / 评审环都没有判路由；
              评审环的参与者＝恰好 1 个执行者 + 1 个评审专家。
              <span v-if="isRouterThenSequence">
                先判路由选出专家与子问，再按判定顺序串行；后步只看前置摘要。
              </span>
            </div>
          </el-form-item>

          <!-- 评审环专属：评审专家 + 最大评审轮次；其它模板隐藏，但库里 maCritic* 不清空，切回可恢复 -->
          <template v-if="isCriticTemplate">
            <el-form-item label="评审专家" required>
              <el-select
                :model-value="config.maCriticAgentId ?? undefined"
                :disabled="!canEdit"
                filterable
                placeholder="选择评审专家（需已启用且允许被编排）"
                style="width: 100%"
                @change="(value: number | undefined) => onCriticChange(value)"
              >
                <el-option
                  v-for="item in criticOptions"
                  :key="item.id"
                  :label="`${item.name} · ${item.code} · ${childRunModeText(item.runMode)}`"
                  :value="item.id"
                />
              </el-select>
              <div class="field-hint">
                执行者出稿后由评审专家判定；不同意则回修（默认 1 轮）。
                评审专家不能与执行者相同，且不必出现在候选列表里。
              </div>
            </el-form-item>
            <el-form-item label="最大评审轮次">
              <el-input-number
                :model-value="criticRounds"
                :min="1"
                :max="MAX_CRITIC_ROUNDS_CAP"
                :step="1"
                :disabled="!canEdit"
                controls-position="right"
                style="width: 120px"
                @change="(value: number | undefined) => patchConfig({ maCriticMaxRounds: value ?? DEFAULT_CRITIC_MAX_ROUNDS })"
              />
              <div class="field-hint">
                1～3 轮，默认 1；每一轮＝一次评审判定 +（若 REVISE）一次执行者回修。
              </div>
            </el-form-item>
          </template>

          <el-form-item label="汇总策略">
            <el-radio-group
              :model-value="config.answerMode || 'CONCAT'"
              :disabled="!canEdit"
              @change="(value: string | number | boolean) => patchConfig({ answerMode: String(value) })"
            >
              <el-radio value="CONCAT">拼接 CONCAT</el-radio>
              <el-radio value="SUMMARIZE">汇总 SUMMARIZE</el-radio>
            </el-radio-group>
            <div class="field-hint">SUMMARIZE 用父 ROUTER 文本模型再整合一轮；失败自动降级拼接。</div>
          </el-form-item>

          <el-form-item label="上下文策略">
            <el-radio-group
              :model-value="contextPolicy"
              :disabled="!canEdit"
              @change="(value: string | number | boolean) => onContextChange(String(value))"
            >
              <el-radio :value="MA_CONTEXT_ISOLATED">多项隔离</el-radio>
              <el-radio
                :value="MA_CONTEXT_PRIOR_SUMMARY_ONLY"
                :disabled="!serialTemplate"
              >
                前置摘要（仅串行）
              </el-radio>
              <el-radio :value="MA_CONTEXT_SHARED_SCRATCH">共享草稿区</el-radio>
            </el-radio-group>
            <div class="field-hint">
              {{ contextHint }}
            </div>
            <!-- 串行模板选了共享草稿：字段单选，前置摘要不会同时生效，必须明确提示 -->
            <div v-if="serialTemplate && contextPolicy === MA_CONTEXT_SHARED_SCRATCH" class="field-warn">
              串行推荐「仅前置摘要」：选了共享草稿区后不再自动拼接前置摘要，后步需自己调用 ma_scratch_get 读取。
            </div>
          </el-form-item>

          <el-form-item
            v-if="!isFixedTemplate && !isCriticTemplate"
            :label="isRouterThenSequence ? '多意图串行' : '多意图并行'"
          >
            <el-switch
              :model-value="config.multiIntentEnabled !== false"
              :disabled="!canEdit"
              inline-prompt
              @change="(on: string | number | boolean) => patchConfig({ multiIntentEnabled: on === true })"
            />
            <div class="field-hint">
              {{ isRouterThenSequence
                ? '关闭后只跑最匹配的 1 个专家；开启才可能按判定顺序串行多个。'
                : '关闭后只派给最匹配的一个子；开启才可能多子并行。' }}
            </div>
          </el-form-item>

          <el-form-item v-if="!isCriticTemplate" label="单意图继承父历史">
            <el-switch
              :model-value="config.maAllowParentHistoryWhenSingle !== false"
              :disabled="!canEdit"
              @change="(on: string | number | boolean) => patchConfig({ maAllowParentHistoryWhenSingle: on === true })"
            />
            <div class="field-hint">
              仅「只有 1 个启用候选」时生效：开＝子可继承父会话历史，关＝硬隔离只看用户原问。
            </div>
          </el-form-item>

          <el-form-item :label="isRouterThenSequence ? '最大串行子数' : '最大并行子数'">
            <el-input-number
              :model-value="config.maxRouteChildren ?? 3"
              :min="1"
              :max="5"
              :step="1"
              :disabled="!canEdit"
              controls-position="right"
              style="width: 120px"
              @change="(value: number | undefined) => patchConfig({ maxRouteChildren: value ?? 3 })"
            />
            <div class="field-hint">
              {{ isRouterThenSequence
                ? '路由后串行：判定条数超过上限时只跑判定顺序前 N 个，其余标「已跳过」；串行执行不并行扇出。'
                : isSequenceTemplate
                  ? '固定串行按顺序跑全部候选，不受此上限限制。'
                  : isCriticTemplate
                    ? '评审环只跑 1 个执行者，不受此上限影响。'
                    : isFixedTemplate
                      ? '固定并行：超过上限的候选按排序标「已跳过」，不参与执行。'
                      : '路由并行：判路由最多返回这么多个意图；1～5，后端夹紧。' }}
            </div>
          </el-form-item>

          <el-form-item label="允许的子运行模式">
            <el-checkbox-group
              :model-value="allowedChildRunModes"
              :disabled="!canEdit"
              @change="onChildModesChange"
            >
              <el-checkbox :value="MA_CHILD_MODE_REACT" disabled>ReAct</el-checkbox>
              <el-checkbox :value="MA_CHILD_MODE_PLAN">计划执行</el-checkbox>
            </el-checkbox-group>
            <div class="field-hint">
              ReAct 恒可用；勾选「计划执行」后，可把 P&E 子挂为候选并真跑。子仍需自身开启「允许被编排调用」。
              若某个子开启「规划确认」，试跑会暂停等待批准（同时仅一个待批）。
            </div>
          </el-form-item>

          <el-form-item label="编排深度">
            <el-input model-value="1" disabled style="width: 88px" />
            <div class="field-hint">一层；子不得为 ROUTER（禁嵌套）。</div>
          </el-form-item>
        </el-form>
      </section>
    </div>

    <!-- 底部：校验关系图。前端按现网 RouteResp 字段校验，不发起新请求 -->
    <section class="orch-card">
      <div class="card-head">
        <div>
          <h4>关系校验</h4>
          <span class="hint">保存前先拦零候选、停用子、ROUTER 子与 route_key 冲突</span>
        </div>
        <el-button :loading="validating" @click="runValidation">校验关系图</el-button>
      </div>
      <div v-if="validation" class="validation-result">
        <el-alert v-if="validation.errors.length" type="error" :closable="false" show-icon title="校验未通过">
          <ul class="issue-list">
            <li v-for="(msg, index) in validation.errors" :key="`e-${index}`">{{ msg }}</li>
          </ul>
        </el-alert>
        <el-alert v-if="validation.warnings.length" type="warning" :closable="false" show-icon title="提醒">
          <ul class="issue-list">
            <li v-for="(msg, index) in validation.warnings" :key="`w-${index}`">{{ msg }}</li>
          </ul>
        </el-alert>
        <el-alert
          v-if="validation.ok"
          type="success"
          :closable="false"
          show-icon
          :title="`校验通过 · 启用候选 ${validation.enabledCount} 个 · 环检测：通过（一层结构，子不得为 ROUTER）`"
        />
      </div>
      <p v-else class="hint">尚未校验。点击右上「校验关系图」。</p>
    </section>

    <!-- 底部薄入口：只读看最近一轮试跑的编排泳道；无试跑会话时提示先跑一轮 -->
    <footer class="orch-foot">
      <span class="hint">只读：按最近一轮试跑会话拉调用追踪，展示判路由 → 子并行 → 汇总的泳道</span>
      <el-button size="small" :loading="laneOpening" @click="openLane">查看最近试跑泳道</el-button>
    </footer>

    <RouteBindDialog
      v-model="routeOpen"
      :parent-id="agentId"
      :bound-ids="routes.map((item) => item.childAgentId)"
      :allowed-child-run-modes="allowedChildRunModes"
      @submit="addRoute"
    />

    <el-dialog v-model="routeEditOpen" title="编辑候选" width="520px">
      <el-form label-width="88px">
        <el-form-item label="意图" required>
          <el-input
            v-model="routeDraft.intent"
            maxlength="255"
            type="textarea"
            :rows="2"
            placeholder="白话描述该子 Agent 负责什么，供判路由识别"
          />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="routeDraft.sortOrder" :min="0" :max="10000" controls-position="right" />
        </el-form-item>
        <el-collapse v-model="editAdvanced" class="route-advanced">
          <el-collapse-item title="高级 · 路由键" name="adv">
            <el-form-item label="路由键">
              <el-input v-model="routeDraft.routeKey" maxlength="64" placeholder="系统生成的稳定短码" />
              <div class="route-hint">改后须与判路由输出一致；默认不要改，避免已跑通的提示词对不上</div>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="routeEditOpen = false">取消</el-button>
        <el-button type="primary" :loading="routeSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!--
      最近一轮试跑的编排泳道（只读）。与试跑面板共用同一个组件，
      这里只负责找到该 Agent 最近的会话；没有会话时给中文提示，不新开列表。
    -->
    <el-drawer v-model="laneOpen" title="最近试跑 · 编排泳道" size="72%" append-to-body>
      <div v-loading="laneOpening" class="lane-drawer">
        <el-empty v-if="!laneOpening && !laneSessionId" description="该 Agent 还没有试跑会话，先试跑一轮再来看泳道" />
        <OrchestrationLane v-else :session-id="laneSessionId" :default-expanded="true" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * Router Agent 详情「编排」Tab。所属域：agent。
 *
 * 职责：把一层编排产品化成「关系概览 + 候选 CRUD + 策略 + 校验」四块。
 * 数据来源：
 * - 候选：复用现网 agent_route 的 /api/agent/agents/{id}/routes CRUD；
 * - 策略：复用 agent_def.runtime_config_json，经父组件底栏保存，不另开接口。
 * 约束：
 * - 五值模板都可保存：ROUTER_PARALLEL / FIXED_SEQUENCE / FIXED_PARALLEL / CRITIC_LOOP /
 *   ROUTER_THEN_SEQUENCE；
 *   两种串行模板（固定串行 / 路由后串行）默认前置摘要，切走时收敛回隔离；
 *   评审环额外配置评审专家与最大评审轮次；
 * - 不引入 X6 / VueFlow，关系图用静态布局（评审环 / 路由后串行用轻量节点示意）；
 * - 不改 RouterTrialExecutor 的隔离 / 并行 / CONCAT·SUMMARIZE 语义。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createRoute, deleteRoute, pageAgents, updateRoute } from '@/api/agent'
import { pageTrialSessions } from '@/api/runtime'
import { useUserStore } from '@/stores/user'
import RouteBindDialog from '@/components/agent/RouteBindDialog.vue'
import OrchestrationLane from '@/components/runtime/OrchestrationLane.vue'
import type { AgentCard, AgentRouteRow, AgentRuntimeConfig } from '@/types/agent'
import {
  MA_AGGREGATE_CONCAT,
  MA_AGGREGATE_SUMMARIZE,
  MA_CHILD_MODE_PLAN,
  MA_CHILD_MODE_REACT,
  MA_CONTEXT_ISOLATED,
  MA_CONTEXT_POLICIES,
  MA_CONTEXT_PRIOR_SUMMARY_ONLY,
  MA_CONTEXT_SHARED_SCRATCH,
  MAX_CRITIC_ROUNDS_CAP,
  MA_TEMPLATE_CRITIC_LOOP,
  MA_TEMPLATE_FIXED_PARALLEL,
  MA_TEMPLATE_FIXED_SEQUENCE,
  MA_TEMPLATE_ROUTER_PARALLEL,
  MA_TEMPLATE_ROUTER_THEN_SEQUENCE,
  DEFAULT_CRITIC_MAX_ROUNDS,
  childRunModeText,
  isSerialTemplate as isSerialTemplateCode,
  normalizeChildRunModes,
  normalizeContextPolicy,
  normalizeCriticMaxRounds,
  normalizeTemplate,
  validateOrchestration,
  type OrchestrationValidationResult
} from '@/utils/orchestrationValidation'

const props = defineProps<{
  /** 编排根 Agent 主键 */
  agentId: number
  /** 根展示名 */
  agentName: string
  /** 根启停；仅用于关系图灰色标识 */
  agentStatus: number
  /** 运行配置；父组件持有，改后经 update:config 回写 */
  config: AgentRuntimeConfig
  /** 现网候选列表，含停用行 */
  routes: AgentRouteRow[]
  /** 是否有 agent:edit（策略编辑） */
  canEdit: boolean
  /** 是否有 agent:bind（候选增删改/启停） */
  canBind: boolean
  /** 父 Agent 是否已按 ROUTER 落库；未保存前禁止维护候选 */
  savedRouter: boolean
}>()

const emit = defineEmits<{
  /** 策略字段变更；父组件写回 form.runtimeConfig，走页面底栏保存 */
  'update:config': [config: AgentRuntimeConfig]
  /** 候选增删改/启停成功；父组件重新拉取详情与候选 */
  'routes-changed': []
}>()

/** 已归一的模板码：库内非法/空值回落到路由并行，页面与后端同口径 */
const templateCode = computed(() => normalizeTemplate(props.config.maTemplate))
/** 是否固定串行模板：决定总控提示与「不受上限」文案 */
const isSequenceTemplate = computed(() => templateCode.value === MA_TEMPLATE_FIXED_SEQUENCE)
/** 是否路由后串行模板：有 JUDGE，但子按判定序串行 */
const isRouterThenSequence = computed(() => templateCode.value === MA_TEMPLATE_ROUTER_THEN_SEQUENCE)
/** 是否串行模板（固定串行 / 路由后串行）：决定关系图箭头与上下文策略是否放开前置摘要 */
const serialTemplate = computed(() => isSerialTemplateCode(templateCode.value))
/** 是否固定并行模板：决定上限提示与参与者口径 */
const isFixedParallelTemplate = computed(() => templateCode.value === MA_TEMPLATE_FIXED_PARALLEL)
/** 是否固定模板（无判路由） */
const isFixedTemplate = computed(() => isSequenceTemplate.value || isFixedParallelTemplate.value)
/** 是否评审环模板：展示评审专家 / 轮次，隐藏多意图与父历史开关 */
const isCriticTemplate = computed(() => templateCode.value === MA_TEMPLATE_CRITIC_LOOP)
/** 评审环轮次：库内非法/空值在页面上先按服务端同口径夹紧 */
const criticRounds = computed(() => normalizeCriticMaxRounds(props.config.maCriticMaxRounds))

/** 上下文策略：串行默认前置摘要，其它模板默认隔离；显式选的共享草稿区原样保留 */
const contextPolicy = computed(() =>
  normalizeContextPolicy(props.config.maContextPolicy, templateCode.value)
)

/** 上下文策略的一句话说明：三种策略各说自己的口径，不混用文案 */
const contextHint = computed(() => {
  if (contextPolicy.value === MA_CONTEXT_SHARED_SCRATCH) {
    return '本轮子 Agent 可用 ma_scratch_get / ma_scratch_put 读写键控草稿；同键第二次写入会被拒绝（不覆盖），草稿随本轮结束丢弃。'
  }
  if (serialTemplate.value) {
    return '前置摘要：后一步可见「本步问题 + 前置终答摘要」（单步 2000 / 累计 6000 字符），不含工具原文。'
  }
  return '多项隔离：子只见自己的子问题；前置摘要只对串行模板（固定串行 / 路由后串行）生效。'
})

/** 关系图与顶部标签用的模板名 */
const templateLabel = computed(() => {
  if (isSequenceTemplate.value) {
    return '模板 · 固定串行'
  }
  if (isFixedParallelTemplate.value) {
    return '模板 · 固定并行'
  }
  if (isCriticTemplate.value) {
    return '模板 · 评审环'
  }
  if (isRouterThenSequence.value) {
    return '模板 · 路由后串行'
  }
  return '模板 · 路由并行'
})

/** 参与判路由的启用候选数 */
const enabledCount = computed(() => props.routes.filter((row) => row.status === 1).length)

/**
 * 评审环执行者：启用候选里排除评审者后的第 1 个（sort_order、同序 id 与服务端同口径）。
 * 仅用于关系示意与校验提示，不参与写库。
 */
const executorRow = computed<AgentRouteRow | undefined>(() => {
  const criticId = props.config.maCriticAgentId ?? null
  return props.routes
    .filter((row) => row.status === 1 && (criticId == null || row.childAgentId !== criticId))
    .slice()
    .sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id)[0]
})

/** 关系示意里的执行者名；没有候选时给占位文案 */
const executorLabel = computed(() => (executorRow.value ? childLabel(executorRow.value) : '未选执行者'))

/** 评审专家下拉的可选项：启用、非 ROUTER、运行模式获允许、已开启允许被编排 */
const criticOptions = ref<AgentCard[]>([])
const criticLoaded = ref(false)

/** 关系示意里的评审专家名：优先下拉里的名称，其次 #id，未选则提示去选 */
const criticLabel = computed(() => {
  const id = props.config.maCriticAgentId ?? null
  if (id == null) {
    return '未选评审专家'
  }
  const hit = criticOptions.value.find((item) => item.id === id)
  return hit ? `${hit.name}` : `#${id}`
})

/**
 * 本编排允许的子运行模式（归一后恒含 ReAct）。
 * 页面上的多选与校验、候选过滤、关系图灰显都读这一份，避免三处口径漂移。
 */
const allowedChildRunModes = computed(() => normalizeChildRunModes(props.config.maChildRunModesAllowed))

/**
 * @param mode 子运行模式
 * @return 是否被本编排允许（ROUTER / 未知模式恒 false）
 */
function isModeAllowed(mode?: string | null): boolean {
  return !!mode && allowedChildRunModes.value.includes(mode)
}

/**
 * 子运行模式多选变更：归一化后写回（至少保留 ReAct；未勾任何项时自动回 ReAct）。
 *
 * @param values 多选值
 */
function onChildModesChange(values: (string | number | boolean)[]) {
  patchConfig({ maChildRunModesAllowed: normalizeChildRunModes(values.map((item) => String(item))) })
}

/**
 * 加载评审专家候选：与「添加候选」同一套门闩（启用 / 非 ROUTER / 运行模式获允许 / 允许被编排）。
 *
 * <p>只在评审环模板下按需加载一次；切换「允许的子运行模式」后需要重载，
 * 否则下拉里会残留已经不获允许的 P&E 评审专家。
 */
async function loadCriticOptions() {
  if (criticLoaded.value) {
    return
  }
  try {
    const { data } = await pageAgents({ current: 1, size: 100, status: 1 })
    const allowed = allowedChildRunModes.value
    criticOptions.value = (data.data?.records || []).filter(
      (item) =>
        item.id !== props.agentId &&
        item.status === 1 &&
        item.runMode !== 'ROUTER' &&
        allowed.includes(String(item.runMode)) &&
        item.allowOrchestrated !== false
    )
    criticLoaded.value = true
  } catch {
    // 下拉加载失败不阻断页面：保存/运行时服务端仍会做同一套校验
    criticOptions.value = []
  }
}

/**
 * 评审专家选择变更：只写配置，随页面底栏一起保存。
 *
 * @param value 选中的评审专家 agentId；清空时为 undefined
 */
function onCriticChange(value: number | undefined) {
  patchConfig({ maCriticAgentId: value ?? null })
}

/** 只有「已落库的 Router + agent:bind」才能维护候选，避免切模式未保存时请求被后端拒绝 */
const canOperate = computed(() => props.canBind && props.savedRouter)

/** 正在提交启停的路由行 id；空表示没有请求在飞 */
const rowBusy = ref<number | null>(null)

/** 候选编辑弹窗 */
const routeOpen = ref(false)
const routeEditOpen = ref(false)
const routeSaving = ref(false)
const editAdvanced = ref<string[]>([])
const routeDraft = reactive({ id: 0, routeKey: '', intent: '', sortOrder: 0 })

/** 校验结果；候选或策略变化后置空，避免展示过期结论 */
const validation = ref<OrchestrationValidationResult | null>(null)
const validating = ref(false)

const user = useUserStore()
/** 泳道抽屉：只读展示最近一轮；不入库、不新增接口 */
const laneOpen = ref(false)
const laneOpening = ref(false)
const laneSessionId = ref<number | null>(null)

/**
 * 打开「最近试跑泳道」。
 *
 * <p>只查该 Agent 最近一条试跑会话（size=1），找到就交给编排泳道组件按 traceId 自取 span；
 * 没有会话或没有试跑列表权限时给中文提示，不新开「编排 → Multi-Agent」列表。
 */
async function openLane() {
  if (!user.hasPerm('runtime:trial:list')) {
    ElMessage.warning('没有试跑会话查询权限，无法查看编排泳道')
    return
  }
  laneOpening.value = true
  try {
    const { data } = await pageTrialSessions({ current: 1, size: 1, agentId: props.agentId })
    laneSessionId.value = data.data?.records?.[0]?.id ?? null
    laneOpen.value = true
  } catch {
    ElMessage.error('试跑会话加载失败')
  } finally {
    laneOpening.value = false
  }
}

/**
 * 合并策略补丁并回传父组件。
 *
 * @param patch 只包含本次改动的字段
 */
function patchConfig(patch: Partial<AgentRuntimeConfig>) {
  emit('update:config', { ...props.config, ...patch })
}

/**
 * 模板切换：五值都可保存；切到串行模板（固定串行 / 路由后串行）时把上下文策略默认成
 * 「前置摘要」，切走时收敛回隔离（服务端保存同口径），避免配置写着摘要继承却按并行隔离跑。
 *
 * @param value 下拉值
 */
function onTemplateChange(value: string) {
  const next = normalizeTemplate(value)
  // 共享草稿区与模板无关：切模板时保留用户显式选择，不被串/并行默认值洗掉
  const keepScratch = normalizeContextPolicy(props.config.maContextPolicy, templateCode.value)
    === MA_CONTEXT_SHARED_SCRATCH
  patchConfig({
    maTemplate: next,
    // 串行默认前置摘要；切到并行一律回隔离，避免把串行策略带进并行模板
    maContextPolicy: keepScratch
      ? MA_CONTEXT_SHARED_SCRATCH
      : (isSerialTemplateCode(next) ? MA_CONTEXT_PRIOR_SUMMARY_ONLY : MA_CONTEXT_ISOLATED)
  })
}

/**
 * 上下文策略切换：三值都收；前置摘要仍只对串行模板开放（非串行选了也收敛回隔离）。
 *
 * @param value 单选值
 */
function onContextChange(value: string) {
  if (value === MA_CONTEXT_SHARED_SCRATCH) {
    patchConfig({ maContextPolicy: MA_CONTEXT_SHARED_SCRATCH })
    return
  }
  if (!serialTemplate.value) {
    patchConfig({ maContextPolicy: MA_CONTEXT_ISOLATED })
    return
  }
  patchConfig({ maContextPolicy: value === MA_CONTEXT_ISOLATED ? MA_CONTEXT_ISOLATED : MA_CONTEXT_PRIOR_SUMMARY_ONLY })
}

/**
 * 候选展示名：名称 → 编码 → 主键。
 *
 * @param row 路由行
 * @return 展示名
 */
function childLabel(row: AgentRouteRow): string {
  return row.childName?.trim() || row.childCode?.trim() || `#${row.childAgentId}`
}

/**
 * 打开候选编辑弹窗并回填现值。
 *
 * @param row 路由行
 */
function openEdit(row: AgentRouteRow) {
  routeDraft.id = row.id
  routeDraft.routeKey = row.routeKey || ''
  routeDraft.intent = row.intent || ''
  routeDraft.sortOrder = row.sortOrder
  editAdvanced.value = []
  routeEditOpen.value = true
}

/**
 * 保存候选编辑：空意图在本地先拦，route_key 空则交给后端补全。
 */
async function saveEdit() {
  const intent = routeDraft.intent.trim()
  if (!intent) {
    ElMessage.warning('请填写意图')
    return
  }
  routeSaving.value = true
  try {
    await updateRoute(props.agentId, routeDraft.id, {
      routeKey: routeDraft.routeKey.trim() || undefined,
      intent,
      sortOrder: routeDraft.sortOrder,
      status: props.routes.find((row) => row.id === routeDraft.id)?.status ?? 1
    })
    routeEditOpen.value = false
    emit('routes-changed')
  } finally {
    routeSaving.value = false
  }
}

/**
 * 新增候选：复用 RouteBindDialog 的子 Agent + 意图表单。
 *
 * @param body 子 Agent、意图、可选 key/sort
 * @param done 弹窗结果回调
 */
async function addRoute(
  body: { childAgentId: number; routeKey?: string; intent: string; sortOrder?: number },
  done: () => void
) {
  try {
    await createRoute(props.agentId, body)
    routeOpen.value = false
    emit('routes-changed')
  } finally {
    done()
  }
}

/**
 * 启停候选：带上原 key / intent，避免后端把空意图当成未填。
 *
 * @param row    路由行
 * @param enable 目标状态
 */
async function onToggle(row: AgentRouteRow, enable: boolean) {
  const next = enable ? 1 : 0
  if (row.status === next || rowBusy.value !== null) {
    return
  }
  rowBusy.value = row.id
  try {
    await updateRoute(props.agentId, row.id, {
      routeKey: row.routeKey || undefined,
      intent: row.intent || undefined,
      sortOrder: row.sortOrder,
      status: next
    })
    emit('routes-changed')
  } finally {
    rowBusy.value = null
  }
}

/**
 * 移除候选；不弹二次确认，与现网路由页行为一致。
 *
 * @param row 路由行
 */
async function removeRow(row: AgentRouteRow) {
  await deleteRoute(props.agentId, row.id)
  emit('routes-changed')
}

/**
 * 执行关系校验。纯前端计算，输入就是当前候选与策略。
 */
function runValidation() {
  validating.value = true
  try {
    validation.value = validateOrchestration({
      parentAgentId: props.agentId,
      template: templateCode.value,
      aggregate: props.config.answerMode || MA_AGGREGATE_CONCAT,
      context: props.config.maContextPolicy,
      maxParallelChildren: props.config.maxRouteChildren ?? 3,
      allowedChildRunModes: allowedChildRunModes.value,
      criticAgentId: props.config.maCriticAgentId ?? null,
      criticMaxRounds: props.config.maCriticMaxRounds,
      routes: props.routes
    })
  } finally {
    validating.value = false
  }
}

// 候选变化后旧结论失效；策略字段变化同样提示重新校验
watch(
  () => props.routes,
  () => {
    validation.value = null
  },
  { deep: true }
)
watch(
  () => [props.config.maTemplate, props.config.answerMode, props.config.maxRouteChildren,
    props.config.maChildRunModesAllowed, props.config.maCriticAgentId, props.config.maCriticMaxRounds],
  () => {
    validation.value = null
  }
)

// 评审环下拉按需加载；切换允许的子运行模式后要重载，避免残留已不获允许的候选
watch(
  isCriticTemplate,
  (on) => {
    if (on) {
      loadCriticOptions()
    }
  },
  { immediate: true }
)

watch(
  allowedChildRunModes,
  () => {
    criticLoaded.value = false
    if (isCriticTemplate.value) {
      loadCriticOptions()
    }
  }
)

// 上下文策略只允许三值白名单；非法 / 模板不兼容的值按模板默认回落，
// 避免历史脏值让页面显示与执行不一致。注意：共享草稿区对五种模板都合法，不能被收敛回隔离。
watch(
  () => [props.config.maContextPolicy, templateCode.value],
  () => {
    const raw = props.config.maContextPolicy
    const known = !raw || (MA_CONTEXT_POLICIES as readonly string[]).includes(raw)
    // 串行 + 前置摘要 / 共享草稿都合法；并行 + 前置摘要属于「模板不兼容」，归一后回落隔离
    const normalized = normalizeContextPolicy(raw, templateCode.value)
    if (!known || normalized !== raw) {
      patchConfig({ maContextPolicy: normalized })
    }
  },
  { immediate: true }
)

// 汇总策略只允许 CONCAT / SUMMARIZE；未知值回落 CONCAT，避免页面显示空白
watch(
  () => props.config.answerMode,
  (value) => {
    if (value !== MA_AGGREGATE_CONCAT && value !== MA_AGGREGATE_SUMMARIZE) {
      patchConfig({ answerMode: MA_AGGREGATE_CONCAT })
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.orch-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.orch-card {
  padding: 14px 16px;
  background: var(--ah-surface, #fff);
  border: 1px solid var(--ah-border, #e8e9ef);
  border-radius: 10px;
}

.orch-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.head-main h3 {
  margin: 0 0 6px;
  font-size: 16px;
  font-weight: 650;
}

.head-sub {
  margin: 0;
  max-width: 62em;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.6;
}

.head-sub code {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.head-tags {
  display: flex;
  flex: none;
  gap: 8px;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.card-head h4 {
  margin: 0 0 2px;
  font-size: 14px;
  font-weight: 650;
}

.hint {
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.5;
}

.block-alert {
  margin-bottom: 12px;
}

/* 静态关系树：根居中，子节点横排；连接线用伪元素画，不引入图库 */
.map {
  padding: 4px 8px 0;
}

.root-node {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 220px;
  margin: 0 auto;
  padding: 10px 12px;
  text-align: center;
  background: #f7f7ff;
  border: 1px solid #dedcff;
  border-radius: 10px;
}

.root-node strong {
  font-size: 14px;
}

.root-node span {
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}

.root-node.off {
  background: #f5f6fa;
  border-color: #e5e7eb;
}

.map-stem {
  width: 2px;
  height: 18px;
  margin: 0 auto;
  background: #d9dbe7;
}

.map-children {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
  padding-top: 18px;
}

/* 横向汇流线；子多换行时仍能看出「同属一个根」 */
.map-children::before {
  content: '';
  position: absolute;
  top: 0;
  left: 8%;
  right: 8%;
  height: 2px;
  background: #d9dbe7;
}

.child-node {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 176px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--ah-border, #e8e9ef);
  border-radius: 10px;
}

.child-node::before {
  content: '';
  position: absolute;
  top: -18px;
  left: 50%;
  width: 2px;
  height: 18px;
  background: #d9dbe7;
}

.child-node strong {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.child-node.off {
  background: #f7f8fa;
  border-style: dashed;
}

/* 子被关掉「允许被编排调用」：边框提示但不整块变灰，仍能看到它的其它状态 */
.child-node.blocked {
  border-color: #f0c78a;
}

/* 串行模板：一条横向链路而非汇流；箭头提示有先后顺序 */
.map-children.is-sequence {
  align-items: center;
  gap: 8px;
}

.map-children.is-sequence::before {
  display: none;
}

.map-children.is-sequence .child-node::before {
  display: none;
}

.seq-arrow {
  color: #8b84ff;
  font-size: 16px;
  font-weight: 700;
  line-height: 1;
}

/* 评审环轻量示意：执行者 → 评审专家 → 虚线回修；只用静态节点，不引入 X6 */
.critic-map {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--ah-border, #e8e9ef);
}

.critic-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 6px 10px;
  background: #f6f5ff;
  border: 1px solid #ddd9ff;
  border-radius: 8px;
  font-size: 12px;
}

.critic-chip.off {
  background: #f6f7fa;
  border-color: #e5e7eb;
  color: #9ca3af;
}

.critic-role {
  color: #8b84ff;
  font-size: 11px;
}

.critic-arrow {
  color: #8b84ff;
  font-size: 12px;
  white-space: nowrap;
}

.critic-arrow.dashed {
  color: var(--ah-text-muted, #9ca3af);
}

.child-key {
  overflow: hidden;
  color: var(--ah-text-muted, #9ca3af);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.child-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.orch-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(320px, 0.9fr);
  gap: 16px;
}

.cand-table {
  width: 100%;
}

.cand-key {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.cand-name {
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cand-sub {
  margin-top: 2px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}

.cand-blocked {
  color: #b45309;
}

.strategy-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.field-hint {
  margin-top: 4px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  line-height: 1.5;
}

/* 串行 + 共享草稿的黄色提示：字段单选，前置摘要不会同时生效 */
.field-warn {
  margin-top: 4px;
  color: #b45309;
  font-size: 12px;
  line-height: 1.5;
}

.validation-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.issue-list {
  margin: 4px 0 0;
  padding-left: 18px;
  line-height: 1.7;
}

.route-advanced {
  margin-top: 4px;
  border: none;
}

.route-advanced :deep(.el-collapse-item__header) {
  height: 36px;
  border: none;
  color: #6b7280;
  font-size: 13px;
  line-height: 36px;
}

.route-advanced :deep(.el-collapse-item__wrap) {
  border: none;
}

.route-hint {
  margin-top: 4px;
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.4;
}

/* 泳道抽屉正文：只读，不参与页面底栏保存 */
.lane-drawer {
  min-height: 240px;
}

/* 编排 Tab 底部：只读入口，不占策略/校验的区域 */
.orch-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  border: 1px dashed var(--ah-border, #e5e7eb);
  border-radius: 10px;
  background: #fafbfc;
}

@media (max-width: 1100px) {
  .orch-grid {
    grid-template-columns: 1fr;
  }
}
</style>
