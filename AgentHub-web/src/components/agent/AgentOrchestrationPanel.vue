<template>
  <div class="orch-panel">
    <!--
      真源提示（MA-P2 刀3）：多智能体方案 ma_def / ma_candidate 是唯一写路径。
      有启用绑根方案 → 只读摘要 + 深链到方案编辑；无方案 → 引导去方案列表新建/绑定。
      本 Tab 不再提供任何编排写入，避免出现第二套与 ma 分叉的配置。
    -->
    <el-alert v-if="boundMa" class="ma-bind-tip" type="success" :closable="false" show-icon>
      <template #title>
        本根由多智能体方案「{{ boundMa.name }}」接管（{{ boundMaTemplateLabel }} · 启用候选
        {{ boundMa.candidateEnabledCount ?? 0 }} 个）。方案是唯一真源，本 Tab 仅只读展示；
        <el-link type="primary" :underline="false" @click="goMaEdit">打开方案编辑</el-link>
        <span class="ma-tip-sep">·</span>
        <el-link type="primary" :underline="false" @click="goMaList">多智能体方案列表</el-link>
      </template>
    </el-alert>
    <el-alert v-else class="ma-bind-tip" type="warning" :closable="false" show-icon>
      <template #title>
        本根未读到已启用的多智能体方案（没有绑定，或当前账号无方案查看权限），执行按旧路由配置兼容回落。请到
        <el-link type="primary" :underline="false" @click="goMaList">编排 → 多智能体</el-link>
        新建方案、绑定本 Agent 并启用；本 Tab 已改为只读，不再写回旧路由。
      </template>
    </el-alert>
    <!-- 顶部：模板 + 一句话说明；与底部策略卡同源，避免两处展示不一致 -->
    <section class="orch-card orch-head">
      <div class="head-main">
        <h3>编排：{{ agentName }}</h3>
        <p class="head-sub">
          六种模板：路由并行（判路由 → 多子并行）、固定串行（跳过判路由，按排序依次执行）、
          固定并行（跳过判路由，全部子吃同一份原问并行）、评审环（执行者出稿 → 评审 → 有限回修）、
          路由后串行（判路由选出专家与子问 → 按判定顺序串行）、
          竞争选优（同题并行给多个候选 → 选优只取 1 份终答）。
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

    <!--
      关系概览：轻量静态树，不引入 X6 / VueFlow。
      连线只表达「根 → 候选子」的一层主从分发：每个子节点各有一条直角折线接回父级汇流线；
      子节点之间不再画串行箭头（串行的先后由候选排序与执行语义表达，不画成节点间连线）。
    -->
    <section class="orch-card">
      <div class="card-head">
        <div>
          <h4>关系概览</h4>
          <span class="hint">
            <template v-if="boundMa">本图取旧 agent_route 行（兼容展示，执行以方案为准）· </template>
            只画「根 → 候选子」一层分发，子节点之间不连线；{{ serialTemplate ? '串行先后以候选排序为准' : isCriticTemplate ? '评审环由执行者出稿、评审专家判定' : '并行模板同时派发' }}；灰色表示停用或不可用
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
        <!-- 干线：始终从根节点底部中心竖直向下，不随模板变形 -->
        <div class="map-stem" />
        <!--
          子节点区：只有根 → 子的连线（每条由子节点自己的伪元素绘制），
          汇流线宽度按子节点数量算出，不写死坐标。
        -->
        <div v-if="routes.length" class="map-children" :style="forkBusStyle">
          <div
            v-for="row in routes"
            :key="row.id"
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

    <!--
      候选与策略：Tab 切换替代左右分栏，任一 Tab 内容都占满全宽。
      策略项的解释性长文一律收进 Label 旁问号 Tooltip，界面上只留控件本身，避免说明堆积。
    -->
    <section class="orch-card">
      <div class="card-head">
        <div>
          <h4>候选与策略</h4>
          <span class="hint">只读展示当前生效的候选与策略；修改请到多智能体方案页，本 Tab 不再保存</span>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="orch-tabs">
        <!-- Tab 一：候选列表；增删改/启停即时落库，与现网 /routes API 等价 -->
        <el-tab-pane label="候选列表" name="routes">
          <div class="pane-toolbar">
            <span class="hint">
              <template v-if="boundMa">旧 agent_route 行，仅供对照 · </template>
              route_key / 意图 / 子 Agent / 启停 · 共 {{ routes.length }} 个候选，启用 {{ enabledCount }} 个
            </span>
            <!-- 新建入口跟随本 Tab 顶部，不回到页头，避免和全局「新建 Agent」混淆 -->
            <el-button v-if="canOperate" type="primary" @click="routeOpen = true">+ 添加候选</el-button>
          </div>

          <el-alert
            v-if="!savedRouter"
            title="当前运行模式是 Router 但尚未保存：保存后本页才按 Router 展示；候选统一在方案页维护。"
            type="warning"
            show-icon
            :closable="false"
            class="block-alert"
          />

          <!--
            全宽表格：意图列吃掉剩余空间，route_key / 子 Agent 给足固定下限，避免挤压换行。
            外层 .cand-scroll 限制高度并自己滚动，长候选列表不会把末行顶到页面底栏下面。
          -->
          <div class="cand-scroll">
            <el-table class="cand-table" :data="routes" size="small" empty-text="还没有候选子 Agent">
              <el-table-column label="route_key" min-width="160">
                <template #default="{ row }">
                  <code class="cand-key">{{ row.routeKey || '待生成' }}</code>
                </template>
              </el-table-column>
              <el-table-column label="意图" min-width="320" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="cand-intent">{{ row.intent || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="子 Agent" min-width="240">
                <template #default="{ row }">
                  <div class="cand-name" :title="childLabel(row)">{{ childLabel(row) }}</div>
                  <div class="cand-sub">
                    {{ childRunModeText(row.childRunMode) }} · {{ row.childStatus === 1 ? '启用' : '停用' }}
                    <span v-if="row.childRunMode && !isModeAllowed(row.childRunMode)" class="cand-blocked"> · 未获本编排允许</span>
                    <span v-if="row.childAllowOrchestrated === 0" class="cand-blocked"> · 未允许被编排调用</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="启停" width="90" align="center">
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
              <el-table-column v-if="canOperate" label="操作" width="130" align="center">
                <template #default="{ row }">
                  <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                  <el-button link type="danger" @click="removeRow(row)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <!-- Tab 二：编排策略；两列栅格横向展开，Label 在左，控件在右 -->
        <el-tab-pane label="编排策略" name="strategy">
          <el-form class="strategy-form" label-width="112px" label-position="left">
            <div class="strategy-grid">
              <!-- 协作模式：下拉铺满本格；模板差异说明收进问号 -->
              <el-form-item>
                <template #label>
                  <span class="label-with-tip">
                    协作模式
                    <el-tooltip placement="top" :show-after="200">
                      <template #content>
                        <div class="field-tip">{{ templateHint }}</div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-select
                  :model-value="config.maTemplate || MA_TEMPLATE_ROUTER_PARALLEL"
                  :disabled="strategyDisabled"
                  style="width: 100%"
                  @change="onTemplateChange"
                >
                  <el-option label="路由并行（判路由 → 多子并行）" value="ROUTER_PARALLEL" />
                  <el-option label="固定串行（跳过判路由，按排序依次执行）" value="FIXED_SEQUENCE" />
                  <el-option label="固定并行（跳过判路由，全部子一起跑）" value="FIXED_PARALLEL" />
                  <el-option label="评审环（执行者出稿 → 评审 → 有限回修）" value="CRITIC_LOOP" />
                  <el-option label="路由后串行（判路由 → 按判定顺序串行）" value="ROUTER_THEN_SEQUENCE" />
                  <el-option label="竞争选优（同题并行 → 选优取 1 个终答）" value="COMPETE" />
                </el-select>
              </el-form-item>

              <!-- 结果合成：横向单选框 -->
              <el-form-item>
                <template #label>
                  <span class="label-with-tip">
                    结果合成
                    <el-tooltip placement="top" :show-after="200" :content="answerModeHint">
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-radio-group
                  :model-value="config.answerMode || 'CONCAT'"
                  :disabled="strategyDisabled"
                  @change="(value: string | number | boolean) => patchConfig({ answerMode: String(value) })"
                >
                  <el-radio value="CONCAT">拼接</el-radio>
                  <el-radio value="SUMMARIZE">汇总</el-radio>
                  <!-- 投票只对「有末段汇总」的模板开放；其它模板选了也会被后端收敛回拼接 -->
                  <el-radio v-if="supportsVote" value="VOTE">投票选优</el-radio>
                </el-radio-group>
              </el-form-item>

              <!-- 上下文共享：三选项横向并排，独占整行避免拥挤 -->
              <el-form-item class="span-2">
                <template #label>
                  <span class="label-with-tip">
                    上下文共享
                    <el-tooltip placement="top" :show-after="200">
                      <template #content>
                        <div class="field-tip">{{ contextHint }}</div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-radio-group
                  :model-value="contextPolicy"
                  :disabled="strategyDisabled"
                  @change="(value: string | number | boolean) => onContextChange(String(value))"
                >
                  <el-radio :value="MA_CONTEXT_ISOLATED">多项隔离</el-radio>
                  <el-radio :value="MA_CONTEXT_PRIOR_SUMMARY_ONLY" :disabled="!serialTemplate">
                    前置摘要（仅串行）
                  </el-radio>
                  <el-radio :value="MA_CONTEXT_SHARED_SCRATCH">共享草稿区</el-radio>
                </el-radio-group>
                <!-- 串行 + 共享草稿是真冲突（前置摘要不会同时生效），保留一行极短警示 -->
                <span
                  v-if="serialTemplate && contextPolicy === MA_CONTEXT_SHARED_SCRATCH"
                  class="field-warn"
                >
                  串行下不再自动拼接前置摘要，后步需自查草稿
                </span>
              </el-form-item>

              <!-- 评审环专属：评审专家 + 最大评审轮次；其它模板隐藏，但库里 maCritic* 不清空，切回可恢复 -->
              <template v-if="isCriticTemplate">
                <el-form-item required>
                  <template #label>
                    <span class="label-with-tip">
                      评审专家
                      <el-tooltip placement="top" :show-after="200">
                        <template #content>
                          <div class="field-tip">{{ criticHint }}</div>
                        </template>
                        <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                      </el-tooltip>
                    </span>
                  </template>
                  <el-select
                    :model-value="config.maCriticAgentId ?? undefined"
                    :disabled="strategyDisabled"
                    filterable
                    placeholder="选择评审专家"
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
                </el-form-item>

                <el-form-item>
                  <template #label>
                    <span class="label-with-tip">
                      最大评审轮次
                      <el-tooltip placement="top" :show-after="200" :content="criticRoundsHint">
                        <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                      </el-tooltip>
                    </span>
                  </template>
                  <el-input-number
                    :model-value="criticRounds"
                    :min="1"
                    :max="MAX_CRITIC_ROUNDS_CAP"
                    :step="1"
                    :disabled="strategyDisabled"
                    controls-position="right"
                    style="width: 120px"
                    @change="(value: number | undefined) => patchConfig({ maCriticMaxRounds: value ?? DEFAULT_CRITIC_MAX_ROUNDS })"
                  />
                </el-form-item>
              </template>

              <!-- 竞争选优专属：选优策略；其它模板隐藏，但库里 competeStrategy 不清空，切回可恢复 -->
              <template v-if="isCompeteTemplate">
                <el-form-item class="span-2">
                  <template #label>
                    <span class="label-with-tip">
                      选优策略
                      <el-tooltip placement="top" :show-after="200">
                        <template #content>
                          <div class="field-tip">{{ competeStrategyHint }}</div>
                        </template>
                        <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                      </el-tooltip>
                    </span>
                  </template>
                  <el-radio-group
                    :model-value="competeStrategy"
                    :disabled="strategyDisabled"
                    @change="(value: string | number | boolean) => patchConfig({ competeStrategy: String(value) })"
                  >
                    <el-radio :value="MA_COMPETE_STRATEGY_JUDGE_PICK">评选模型选优（默认）</el-radio>
                    <el-radio :value="MA_COMPETE_STRATEGY_FIRST_SUCCESS">第一个成功子</el-radio>
                  </el-radio-group>
                  <span class="field-warn">
                    至少 2 个启用候选；评选模型缺省用判路由模型，失败自动降级为第一个成功子
                  </span>
                </el-form-item>
              </template>

              <el-form-item>
                <template #label>
                  <span class="label-with-tip">
                    {{ isRouterThenSequence ? '串行上限' : '并行上限' }}
                    <el-tooltip placement="top" :show-after="200">
                      <template #content>
                        <div class="field-tip">{{ maxChildrenHint }}</div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-input-number
                  :model-value="config.maxRouteChildren ?? 3"
                  :min="1"
                  :max="5"
                  :step="1"
                  :disabled="strategyDisabled"
                  controls-position="right"
                  style="width: 120px"
                  @change="(value: number | undefined) => patchConfig({ maxRouteChildren: value ?? 3 })"
                />
              </el-form-item>

              <el-form-item>
                <template #label>
                  <span class="label-with-tip">
                    子 Agent 运行模式
                    <el-tooltip placement="top" :show-after="200">
                      <template #content>
                        <div class="field-tip">{{ childModesHint }}</div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-checkbox-group
                  :model-value="allowedChildRunModes"
                  :disabled="strategyDisabled"
                  @change="onChildModesChange"
                >
                  <el-checkbox :value="MA_CHILD_MODE_REACT" disabled>ReAct</el-checkbox>
                  <el-checkbox :value="MA_CHILD_MODE_PLAN">计划执行</el-checkbox>
                </el-checkbox-group>
              </el-form-item>

              <!-- 协作深度：只读展示，说明收进问号 -->
              <el-form-item>
                <template #label>
                  <span class="label-with-tip">
                    协作深度
                    <el-tooltip placement="top" :show-after="200" :content="depthHint">
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-input model-value="1" disabled style="width: 88px" />
              </el-form-item>

              <!-- 开关项：各占一整行，Label 在左、Switch 在右，不与上方字段混排 -->
              <div
                v-if="!isFixedTemplate && !isCriticTemplate"
                class="switch-row span-2"
              >
                <span class="switch-row__label label-with-tip">
                  {{ isRouterThenSequence ? '多意图串行' : '多意图并行' }}
                  <el-tooltip placement="top" :show-after="200">
                    <template #content>
                      <div class="field-tip">{{ multiIntentHint }}</div>
                    </template>
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
                <el-switch
                  :model-value="config.multiIntentEnabled !== false"
                  :disabled="strategyDisabled"
                  inline-prompt
                  @change="(on: string | number | boolean) => patchConfig({ multiIntentEnabled: on === true })"
                />
              </div>

              <div v-if="!isCriticTemplate" class="switch-row span-2">
                <span class="switch-row__label label-with-tip">
                  同会话继承父上下文
                  <el-tooltip placement="top" :show-after="200">
                    <template #content>
                      <div class="field-tip">{{ parentHistoryHint }}</div>
                    </template>
                    <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </span>
                <el-switch
                  :model-value="config.maAllowParentHistoryWhenSingle !== false"
                  :disabled="strategyDisabled"
                  @change="(on: string | number | boolean) => patchConfig({ maAllowParentHistoryWhenSingle: on === true })"
                />
              </div>
            </div>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </section>

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

    <!-- 底部薄入口：试跑进独立全屏页；只读看最近一轮试跑的编排泳道（无会话时提示先跑一轮） -->
    <footer class="orch-foot">
      <span class="hint">泳道只读：按最近一轮运行会话拉调用追踪，展示判路由 → 子并行 → 汇总；运行为全屏对话页</span>
      <div class="orch-foot__ops">
        <!-- 运行入口：行为由父级决定（Router 跳独立页，其它模式维持原抽屉） -->
        <el-button v-if="canTrial" size="small" type="primary" :disabled="agentStatus !== 1" @click="emit('trial')">
          运行
        </el-button>
        <el-button size="small" :loading="laneOpening" @click="openLane">查看最近运行泳道</el-button>
      </div>
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
    <el-drawer v-model="laneOpen" title="最近运行 · 编排泳道" size="72%" append-to-body>
      <div v-loading="laneOpening" class="lane-drawer">
        <el-empty v-if="!laneOpening && !laneSessionId" description="该 Agent 还没有运行会话，先运行一轮再来看泳道" />
        <OrchestrationLane v-else :session-id="laneSessionId" :default-expanded="true" />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * Router Agent 详情「编排」Tab。所属域：agent。
 *
 * <p>MA-P2 刀3 起本 Tab 是<b>只读视图</b>：多智能体方案（ma_def + ma_candidate）是唯一写源。
 * <ul>
 *   <li>有启用绑根方案：顶部展示方案摘要（名称 / 模板 / 启用候选数）+「打开方案编辑」深链；</li>
 *   <li>无启用方案：提示去「编排 → 多智能体」新建/绑定并启用，执行按旧路由兼容回落；</li>
 *   <li>候选表与策略控件一律只读，不再提供增删改或保存入口，避免写出与 ma 分叉的配置。</li>
 * </ul>
 *
 * 数据来源（只读）：
 * - 候选：仍从 /api/agent/agents/{id}/routes 读取历史行（agent_route 保留兼容，不再回写）；
 * - 策略：父组件传入的 agent_def.runtime_config_json 快照；
 * - 绑定方案摘要：GET /api/agent/ma/defs/by-root/{agentId}。
 * 约束：
 * - 关系图用静态布局，不引入 X6 / VueFlow；
 * - 不改 RouterTrialExecutor 的隔离 / 并行 / CONCAT·SUMMARIZE 语义。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { createRoute, deleteRoute, pageAgents, updateRoute } from '@/api/agent'
import { pageTrialSessions } from '@/api/runtime'
import { getMaDefByRoot } from '@/api/ma'
import { useUserStore } from '@/stores/user'
import RouteBindDialog from '@/components/agent/RouteBindDialog.vue'
import OrchestrationLane from '@/components/runtime/OrchestrationLane.vue'
import type { AgentCard, AgentRouteRow, AgentRuntimeConfig } from '@/types/agent'
import type { MaDefRow } from '@/types/ma'
import {
  MA_AGGREGATE_CONCAT,
  MA_AGGREGATE_SUMMARIZE,
  MA_AGGREGATE_VOTE,
  MA_CHILD_MODE_PLAN,
  MA_CHILD_MODE_REACT,
  MA_CONTEXT_ISOLATED,
  MA_CONTEXT_POLICIES,
  MA_CONTEXT_PRIOR_SUMMARY_ONLY,
  MA_CONTEXT_SHARED_SCRATCH,
  MAX_CRITIC_ROUNDS_CAP,
  MA_COMPETE_STRATEGY_FIRST_SUCCESS,
  MA_COMPETE_STRATEGY_JUDGE_PICK,
  MA_TEMPLATE_CRITIC_LOOP,
  MA_TEMPLATE_COMPETE,
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
  normalizeCompeteStrategy,
  normalizeTemplate,
  supportsVoteTemplate,
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
  /** 是否有试跑权限；无权限不渲染底部试跑入口 */
  canTrial?: boolean
}>()

const emit = defineEmits<{
  /** 策略字段变更；父组件写回 form.runtimeConfig，走页面底栏保存 */
  'update:config': [config: AgentRuntimeConfig]
  /** 候选增删改/启停成功；父组件重新拉取详情与候选 */
  'routes-changed': []
  /** 点「试跑」：交回父级决定落点（Router 跳独立页，其它模式维持原抽屉） */
  trial: []
}>()

const router = useRouter()
const user = useUserStore()

/**
 * 该根当前绑定的启用方案。null＝还没有启用的 ma_def（老数据），
 * 此时 Tab 顶部提示去方案列表新建/绑定；非空时展示只读摘要并深链到方案编辑。
 *
 * <p>无论哪种情况，本 Tab 都不再写编排配置（刀3 起 ma 为唯一写源）。
 */
const boundMa = ref<MaDefRow | null>(null)

/**
 * 查询绑定方案。失败静默：提示条只是导航与摘要，不能阻断编排页展示。
 */
async function loadBoundMa() {
  // 没有方案查看权时别发请求：拦截器会弹 403 红条，而这里只是导航与摘要
  if (!user.hasPerm('ma:def:view') || !props.agentId) {
    boundMa.value = null
    return
  }
  try {
    const { data } = await getMaDefByRoot(props.agentId)
    boundMa.value = data.data || null
  } catch {
    boundMa.value = null
  }
}

/**
 * 跳到方案列表。
 * 无绑根方案时用作「去新建/绑定」入口，也作为摘要里的兜底列表链接。
 */
function goMaList() {
  void router.push('/orchestration/multi-agent')
}

/**
 * 深链到方案编辑：列表页读 query.edit 打开对应方案的抽屉。
 * 不复制一份编排表单到这里，避免与 ma 真源分叉。
 */
function goMaEdit() {
  if (!boundMa.value) {
    goMaList()
    return
  }
  void router.push({ path: '/orchestration/multi-agent', query: { edit: String(boundMa.value.id) } })
}

// Agent 切换（含 KeepAlive 复用）时重查绑定方案，避免把上一个根的提示带过来
watch(
  () => props.agentId,
  () => {
    void loadBoundMa()
  },
  { immediate: true }
)

/** 已归一的模板码：库内非法/空值回落到路由并行，页面与后端同口径 */
const templateCode = computed(() => normalizeTemplate(props.config.maTemplate))
/** 是否固定串行模板：决定总控提示与「不受上限」文案 */
const isSequenceTemplate = computed(() => templateCode.value === MA_TEMPLATE_FIXED_SEQUENCE)
/** 是否路由后串行模板：有 JUDGE，但子按判定序串行 */
const isRouterThenSequence = computed(() => templateCode.value === MA_TEMPLATE_ROUTER_THEN_SEQUENCE)
/** 是否串行模板（固定串行 / 路由后串行）：决定关系图提示文案与上下文共享是否放开前置摘要 */
const serialTemplate = computed(() => isSerialTemplateCode(templateCode.value))
/** 是否固定并行模板：决定上限提示与参与者口径 */
const isFixedParallelTemplate = computed(() => templateCode.value === MA_TEMPLATE_FIXED_PARALLEL)
/** 是否固定模板（无判路由） */
const isFixedTemplate = computed(() => isSequenceTemplate.value || isFixedParallelTemplate.value)
/** 是否评审环模板：展示评审专家 / 轮次，隐藏多意图与父历史开关 */
const isCriticTemplate = computed(() => templateCode.value === MA_TEMPLATE_CRITIC_LOOP)
/** 是否竞争选优模板：展示选优策略，参与者需至少 2 个启用候选 */
const isCompeteTemplate = computed(() => templateCode.value === MA_TEMPLATE_COMPETE)
/** 竞争选优策略：库内非法/空值按服务端同口径回落 JUDGE_PICK */
const competeStrategy = computed(() => normalizeCompeteStrategy(props.config.competeStrategy))
/** 当前模板是否支持「投票选优」汇总（与服务端 AgentRuntimeConfig.supportsVote 同口径） */
const supportsVote = computed(() => supportsVoteTemplate(templateCode.value))
/** 评审环轮次：库内非法/空值在页面上先按服务端同口径夹紧 */
const criticRounds = computed(() => normalizeCriticMaxRounds(props.config.maCriticMaxRounds))

/** 上下文共享：串行默认前置摘要，其它模板默认隔离；显式选的共享草稿区原样保留 */
const contextPolicy = computed(() =>
  normalizeContextPolicy(props.config.maContextPolicy, templateCode.value)
)

/** 上下文共享的一句话说明：三种策略各说自己的口径，不混用文案 */
const contextHint = computed(() => {
  if (contextPolicy.value === MA_CONTEXT_SHARED_SCRATCH) {
    return '本轮子 Agent 可用 ma_scratch_get / ma_scratch_put 读写键控草稿；同键第二次写入会被拒绝（不覆盖），草稿随本轮结束丢弃。'
  }
  if (serialTemplate.value) {
    return '前置摘要：后一步可见「本步问题 + 前置终答摘要」（单步 2000 / 累计 6000 字符），不含工具原文。'
  }
  return '多项隔离：子只见自己的子问题；前置摘要只对串行模板（固定串行 / 路由后串行）生效。'
})

/*
 * 策略项的说明文案：界面上不再铺长文，改由 Label 旁问号 Tooltip 悬浮展示。
 * 放在这里集中维护，模板只引用，避免说明与控件逻辑两处漂移。
 */

/** 协作模式 Tooltip：六值合法性 + 各模板是否有判路由；路由后串行 / 竞争选优各补一句执行口径 */
const templateHint = computed(() => {
  const base =
    '六种模板都可保存；库里非法或空值回落「路由并行」。固定串行 / 固定并行 / 评审环 / 竞争选优都没有判路由；'
    + '评审环的参与者＝恰好 1 个执行者 + 1 个评审专家；竞争选优至少 2 个启用候选。'
  if (isRouterThenSequence.value) {
    return `${base}本模板先判路由选出专家与子问，再按判定顺序串行，后步只看前置摘要。`
  }
  if (isCompeteTemplate.value) {
    return `${base}本模板把同一问题同时交给多个候选，再按选优策略只取 1 个子终答，不做多段拼接。`
  }
  return base
})

/** 结果合成 Tooltip：三种汇总语义 + 各自降级口径 */
const answerModeHint = 'CONCAT 直接拼接各子终答；SUMMARIZE 用父 ROUTER 的文本模型再整合一轮；'
  + 'VOTE 由评选模型在各成功子终答里投票选一路原文作为终答（不改写）。SUMMARIZE / VOTE 失败都自动降级拼接。'

/** 评审专家 Tooltip：角色约束与轮次来源 */
const criticHint = '执行者出稿后由评审专家判定，不同意则回修。评审专家不能与执行者相同，也不必出现在候选列表里；下拉只列已启用、非 ROUTER 且允许被编排的 Agent。'

/** 最大评审轮次 Tooltip */
const criticRoundsHint = '1～3 轮，默认 1；每一轮＝一次评审判定 +（若 REVISE）一次执行者回修。'

/** 竞争选优选优策略 Tooltip：两种策略的产出与降级口径 */
const competeStrategyHint = computed(() =>
  competeStrategy.value === MA_COMPETE_STRATEGY_FIRST_SUCCESS
    ? '第一个成功子：按候选排序取最先成功的那份终答，不调用评选模型；全部失败才报错。'
    : '评选模型选优：把各子终答交给评选模型比较，选出 winner + 短理由；评选模型不可用时自动降级为第一个成功子。'
)

/** 多意图开关 Tooltip：串行与并行两种语义分开说，避免开关含义被误读 */
const multiIntentHint = computed(() =>
  isRouterThenSequence.value
    ? '关闭后只跑最匹配的 1 个专家；开启才可能按判定顺序串行多个。'
    : '关闭后只派给最匹配的一个子；开启才可能多子并行。'
)

/** 同会话继承父上下文 Tooltip：只在「恰好 1 个启用候选」时生效 */
const parentHistoryHint = '仅「只有 1 个启用候选」时生效：开＝子可继承父会话历史，关＝硬隔离只看用户原问。'

/** 子数上限 Tooltip：按模板说明上限是否真的生效 */
const maxChildrenHint = computed(() => {
  if (isRouterThenSequence.value) {
    return '路由后串行：判定条数超过上限时只跑判定顺序前 N 个，其余标「已跳过」；串行执行不并行扇出。'
  }
  if (isSequenceTemplate.value) {
    return '固定串行按顺序跑全部候选，不受此上限限制。'
  }
  if (isCriticTemplate.value) {
    return '评审环只跑 1 个执行者，不受此上限影响。'
  }
  if (isCompeteTemplate.value) {
    return '竞争选优：至少 2 个候选同时作答；超过上限的候选按排序标「已跳过」，不参与选优。'
  }
  if (isFixedParallelTemplate.value) {
    return '固定并行：超过上限的候选按排序标「已跳过」，不参与执行。'
  }
  return '路由并行：判路由最多返回这么多个意图；1～5，后端夹紧。'
})

/** 子 Agent 运行模式 Tooltip：ReAct 恒可用，P&E 需双向开关同时打开 */
const childModesHint = 'ReAct 恒可用；勾选「计划执行」后，可把 P&E 子挂为候选并真跑。子仍需自身开启「允许被编排调用」；子若开启「规划确认」，运行会暂停等待批准（同时仅一个待批）。'

/** 协作深度 Tooltip：一期硬限制一层 */
const depthHint = '一期只允许一层：子不得为 ROUTER，禁嵌套。'

/**
 * 模板码 → 中文名。关系图标签与「绑定方案摘要」共用，避免两处口径不一致。
 *
 * @param code 模板码（库内可能为空或非法，内部先归一）
 * @return 形如「模板 · 路由并行」
 */
function templateText(code: string | null | undefined): string {
  switch (normalizeTemplate(code ?? '')) {
    case MA_TEMPLATE_FIXED_SEQUENCE:
      return '模板 · 固定串行'
    case MA_TEMPLATE_FIXED_PARALLEL:
      return '模板 · 固定并行'
    case MA_TEMPLATE_CRITIC_LOOP:
      return '模板 · 评审环'
    case MA_TEMPLATE_ROUTER_THEN_SEQUENCE:
      return '模板 · 路由后串行'
    case MA_TEMPLATE_COMPETE:
      return '模板 · 竞争选优'
    default:
      return '模板 · 路由并行'
  }
}

/** 关系图与顶部标签用的模板名 */
const templateLabel = computed(() => templateText(templateCode.value))

/** 绑定方案摘要里的模板名：取 ma_def 上的 template，不用本页草稿配置 */
const boundMaTemplateLabel = computed(() => templateText(boundMa.value?.template))

/** 参与判路由的启用候选数 */
const enabledCount = computed(() => props.routes.filter((row) => row.status === 1).length)

/**
 * 汇流线宽度：由子节点数量与子卡尺寸算出「首子中心 → 末子中心」的直角干线长度。
 *
 * <p>不写死像素：卡片宽与间距统一由 CSS 变量 --map-child-w / --map-child-gap 提供，
 * 这里只乘「子节点数 - 1」段；单一子节点时为 0，等同于「根直连子卡」，不会多出一条横线。
 * 子多换行时由 CSS 的 max-width 收敛，避免横线溢出画布。
 *
 * @return 直接绑到 .map-children 的 style，只注入 --fork-bus-w 这一个自定义属性
 */
const forkBusStyle = computed<Record<string, string>>(() => {
  const spans = Math.max(0, props.routes.length - 1)
  return {
    '--fork-bus-w': `calc(${spans} * (var(--map-child-w) + var(--map-child-gap)))`
  }
})

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
 * 本编排允许的子 Agent 运行模式（归一后恒含 ReAct）。
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
 * <p>只在评审环模板下按需加载一次；切换「子 Agent 运行模式」后需要重载，
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

/**
 * 编排 Tab 是否只读。MA-P2 刀3 起编排真源收敛到 ma_def：本 Tab 只做只读展示与深链，
 * 候选增删改入口隐藏、策略控件置灰，避免出现第二套与方案分叉的配置。
 */
const tabReadOnly = true

/** 策略控件禁用条件：本 Tab 整体只读，或没有 agent:edit 权限 */
const strategyDisabled = computed(() => tabReadOnly || !props.canEdit)

/**
 * 只有「已落库的 Router + agent:bind + 非只读」才能维护候选。
 * 刀3 起 tabReadOnly 恒真 → 恒 false，保留与权限组合的原表达式便于后续放开。
 */
const canOperate = computed(() => !tabReadOnly && props.canBind && props.savedRouter)

/** 正在提交启停的路由行 id；空表示没有请求在飞 */
const rowBusy = ref<number | null>(null)

/**
 * 「候选与策略」区块的当前 Tab。
 * 默认停在候选列表（日常最高频）；策略改动走页面底栏保存，切 Tab 不丢草稿、不触发请求。
 * 用 string 而非字面量联合：el-tabs 的 v-model 回写类型是 string | number，收窄会与组件签名冲突。
 */
const activeTab = ref<string>('routes')

/** 候选编辑弹窗 */
const routeOpen = ref(false)
const routeEditOpen = ref(false)
const routeSaving = ref(false)
const editAdvanced = ref<string[]>([])
const routeDraft = reactive({ id: 0, routeKey: '', intent: '', sortOrder: 0 })

/** 校验结果；候选或策略变化后置空，避免展示过期结论 */
const validation = ref<OrchestrationValidationResult | null>(null)
const validating = ref(false)

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
    ElMessage.warning('没有运行会话查询权限，无法查看编排泳道')
    return
  }
  laneOpening.value = true
  try {
    const { data } = await pageTrialSessions({ current: 1, size: 1, agentId: props.agentId })
    laneSessionId.value = data.data?.records?.[0]?.id ?? null
    laneOpen.value = true
  } catch {
    ElMessage.error('运行会话加载失败')
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
 * 模板切换：六值都可保存；切到串行模板（固定串行 / 路由后串行）时把上下文共享默认成
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
      : (isSerialTemplateCode(next) ? MA_CONTEXT_PRIOR_SUMMARY_ONLY : MA_CONTEXT_ISOLATED),
    // 切入竞争选优时补齐策略缺省值，避免下拉出现「无选中」；其它模板保留原值不回写
    ...(next === MA_TEMPLATE_COMPETE && !props.config.competeStrategy
      ? { competeStrategy: MA_COMPETE_STRATEGY_JUDGE_PICK }
      : {})
  })
}

/**
 * 上下文共享切换：三值都收；前置摘要仍只对串行模板开放（非串行选了也收敛回隔离）。
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
      competeStrategy: props.config.competeStrategy,
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
    props.config.maChildRunModesAllowed, props.config.maCriticAgentId, props.config.maCriticMaxRounds,
    props.config.competeStrategy],
  () => {
    validation.value = null
  }
)

// 评审环下拉按需加载；切换子 Agent 运行模式后要重载，避免残留已不获允许的候选
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

// 上下文共享只允许三值白名单；非法 / 模板不兼容的值按模板默认回落，
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

// 结果合成只允许 CONCAT / SUMMARIZE / VOTE；未知值、或模板不支持投票时回落 CONCAT
watch(
  () => [props.config.answerMode, templateCode.value] as const,
  ([value, template]) => {
    const known = value === MA_AGGREGATE_CONCAT || value === MA_AGGREGATE_SUMMARIZE
      || value === MA_AGGREGATE_VOTE
    if (!known) {
      patchConfig({ answerMode: MA_AGGREGATE_CONCAT })
      return
    }
    // 切到不支持投票的模板时同步回落：避免页面显示「投票选优」却在服务端被洗成拼接
    if (value === MA_AGGREGATE_VOTE && !supportsVoteTemplate(template)) {
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

/* 方案接管提示：与卡片同宽，不额外加边框，保持浅灰底上的轻提示 */
.ma-bind-tip {
  border-radius: 10px;
}

/* 摘要里的分隔点：只做视觉分隔，不参与文案语义 */
.ma-tip-sep {
  margin: 0 4px;
  color: var(--ah-text-3, #9aa0b4);
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
  /* 子卡宽与间距集中在此：汇流线长度按这两个值 + 子节点数量算，避免把坐标写死 */
  --map-child-w: 176px;
  --map-child-gap: 12px;
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

/* 直角折线第一段：根节点底部中心 → 汇流线，始终竖直 */
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
  gap: var(--map-child-gap);
  padding-top: 18px;
}

/*
 * 直角折线第二段：一条水平汇流线，左右端点正好落在首/末子卡的中心线上。
 * 长度 = (子节点数 - 1) × (卡宽 + 间距)，由 forkBusStyle 注入 --fork-bus-w；
 * 单子时为 0（干线直接落到子卡），换行时用 max-width 收敛，不溢出画布。
 * 这里不画箭头：画布只表达主从分发，不表达流转方向。
 */
.map-children::before {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  width: var(--fork-bus-w, 0px);
  max-width: 84%;
  height: 2px;
  transform: translateX(-50%);
  background: #d9dbe7;
}

.child-node {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: var(--map-child-w);
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--ah-border, #e8e9ef);
  border-radius: 10px;
}

/* 直角折线第三段：汇流线 → 子节点顶部中心；每个子节点各画一条，天然随节点动态生成 */
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

/* Tab 切换：两个页签都占满全宽，不再左右分栏 */
.orch-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

/* Tab 顶部工具条：说明与「+ 添加候选」分列两端，新建入口不回到页头 */
.pane-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

/*
 * 候选列表滚动区：高度按「视口 - 页头/详情头/策略区/底栏」估算并封顶，
 * 超出部分在这里局部滚动，避免长列表把末行顶到页面底部固定操作栏下面。
 * 行数少时 max-height 不生效，容器自动贴合内容高度。
 */
.cand-scroll {
  max-height: calc(100vh - 480px);
  overflow-x: hidden;
  overflow-y: auto;
  /* 滚动条：细长半透明，默认藏、悬停显，不挤压列宽 */
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.cand-scroll:hover {
  scrollbar-color: rgba(107, 114, 128, 0.35) transparent;
}

.cand-scroll::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

.cand-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.cand-scroll::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background: rgba(107, 114, 128, 0.22);
}

.cand-scroll:hover::-webkit-scrollbar-thumb {
  background: rgba(107, 114, 128, 0.36);
}

.cand-scroll::-webkit-scrollbar-thumb:hover {
  background: rgba(107, 114, 128, 0.5);
}

/*
 * el-table 自带 overflow:hidden，会把自己变成 sticky 的滚动上下文，
 * 导致外层滚动时表头不吸顶。本表无固定列，放开裁剪后改为跟随 .cand-scroll 吸顶。
 */
.cand-scroll :deep(.el-table) {
  overflow: visible;
}

.cand-scroll :deep(.el-table__header-wrapper) {
  position: sticky;
  top: 0;
  z-index: 10;
  background: #fff;
}

.cand-table {
  width: 100%;
}

.cand-key {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

/* 意图列吃掉剩余宽度：过长由列自身 show-overflow-tooltip 截断，不撑破表格 */
.cand-intent {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/*
 * 策略表单：两列栅格横向展开，Label 在左、控件在右。
 * 间距由 grid gap 统一控制，清掉 el-form-item 自带的 margin，避免栅格内出现双倍留白。
 */
.strategy-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.strategy-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px 24px;
  align-items: start;
}

/* 整行字段（上下文共享、开关行）：跨满两列 */
.strategy-grid > .span-2 {
  grid-column: 1 / -1;
}

/* 开关项：独占一行，Label 在左、Switch 在右，与上方栅格字段用细线分隔 */
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 32px;
  padding-top: 14px;
  border-top: 1px solid var(--ah-border, #e8e9ef);
}

.switch-row__label {
  font-size: 14px;
  color: var(--el-text-color-regular, #606266);
}

/* Label 旁问号：解释性长文只在悬浮时出现，界面保持干净 */
.label-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tip-icon {
  color: var(--el-text-color-placeholder, #a8abb2);
  font-size: 13px;
  cursor: help;
}

/* Tooltip 正文：限制宽度并按行折行，避免超长单行撑成一条 */
.field-tip {
  max-width: 340px;
  font-size: 12px;
  line-height: 1.6;
}

.field-warn {
  margin-left: 8px;
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

/* 底栏右侧两个按钮成组，窄屏换行时不散开 */
.orch-foot__ops {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

/* 窄屏：策略栅格退化为单列，长文案已经收进 Tooltip，不会再把页面撑长 */
@media (max-width: 900px) {
  .strategy-grid {
    grid-template-columns: 1fr;
  }
}
</style>
