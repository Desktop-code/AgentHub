<template>
  <!-- is-focus：专注模式，画布吃掉页签条与主区留白后的全部高度 -->
  <div class="wf-page" :class="{ 'is-focus': focusMode }">
    <!-- 顶栏：标题侧返回列表（图标按钮）；右上为版本切换 / 校验 / 发布；编辑类按钮叠在画布顶部居中，调试入口在画布底部悬浮工具栏 -->
    <PageHeader :title="def?.name || '工作流编辑'" :description="headerDesc">
      <!-- 返回列表：只留箭头图标，名字与落点靠悬停说明；与标题垂直居中 -->
      <template #title-leading>
        <el-tooltip content="返回列表" placement="bottom">
          <el-button circle class="wf-back" aria-label="返回列表" @click="goList">
            <el-icon><ArrowLeft /></el-icon>
          </el-button>
        </el-tooltip>
      </template>
      <template #extra>
        <!-- 发布组：版本切换靠左，校验紧挨发布之前，发布固定在最右；运行历史已收进调试框「记录」页 -->
        <WorkflowReleaseSwitcher
          v-if="canPublish"
          :workflow-id="workflowId"
          :current-release-id="def?.defaultReleaseId"
          :canvas-dirty="dirty"
          @activated="onReleaseActivated"
        />

        <span class="wf-header-divider" aria-hidden="true" />
        <el-button :loading="validating" @click="onValidate">校验</el-button>
        <el-button
          v-if="canPublish"
          type="primary"
          :loading="publishing"
          :disabled="!workflowId"
          @click="onPublish"
        >
          发布
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" class="block-alert" />

    <div v-loading="loading" class="wf-body">
      <!-- 中：VueFlow 画布铺满整块；左右面板改为叠在画布上的悬浮框，不再各占一列 -->
      <div ref="canvasHost" class="wf-canvas" @dragover.prevent @drop.prevent="onDrop">
        <el-alert
          v-if="canvasError"
          :title="canvasError"
          type="error"
          show-icon
          :closable="false"
          class="canvas-alert"
        />

        <!--
          画布顶部居中：编辑类悬浮按钮组（保存草稿 / 重新加载 / 专注）。
          只留图标，名字靠悬停 tooltip；普通编辑与专注模式共用这一处摆放。
          左右悬浮框各自贴边，这组在中间，三者不互相遮挡；底部缩放与调试仍单独在胶囊里。
        -->
        <div
          class="wf-canvas-actions"
          :class="{ 'is-alerted': !!canvasError }"
          @pointerdown.stop
          @keydown.space.prevent
        >
          <el-tooltip v-if="canEdit" content="保存草稿" placement="bottom">
            <!-- 外套 span：按钮 disabled 时 tooltip 仍能触发，名字照样可见 -->
            <span class="wf-canvas-actions__slot">
              <el-button
                circle
                :loading="saving"
                :disabled="!dirty"
                aria-label="保存草稿"
                @click="onSaveDraft()"
              >
                <el-icon v-if="!saving"><DocumentChecked /></el-icon>
              </el-button>
            </span>
          </el-tooltip>
          <el-tooltip v-if="canEdit" content="重新加载" placement="bottom">
            <span class="wf-canvas-actions__slot">
              <el-button circle aria-label="重新加载" @click="reloadDraft">
                <el-icon><RefreshRight /></el-icon>
              </el-button>
            </span>
          </el-tooltip>
          <!-- 专注模式：进入用 FullScreen、退出用 ScaleToOriginal，两个图标一眼能分开；ESC 同样可退 -->
          <el-tooltip :content="focusMode ? '退出专注' : '专注模式'" placement="bottom">
            <span class="wf-canvas-actions__slot">
              <el-button
                circle
                :class="{ 'is-focus-on': focusMode }"
                :aria-label="focusMode ? '退出专注' : '专注模式'"
                :aria-pressed="focusMode"
                @click="toggleFocusMode"
              >
                <el-icon>
                  <ScaleToOriginal v-if="focusMode" />
                  <FullScreen v-else />
                </el-icon>
              </el-button>
            </span>
          </el-tooltip>
        </div>
        <VueFlow
          :node-types="nodeTypes"
          :edge-types="edgeTypes"
          :snap-to-grid="true"
          :snap-grid="[10, 10]"
          :min-zoom="0.2"
          :max-zoom="2"
          :delete-key-code="['Delete', 'Backspace']"
          :fit-view-on-init="false"
          :zoom-on-scroll="true"
          :zoom-on-pinch="true"
          :zoom-on-double-click="false"
          :pan-on-scroll="false"
          :pan-on-drag="true"
          pan-activation-key-code="Space"
          @connect="onConnect"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @pane-click="onPaneClick"
          @move-end="onCanvasMoveEnd"
          @nodes-initialized="onNodesInitialized"
          @error="onVueFlowError"
        >
          <Background :gap="16" color="#ebeef5" />
        </VueFlow>

        <!-- 底部悬浮胶囊：缩放 / 自适应 / 调试；空白处穿透，不挡画布拖拽 -->
        <!-- keydown.space.prevent：避免焦点停在按钮上时按空格误触发按钮，把空格让给画布平移 -->
        <div class="wf-dock" @pointerdown.stop @keydown.space.prevent>
          <el-tooltip content="缩小" placement="top">
            <button type="button" class="wf-dock__btn" aria-label="缩小" @click="zoomOutCanvas">
              <el-icon><Minus /></el-icon>
            </button>
          </el-tooltip>
          <span class="wf-dock__zoom" title="当前缩放比例">{{ zoomPercent }}%</span>
          <el-tooltip content="放大" placement="top">
            <button type="button" class="wf-dock__btn" aria-label="放大" @click="zoomInCanvas">
              <el-icon><Plus /></el-icon>
            </button>
          </el-tooltip>
          <span class="wf-dock__divider" aria-hidden="true" />
          <el-tooltip content="自适应画布" placement="top">
            <button
              type="button"
              class="wf-dock__btn"
              aria-label="自适应画布"
              @click="fitCanvas"
            >
              <!-- 用准心（居中适配）而不是满屏框：满屏框留给上方专注模式，避免两个图标混淆 -->
              <el-icon><Aim /></el-icon>
            </button>
          </el-tooltip>
          <!-- 调试跟随的状态入口：只在有当前目标时出现；暂停后点一下立刻对准并恢复 -->
          <template v-if="followHasTarget">
            <span class="wf-dock__divider" aria-hidden="true" />
            <el-tooltip
              :content="followPaused ? '继续跟随当前执行节点' : '跟随中：点此重新对准当前节点'"
              placement="top"
            >
              <button
                type="button"
                class="wf-dock__follow"
                :class="{ 'is-paused': followPaused }"
                :aria-label="followPaused ? '继续跟随当前执行节点' : '重新对准当前执行节点'"
                @click="resumeFollow()"
              >
                {{ followPaused ? '已暂停' : '跟随' }}
              </button>
            </el-tooltip>
          </template>
          <button
            v-if="canRunTrial"
            type="button"
            class="wf-dock__run"
            :disabled="!workflowId || validating"
            @click="onDebug"
          >
            调试
          </button>
        </div>
      </div>

      <!--
        右：调试悬浮框（比属性框 / 校验框更宽的独立框）。叠在画布上，不占一列；
        上、下留出空隙，露出画布顶部按钮组与底部缩放 / 调试入口；只点框右上角 X 才关。
      -->
      <WorkflowTrialDrawer
        v-model="trialVisible"
        :workflow-id="workflowId"
        :workflow-name="def?.name || ''"
        :can-act="canHumanAct"
        :page-active="pageActive"
        :prepare-start="prepareDebugStart"
        @run-change="onRunChange"
      />

      <!-- 左：节点库悬浮框。只点框上的关闭才收起，点画布 / 拖动画布都不收；调试框开着时整体让位 -->
      <Transition name="wf-wake">
        <aside v-if="paletteOpen && !trialVisible" class="wf-palette wf-panel" aria-label="节点库">
          <div class="wf-panel__head">
            <h3 class="wf-panel__title">节点</h3>
            <el-tooltip content="关闭节点库" placement="right">
              <button
                type="button"
                class="wf-panel__close"
                aria-label="关闭节点库"
                @click="paletteOpen = false"
              >
                <el-icon><Close /></el-icon>
              </button>
            </el-tooltip>
          </div>
          <div class="wf-panel__inner">
            <div class="wf-palette__list">
              <div
                v-for="item in PALETTE"
                :key="item.type"
                class="wf-palette__item"
                :class="{ 'is-p1': !item.p0 }"
                :draggable="item.p0"
                :title="item.p0 ? `拖到画布添加「${item.label}」` : 'P1：本刀不实现'"
                @dragstart="onDragStart(item, $event)"
              >
                <span class="wf-palette__label">{{ item.label }}</span>
                <span v-if="!item.p0" class="wf-palette__p1">P1</span>
                <span class="wf-palette__hint">{{ item.hint }}</span>
              </div>
            </div>
          </div>
        </aside>
      </Transition>
      <!-- 左侧关闭后的常驻小入口：点开重新显示节点库，保证还能往画布加节点；调试期间一并收起，避免再拉开节点栏 -->
      <Transition name="wf-wake">
        <button
          v-if="!paletteOpen && !trialVisible"
          type="button"
          class="wf-float-entry"
          aria-label="打开节点库"
          @click="paletteOpen = true"
        >
          <el-icon class="wf-float-entry__icon"><ArrowRight /></el-icon>
          <span class="wf-float-entry__label">节点</span>
        </button>
      </Transition>

      <!-- 右：属性悬浮框。选中节点 / 连线时浮出；只点关闭才收起，再次选中会重新打开。校验结果框打开时让位给校验框；调试框开着时整体让位 -->
      <Transition name="wf-wake">
        <aside v-if="propsOpen && !validationOpen && !trialVisible" class="wf-props wf-panel" aria-label="属性面板">
          <div class="wf-panel__head">
            <h3 class="wf-panel__title">
              <template v-if="selectedNode">
                节点属性
                <el-tag size="small" effect="plain">{{ nodeTypeLabel(selectedNode.data.nodeType) }}</el-tag>
              </template>
              <template v-else-if="selectedEdge">
                连线属性
                <el-tag size="small" effect="plain">{{ edgeTypeLabel(selectedEdge.data.edgeType) }}</el-tag>
              </template>
              <template v-else>属性</template>
            </h3>
            <el-tooltip content="关闭属性面板" placement="left">
              <button
                type="button"
                class="wf-panel__close"
                aria-label="关闭属性面板"
                @click="propsOpen = false"
              >
                <el-icon><Close /></el-icon>
              </button>
            </el-tooltip>
          </div>
          <div class="wf-panel__inner">
        <!-- 工作流级运行时限：不随选中态隐藏，不选中任何元素也能改；单位分钟，不露内部键名 -->
        <el-form label-position="top" class="wf-form wf-limits" @submit.prevent>
          <div class="wf-limits__head">
            <span class="wf-limits__title">运行时限</span>
            <span class="wf-limits__hint">留空按平台上限 {{ platformMaxMinutes }} 分钟</span>
          </div>
          <el-form-item label="整段运行上限">
            <el-input
              v-model="runMinutesText"
              class="wf-field"
              placeholder="留空 = 平台上限"
              inputmode="numeric"
              maxlength="5"
              clearable
              :disabled="!canEdit"
            >
              <template #append>分钟</template>
            </el-input>
          </el-form-item>
          <el-form-item label="单节点上限">
            <el-input
              v-model="nodeMinutesText"
              class="wf-field"
              placeholder="留空 = 不限制单节点"
              inputmode="numeric"
              maxlength="5"
              clearable
              :disabled="!canEdit"
            >
              <template #append>分钟</template>
            </el-input>
          </el-form-item>
          <div v-if="limitsError" class="wf-tip wf-tip--error">{{ limitsError }}</div>
          <div v-else class="wf-tip">等待用户回复或人工处理的时间不计入这两项。</div>
        </el-form>
        <template v-if="selectedNode">
          <el-form label-position="top" class="wf-form">
            <el-form-item label="编码 code" required>
              <el-input v-model="selectedNode.data.code" maxlength="64" :disabled="!canEdit" />
            </el-form-item>
            <el-form-item label="显示名">
              <el-input v-model="selectedNode.data.name" maxlength="64" :disabled="!canEdit" />
            </el-form-item>

            <!-- START：对外入参声明；这里是合同，不是给自己配来源 -->
            <template v-if="selectedNode.data.nodeType === 'START'">
              <el-form-item label="入参声明">
                <InputDeclarationEditor
                  :model-value="selectedNode.data.config.inputSchema"
                  :disabled="!canEdit"
                  @update:model-value="setInputDeclaration"
                />
              </el-form-item>
              <div class="wf-tip">
                声明只影响编排按哪些键出槽；调试框与工作台仍只收一个问题。旧的开始节点没有声明也能照常运行、照常发布。
              </div>
            </template>

            <!-- AGENT：只能绑叶子 Agent，ROUTER 已在选项中排除 -->
            <template v-if="selectedNode.data.nodeType === 'AGENT'">
              <el-form-item label="绑定 Agent" required>
                <el-alert
                  v-if="agentLoadError"
                  :title="agentLoadError"
                  type="error"
                  show-icon
                  :closable="false"
                  class="block-alert"
                />
                <el-select
                  v-else
                  :model-value="selectedNode.data.config.agentId"
                  class="wf-field"
                  clearable
                  filterable
                  placeholder="选择 Agent（不含 ROUTER）"
                  :disabled="!canEdit"
                  @update:model-value="setNodeAgent"
                >
                  <el-option
                    v-for="item in agentOptions"
                    :key="item.id"
                    :label="`${item.name}（${item.code}）`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <!-- 画布节点标题会优先显示绑定 Agent 名称；这里说明回退规则，避免用户以为「显示名」失效 -->
              <div class="wf-tip">
                画布上优先显示绑定 Agent 的名称；未绑定或 Agent 已删除时回退显示「显示名」。
              </div>
              <el-alert
                v-if="routerBindingHint"
                :title="routerBindingHint"
                type="error"
                show-icon
                :closable="false"
                class="block-alert"
              />
              <!--
                对话停等：勾上后该 Agent 节点产出的问句会进对话正文，运行挂起等同一会话的下一句。
                不勾＝原行为（节点一次跑完继续往下），因此存量图不用改。
              -->
              <el-form-item label="等用户回复">
                <el-switch
                  :model-value="selectedNode.data.config.awaitReply === true"
                  :disabled="!canEdit"
                  @update:model-value="setAwaitReply"
                />
              </el-form-item>
              <div class="wf-tip">
                勾上后：本节点产出的问句直接写进工作台对话，运行停在「等待你的回复」；
                用户在本会话输入框发下一句，会送回同一条运行继续，后续条件边可读
                <code>$.input.reply</code>。不勾＝节点跑完直接继续，行为与原来一致。
              </div>
            </template>

            <!-- TOOL：直接绑定工具项（固定宽度单选浮层，对齐可选资源左栏排版） -->
            <template v-else-if="selectedNode.data.nodeType === 'TOOL'">
              <el-form-item label="绑定工具" required>
                <el-alert
                  v-if="toolLoadError"
                  :title="toolLoadError"
                  type="error"
                  show-icon
                  :closable="false"
                  class="block-alert"
                />
                <WorkflowToolBindPicker
                  v-else
                  class="wf-field"
                  :model-value="selectedNode.data.config.toolId"
                  :tools="toolOptions"
                  :disabled="!canEdit"
                  :fallback-label="selectedNode.data.toolTitle || selectedNode.data.toolName || ''"
                  @update:model-value="setNodeTool"
                />
              </el-form-item>
              <!-- 画布节点标题会优先显示工具显示名；这里说明回退规则 -->
              <div class="wf-tip">
                画布上优先显示工具的显示名；未绑定或工具已删除时回退显示「显示名」。
              </div>
            </template>

            <!-- SUBGRAPH：绑已发布工作流并钉死版本；运行时只跑该版本，出参挂回本节点 outputs -->
            <template v-else-if="selectedNode.data.nodeType === 'SUBGRAPH'">
              <el-form-item label="被调工作流" required>
                <el-alert
                  v-if="subgraphWorkflowsError"
                  :title="subgraphWorkflowsError"
                  type="error"
                  show-icon
                  :closable="false"
                  class="block-alert"
                />
                <el-select
                  v-else
                  :model-value="selectedNode.data.config.subWorkflowId"
                  class="wf-field"
                  clearable
                  filterable
                  :loading="subgraphWorkflowsLoading"
                  placeholder="选择已发布的工作流（同租户）"
                  :disabled="!canEdit"
                  @update:model-value="setSubgraphWorkflow"
                >
                  <el-option
                    v-for="item in subgraphWorkflowOptions"
                    :key="item.id"
                    :label="`${item.name}（${item.code}）`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="发布版本" required>
                <el-alert
                  v-if="subgraphReleasesError"
                  :title="subgraphReleasesError"
                  type="error"
                  show-icon
                  :closable="false"
                  class="block-alert"
                />
                <el-select
                  v-else
                  :model-value="selectedNode.data.config.subReleaseId"
                  class="wf-field"
                  clearable
                  :loading="subgraphReleasesLoading"
                  :disabled="!canEdit || !selectedNode.data.config.subWorkflowId"
                  placeholder="必须钉死一个已发布版本"
                  @update:model-value="setSubgraphRelease"
                >
                  <el-option
                    v-for="item in subgraphReleaseOptions"
                    :key="item.id"
                    :label="`v${item.version}${item.publishNote ? ` · ${item.publishNote}` : ''}`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
              <div class="wf-tip">
                入参槽按「该发布版本」开始节点上的声明出槽；换版本时同名键保留已配来源，不再属于新声明的旧键标为「未在定义中」。
              </div>
              <div class="wf-tip">
                运行时只跑这里钉死的发布版本，对方后来发的新版不会影响本图；子图整张图的产出挂到本节点的
                $.outputs 下供下游取用。
              </div>
              <div class="wf-tip wf-tip--error">
                子图可以继续嵌套，但最多 3 层；调用链上同一工作流出现两次会按循环拒绝运行。
              </div>
            </template>

            <!-- CONDITION：JSONLogic 子集，禁 SpEL -->
            <template v-else-if="selectedNode.data.nodeType === 'CONDITION'">
              <el-form-item label="条件表达式（JSONLogic 子集）">
                <JsonLogicEditor
                  :model-value="selectedNode.data.config.condition"
                  @update:model-value="setCondition"
                />
              </el-form-item>
              <div class="wf-tip">条件节点必须恰好有 1 条兜底出边（DEFAULT，或指向循环节点的 LOOP_BACK）。</div>
            </template>

            <!-- HUMAN：超时策略必填 -->
            <template v-else-if="selectedNode.data.nodeType === 'HUMAN'">
              <el-form-item label="超时策略" required>
                <el-select
                  v-model="selectedNode.data.config.timeoutPolicy"
                  class="wf-field"
                  clearable
                  placeholder="必须选择"
                  :disabled="!canEdit"
                >
                  <el-option
                    v-for="item in TIMEOUT_POLICIES"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <div v-if="!selectedNode.data.config.timeoutPolicy" class="wf-tip wf-tip--error">
                未配置超时策略会阻塞发布；待办流转与提醒留给后续刀。
              </div>
              <el-form-item label="超时毫秒数（可选）">
                <el-input-number
                  v-model="selectedNode.data.config.timeoutMs"
                  class="wf-field"
                  :min="0"
                  :step="60000"
                  controls-position="right"
                  :disabled="!canEdit"
                />
              </el-form-item>
              <!-- W4 处理人规则：空=有 human:act 即可；非法规则校验器会拒发 -->
              <el-form-item label="处理人规则">
                <el-select
                  v-model="assigneeKind"
                  class="wf-field"
                  :disabled="!canEdit"
                >
                  <el-option v-for="item in ASSIGNEE_KINDS" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
              <el-form-item
                v-if="assigneeKind !== 'EMPTY'"
                :label="assigneeKind === 'ROLE' ? '角色编码' : '用户 ID 列表'"
              >
                <el-input
                  v-model="assigneeValue"
                  maxlength="128"
                  :placeholder="assigneeKind === 'ROLE' ? '如 ops_admin' : '如 1,2,7（逗号分隔）'"
                  :disabled="!canEdit"
                />
              </el-form-item>
              <div v-if="assigneeParsed.problem" class="wf-tip wf-tip--error">
                规则不合法：{{ assigneeParsed.problem }}（发布会失败）
              </div>
              <div v-else-if="assigneeKind === 'EMPTY'" class="wf-tip">
                未配置处理人：任何具备 workflow:human:act 的用户都能处理。建议配置角色或用户，避免误批。
              </div>
              <div v-else class="wf-tip">仅命中该规则且具备 workflow:human:act 的用户可处理；无超级管理员绕过。</div>
            </template>

            <template v-else-if="selectedNode.data.nodeType === 'LOOP'">
              <WorkflowLoopFields
                :key="selectedNode.id"
                :max-iterations="loopMaxIterations"
                :on-exceeded="String(selectedNode.data.config.onExceeded || '')"
                :feedback-path="String(selectedNode.data.config.feedbackPath || '')"
                :feedback-sources="loopBackSources"
                :cap="platformMaxLoopIterations"
                :disabled="!canEdit"
                @update:max-iterations="setLoopField('maxIterations', $event)"
                @update:on-exceeded="setLoopField('onExceeded', $event)"
                @update:feedback-path="setLoopField('feedbackPath', $event)"
              />
            </template>

            <!-- FORK / JOIN：同组才能成对 -->
            <template
              v-else-if="
                selectedNode.data.nodeType === 'PARALLEL_FORK' ||
                selectedNode.data.nodeType === 'PARALLEL_JOIN'
              "
            >
              <el-form-item label="并行组 forkGroupId" required>
                <el-input
                  v-model="selectedNode.data.config.forkGroupId"
                  maxlength="64"
                  placeholder="如 g1；分叉与汇合必须一致"
                  :disabled="!canEdit"
                />
              </el-form-item>
            </template>

            <!-- 入参映射：可视化「来源节点 → 输出字段」，落库仍是 $.input / $.outputs / $.vars 路径 -->
            <template v-if="showMapping">
              <el-form-item label="入参映射">
                <!-- 按节点重建编辑器：切换节点时不清空槽位状态，避免残留上一个节点的行 -->
                <InputMappingEditor
                  :key="selectedNode.id"
                  :model-value="selectedNode.data.config.inputMapping"
                  :sources="mappingSources"
                  :start-codes="startCodes"
                  :node-type="String(selectedNode.data.nodeType || '')"
                  :tool-schema-json="selectedToolSchema"
                  :input-declaration="selectedInputDeclaration"
                  :declaration-loading="selectedDeclarationLoading"
                  :disabled="!canEdit"
                  @update:model-value="setInputMapping"
                />
              </el-form-item>
              <el-form-item label="出参映射">
                <OutputMappingEditor
                  :model-value="selectedNode.data.config.outputMapping"
                  :disabled="!canEdit"
                  @update:model-value="setOutputMapping"
                />
              </el-form-item>
            </template>

            <el-button v-if="canEdit" type="danger" plain class="wf-delete" @click="deleteSelected">
              删除节点
            </el-button>
          </el-form>
        </template>

        <template v-else-if="selectedEdge">
          <el-form label-position="top" class="wf-form">
            <el-form-item label="连线类型">
              <el-select
                :model-value="selectedEdge.data.edgeType"
                class="wf-field"
                :disabled="!canEdit"
                @update:model-value="setEdgeType"
              >
                <el-option v-for="item in EDGE_TYPES" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="显示标签">
              <el-input
                :model-value="edgeLabelInput"
                maxlength="64"
                placeholder="留空则按类型显示（条件 / 默认 / 异常）"
                :disabled="!canEdit"
                @update:model-value="setEdgeLabel"
              />
            </el-form-item>
            <el-form-item v-if="selectedEdge.data.edgeType === 'COND'" label="条件表达式（JSONLogic 子集）">
              <JsonLogicEditor
                :model-value="selectedEdge.data.condition"
                @update:model-value="setEdgeCondition"
              />
            </el-form-item>
            <div v-if="selectedEdge.data.edgeType === 'DEFAULT'" class="wf-tip">
              DEFAULT 是条件节点兜底出口，每个条件节点只允许 1 条。
            </div>
            <div v-else-if="selectedEdge.data.edgeType === 'LOOP_BODY'" class="wf-tip">
              进入循环体：只能从循环节点拉出，指向要重复执行的第一步。
            </div>
            <div v-else-if="selectedEdge.data.edgeType === 'LOOP_BACK'" class="wf-tip">
              回到循环：必须指向循环节点。没有这条声明的回边，保存会被拒绝。
            </div>
            <div v-else-if="selectedEdge.data.edgeType === 'LOOP_EXIT'" class="wf-tip">
              次数用尽后离开循环。失败出路不要这条边。
            </div>
            <el-button v-if="canEdit" type="danger" plain class="wf-delete" @click="deleteSelected">
              删除连线
            </el-button>
          </el-form>
        </template>

        <div v-else class="wf-props__empty">
          <p>在画布上选中节点或连线以编辑属性。</p>
          <p class="wf-props__empty-hint">从左侧拖入节点，从节点两侧锚点拉线连线。</p>
        </div>
          </div>
        </aside>
      </Transition>

      <!--
        右：校验结果悬浮框。与属性框同样贴右、同宽同上下留白，但独立存在、不共用同一个框。
        只由框上的关闭（X）收起；点画布 / 拖动画布都不关。打开时属性框暂时让位，关掉后按原开合状态恢复。
      -->
      <Transition name="wf-wake">
        <aside v-if="validationOpen" class="wf-validate wf-panel" aria-label="校验结果">
          <div class="wf-panel__head">
            <!-- 不设标题，顶部只放错误 / 提示计数 -->
            <div class="wf-validate__counts">
              <el-tag size="small" type="danger" effect="plain">{{ errorCount }} 错误</el-tag>
              <el-tag size="small" type="warning" effect="plain">{{ warnCount }} 提示</el-tag>
            </div>
            <el-tooltip content="关闭校验结果" placement="left">
              <button
                type="button"
                class="wf-panel__close"
                aria-label="关闭校验结果"
                @click="validationOpen = false"
              >
                <el-icon><Close /></el-icon>
              </button>
            </el-tooltip>
          </div>
          <div class="wf-panel__inner wf-validate__inner">
            <!-- 图改过之后的结果会过期；提示只放在框内，不另外弹窗打扰编辑 -->
            <p v-if="issuesStale" class="wf-validate__stale">图已修改，建议重新校验</p>
            <p v-if="!issues.length" class="wf-validate__empty">校验通过，没有错误或提示。</p>
            <button
              v-for="issue in issues"
              :key="issue.key"
              type="button"
              class="wf-issue"
              :class="issue.level === 'error' ? 'is-error' : 'is-warn'"
              :title="issue.nodeCode ? `定位到 ${issue.nodeCode}` : '无关联节点'"
              @click="focusIssue(issue)"
            >
              <span class="wf-issue__code">{{ issue.code }}</span>
              <span class="wf-issue__msg">{{ issue.message }}</span>
            </button>
          </div>
        </aside>
      </Transition>
    </div>

    <!-- 底栏：草稿状态 + 上次校验计数 -->
    <div class="wf-footer">
      <span class="wf-footer__left">{{ draftHint }}</span>
      <span class="wf-footer__right">
        <template v-if="lastResult">
          上次校验：<b :class="lastResult.errors ? 'is-error' : ''">{{ lastResult.errors }}</b> 错误 /
          <b :class="lastResult.warns ? 'is-warn' : ''">{{ lastResult.warns }}</b> 提示
        </template>
        <template v-else>尚未校验</template>
        <span v-if="dirty" class="wf-footer__dirty">· 草稿有未保存修改</span>
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'WorkflowEditor' })
/**
 * 工作流画布编辑页。所属域：workflow。
 * <p>职责：VueFlow 建图（W1）、存草稿（updateTime 乐观锁）、图校验、发布不可变快照，
 * 以及挂「调试」悬浮框（先静默校验当前画布，再按草稿调试）。版本切换入口在顶栏「发布」旁，
 * 调试框「记录」页签只放调试记录与调用追踪外链。
 * <p>边界：引擎在后端；本页不发模型/工具出站，也不参与 run 状态计算。
 * <p>单一真源：画布状态在 VueFlow store；节点/边的修改都走 store API，
 * 保存时从 store 读，避免「画布显示的」和「保存出去的」不一致。
 */
import {
  computed,
  markRaw,
  nextTick,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  provide,
  ref,
  watch
} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Aim, ArrowLeft, ArrowRight, Close, DocumentChecked, FullScreen, Minus, Plus, RefreshRight, ScaleToOriginal } from '@element-plus/icons-vue'
import { MarkerType, VueFlow, useVueFlow } from '@vue-flow/core'
import type {
  Connection,
  Edge,
  EdgeMouseEvent,
  EdgeTypesObject,
  GraphEdge,
  GraphNode,
  Node,
  NodeMouseEvent,
  NodeTypesObject
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import PageHeader from '@/components/PageHeader.vue'
import { useWorkflowFocus } from '@/composables/workflowFocus'
import InputMappingEditor from './InputMappingEditor.vue'
import OutputMappingEditor from './OutputMappingEditor.vue'
import JsonLogicEditor from './JsonLogicEditor.vue'
import WorkflowLoopFields from './WorkflowLoopFields.vue'
import WorkflowNodeCard from './WorkflowNodeCard.vue'
import WorkflowToolBindPicker from './WorkflowToolBindPicker.vue'
import WorkflowEdge from './WorkflowEdge.vue'
import { WORKFLOW_EDGE_INTERACTION } from './workflowEdgeInteraction'
import WorkflowTrialDrawer from './WorkflowTrialDrawer.vue'
import WorkflowReleaseSwitcher from './WorkflowReleaseSwitcher.vue'
import InputDeclarationEditor from '@/components/common/InputDeclarationEditor.vue'
import { useSubgraphBindings } from './useSubgraphBindings'
import { useWorkflowRunFollow } from './useWorkflowRunFollow'
import { pageAgents, getAgent } from '@/api/agent'
import { listAllItems } from '@/api/tool'
import {
  getWorkflowDef,
  getWorkflowDraft,
  publishWorkflow,
  saveWorkflowDraft,
  validateWorkflow,
  validateWorkflowGraph
} from '@/api/workflow'
import { useUserStore } from '@/stores/user'
import { parseAssigneeRule } from '@/utils/workflowAssignee'
import { sameMapping, singleBackSourcePrefill } from '@/utils/workflowMapping'
import {
  dropEmptyInputMapping,
  missingRequiredSlotKeys,
  parseToolInputSlots
} from '@/utils/workflowInputSlots'
import { declaredInputSlots } from '@/utils/workflowInputDeclaration'
import type { AgentCard } from '@/types/agent'
import type { ToolItemRow } from '@/types/tool'
import type {
  WorkflowDefRow,
  WorkflowEdgeType,
  WorkflowFlowEdgeData,
  WorkflowFlowNodeData,
  WorkflowGraph,
  WorkflowGraphEdge,
  WorkflowGraphLimits,
  WorkflowGraphNode,
  WorkflowIssue,
  WorkflowMappingSource,
  WorkflowNodeConfig,
  WorkflowNodeType,
  WorkflowPaletteItem,
  WorkflowReleaseActivateResult,
  WorkflowRunDetail,
  WorkflowValidateResp
} from '@/types/workflow'

/** 拖拽节点时写入的 MIME，避免和外部拖拽混淆 */
const DRAG_MIME = 'application/x-agenthub-workflow-node'

/** 节点面板：P0 可拖，P1 灰显 */
const PALETTE: WorkflowPaletteItem[] = [
  { type: 'START', label: '开始', hint: '唯一入口', p0: true },
  { type: 'END', label: '结束', hint: '至少 1 个', p0: true },
  { type: 'AGENT', label: 'Agent', hint: '调叶子 Agent，禁 ROUTER', p0: true },
  { type: 'TOOL', label: '工具', hint: '直调工具项', p0: true },
  { type: 'CONDITION', label: '条件', hint: 'COND + 恰好 1 条 DEFAULT', p0: true },
  { type: 'PARALLEL_FORK', label: '并行分叉', hint: '按 forkGroupId 成对', p0: true },
  { type: 'PARALLEL_JOIN', label: '并行汇合', hint: '按 forkGroupId 成对', p0: true },
  { type: 'HUMAN', label: '人工审批', hint: '必须配超时策略', p0: true },
  { type: 'LOOP', label: '循环', hint: '显式声明次数和超限出路', p0: true },
  { type: 'SUBGRAPH', label: '子图', hint: '绑已发布工作流并钉死版本', p0: true },
  { type: 'KB_RETRIEVE', label: '知识检索', hint: 'P1', p0: false },
  { type: 'SKILL_INJECT', label: 'Skill 注入', hint: 'P1', p0: false },
  { type: 'TIMER', label: '定时等待', hint: 'P1', p0: false },
  { type: 'COMPENSATE', label: '补偿', hint: 'P1', p0: false }
]

/** 节点默认显示名 */
const NODE_NAME: Record<string, string> = {
  START: '开始',
  END: '结束',
  AGENT: 'Agent',
  TOOL: '工具',
  CONDITION: '条件',
  PARALLEL_FORK: '并行分叉',
  PARALLEL_JOIN: '并行汇合',
  HUMAN: '人工审批',
  LOOP: '循环',
  SUBGRAPH: '子图'
}

/** code 生成前缀 */
const CODE_PREFIX: Record<string, string> = {
  START: 'start',
  END: 'end',
  AGENT: 'agent',
  TOOL: 'tool',
  CONDITION: 'cond',
  PARALLEL_FORK: 'fork',
  PARALLEL_JOIN: 'join',
  HUMAN: 'human',
  LOOP: 'loop',
  SUBGRAPH: 'sub'
}

/** 类型中文名（属性面板标题用） */
const NODE_TYPE_LABEL: Record<string, string> = {
  START: '开始',
  END: '结束',
  AGENT: 'Agent',
  TOOL: '工具',
  CONDITION: '条件',
  PARALLEL_FORK: '并行分叉',
  PARALLEL_JOIN: '并行汇合',
  HUMAN: '人工审批',
  LOOP: '循环',
  SUBGRAPH: '子图'
}

/** 边类型下拉；CANCEL 本刀不产出 */
const EDGE_TYPES: Array<{ label: string; value: WorkflowEdgeType }> = [
  { label: '普通', value: 'NORMAL' },
  { label: '条件', value: 'COND' },
  { label: '默认（兜底）', value: 'DEFAULT' },
  { label: '异常', value: 'ERROR' },
  { label: '进入循环体', value: 'LOOP_BODY' },
  { label: '回到循环', value: 'LOOP_BACK' },
  { label: '次数用尽', value: 'LOOP_EXIT' }
]

const EDGE_TYPE_LABEL: Record<string, string> = {
  NORMAL: '普通',
  COND: '条件',
  DEFAULT: '默认',
  ERROR: '异常',
  LOOP_BODY: '进入循环体',
  LOOP_BACK: '回到循环',
  LOOP_EXIT: '次数用尽'
}

/** 人工卡点超时策略，与后端枚举一致 */
const TIMEOUT_POLICIES = [
  { label: '超时自动拒绝', value: 'AUTO_REJECT' },
  { label: '超时自动通过', value: 'AUTO_APPROVE' },
  { label: '超时升级处理', value: 'ESCALATE' }
]

/** 人工卡点处理人规则类型（W4）；空=任何有 human:act 的人可处理 */
const ASSIGNEE_KINDS = [
  { label: '不限（任何有处理权限的人）', value: 'EMPTY' },
  { label: '按角色（ROLE:角色码）', value: 'ROLE' },
  { label: '按用户（USER:用户id）', value: 'USER' }
]

const user = useUserStore()
const route = useRoute()
const router = useRouter()

/** 组件名固定，按 data.nodeType 上色，避免注册 15 个节点类型 */
const nodeTypes = { workflow: markRaw(WorkflowNodeCard) } as NodeTypesObject
/**
 * 自定义边注册：只接管走线渲染与拐点拖拽，
 * 边的业务类型（NORMAL / COND / LOOP_* 等）仍读 data.edgeType，不受影响。
 */
const edgeTypes = { workflow: markRaw(WorkflowEdge) } as EdgeTypesObject

const vf = useVueFlow()
const {
  addNodes,
  addEdges,
  removeNodes,
  removeEdges,
  findNode,
  screenToFlowCoordinate,
  getViewport,
  fitView,
  setCenter,
  setViewport,
  zoomIn,
  zoomOut,
  viewport
} = vf

/** 专注模式跨壳层开关；本页只负责进入 / 退出与失活复位 */
const { setFocus: setWorkflowFocus } = useWorkflowFocus()

/** 下拉第一页以外的 Agent 名称 / 声明缓存（id → 名称 / 编码 / 入参声明），只服务画布展示与出槽 */
const agentLabelCache = new Map<number, { name: string; code: string; inputSchema?: unknown }>()
/** 详情接口也解析不到的 id（已删除 / 无权限），避免反复请求 */
const agentLabelMissing = new Set<number>()
/** 详情补查进行中标记：防止回填触发递归请求 */
let agentLabelResolving = false
/** Agent 下拉是否已加载完（成功或失败）：未完成前不补查详情，避免多余请求 */
let agentOptionsReady = false

interface SelectedElement {
  kind: 'node' | 'edge'
  id: string
}

interface IssueItem extends WorkflowIssue {
  level: 'error' | 'warn'
  key: string
}

const workflowId = computed(() => {
  const raw = route.params.id
  const text = Array.isArray(raw) ? raw[0] : raw
  const numeric = Number(text)
  return Number.isFinite(numeric) && numeric > 0 ? numeric : 0
})

const loading = ref(false)
const loadError = ref('')
const canvasError = ref('')
const def = ref<WorkflowDefRow | null>(null)
const draftUpdateTime = ref('')
/** 最近一次「载入或保存」的图签名，用于脏检查 */
const baseline = ref('')
const validatedSignature = ref('')

const saving = ref(false)
const validating = ref(false)
const publishing = ref(false)

const issues = ref<IssueItem[]>([])
const lastResult = ref<{ errors: number; warns: number } | null>(null)

/*
 * 工作流级运行时限（单位分钟）。用文本 ref 而非数字：用户需要能「清空」表示跟随平台，
 * 数字输入框清空时会变成 0 / NaN，反而更难表达「未配置」。提交前统一解析成整数。
 */
const runMinutesText = ref('')
const nodeMinutesText = ref('')
/** 平台硬顶（分钟）：由草稿接口下发，页面只显示分钟，不显示 yml 键名与毫秒 */
const platformMaxMinutes = ref(60)
/** 循环次数平台硬顶：画布输入不能超过它 */
const platformMaxLoopIterations = ref(20)

/** 解析一个「分钟」文本框：空 → null；非正整数 → NaN */
function parseMinutesText(raw: string): number | null {
  const text = String(raw ?? '').trim()
  if (!text) {
    return null
  }
  if (!/^\d+$/.test(text)) {
    return Number.NaN
  }
  const value = Number(text)
  return value > 0 ? value : Number.NaN
}

const runMinutesValue = computed(() => parseMinutesText(runMinutesText.value))
const nodeMinutesValue = computed(() => parseMinutesText(nodeMinutesText.value))

/**
 * 时限输入的本地校验：只出中文（分钟 + 平台上限），不出现毫秒或配置键。
 * 后端还会再校验一次，这里只负责让用户保存前就看到问题。
 */
const limitsError = computed(() => {
  const run = runMinutesValue.value
  const node = nodeMinutesValue.value
  if (Number.isNaN(run)) {
    return '整段运行上限请填大于 0 的整数分钟（留空表示跟随平台上限）'
  }
  if (run !== null && run > platformMaxMinutes.value) {
    return `整段运行上限不能超过平台上限 ${platformMaxMinutes.value} 分钟`
  }
  if (Number.isNaN(node)) {
    return '单节点上限请填大于 0 的整数分钟（留空表示不限制单节点）'
  }
  // 单节点不得超过整段：整段留空时，整段实际上就是平台上限
  const effectiveRun = run ?? platformMaxMinutes.value
  if (node !== null && node > effectiveRun) {
    return `单节点上限不能超过整段运行上限 ${effectiveRun} 分钟`
  }
  return ''
})

const selected = ref<SelectedElement | null>(null)

const agentOptions = ref<AgentCard[]>([])
const toolOptions = ref<ToolItemRow[]>([])
const agentLoadError = ref('')
const toolLoadError = ref('')

const canEdit = computed(() => user.hasPerm('workflow:def:edit'))
const canPublish = computed(() => user.hasPerm('workflow:def:publish'))
/** 调试入口：点开后先静默校验，通过才浮出调试框；无权限时隐藏 */
const canRunTrial = computed(() => user.hasPerm('workflow:run:trial'))
/** 人工待办处理权限；无此码时调试框内只读 */
const canHumanAct = computed(() => user.hasPerm('workflow:human:act'))

const trialVisible = ref(false)

/**
 * 本页是否在前台。KeepAlive 缓存根切走 / 切回时翻转，
 * 透传给调试框，让它停 / 起轮询与秒表（调试框是子组件，收不到 deactivated）。
 */
const pageActive = ref(true)

/* ===== 悬浮面板与画布工具栏（纯 UI 状态，不参与 graph_json） ===== */
/** 左「节点库」悬浮框是否打开；只由框上的关闭改成 false，左边缘入口再改回 true */
const paletteOpen = ref(true)
/**
 * 右「属性」悬浮框是否打开。
 * <p>选中节点 / 连线时置 true；只由框上的关闭改成 false。
 * <p>点画布清空选中不关它，避免「点一下空白，面板就没了」。
 */
const propsOpen = ref(false)
/**
 * 右「校验结果」悬浮框是否打开。
 * <p>与属性框独立：点顶栏「校验」或有错误 / 提示时浮出；只由框上的关闭改成 false。
 * <p>打开期间属性框让位（不销毁 propsOpen 状态），关掉校验框后按原状态恢复。
 */
const validationOpen = ref(false)
/** 专注模式：只通知壳层隐藏全局侧导航，左右面板仍是同一套悬浮框 */
const focusMode = ref(false)
/** 当前缩放百分比，供悬浮工具栏展示 */
const zoomPercent = computed(() => Math.round((viewport.value?.zoom ?? 1) * 100))

/**
 * 复位悬浮面板为「刚进入页面」的默认视野：节点库打开，属性框 / 校验结果框等选中或校验再浮出。
 * <p>本页被 KeepAlive 缓存，面板开合是纯 UI 状态、不该跨路由残留。
 */
function resetPanelLayout() {
  paletteOpen.value = true
  propsOpen.value = false
  validationOpen.value = false
}

/**
 * 进入调试时收起编辑侧两个悬浮框。
 * <p>收起只是这次调试期间的临时布局：调试框关掉后，左侧小入口与「选中即浮出属性框」
 * 都按原规则恢复（模板与 revealPropsForSelection 都按 trialVisible 判断，不另存开关）。
 */
function enterDebugLayout() {
  paletteOpen.value = false
  propsOpen.value = false
}

/**
 * 选中节点 / 连线后浮出属性框；取消选中不主动关（只点框上的关闭才关）。
 *
 * <p>调试框开着期间不浮出：右侧让给调试框，不能因为点了个节点就把属性框叠回来。
 *
 * @param hasSelection 当前是否选中了节点或连线
 */
function revealPropsForSelection(hasSelection: boolean) {
  if (!hasSelection || trialVisible.value) {
    return
  }
  propsOpen.value = true
}

/*
 * 自定义边走线拖拽会拦住 pointerdown，VueFlow 自带的 edge-click 不一定还能触发；
 * 这里把「选中这条边」的入口注入给边组件，保证右侧属性面板与删除按钮照常工作。
 * 只同步选中态，不参与走线计算，也不写 graph_json。
 */
provide(WORKFLOW_EDGE_INTERACTION, {
  selectEdge(edgeId: string) {
    selected.value = { kind: 'edge', id: edgeId }
    revealPropsForSelection(true)
  }
})

const errorCount = computed(() => issues.value.filter((item) => item.level === 'error').length)
const warnCount = computed(() => issues.value.filter((item) => item.level === 'warn').length)
/** 图改过但还没重新校验：提示用户结果已过期 */
const issuesStale = computed(
  () => Boolean(validatedSignature.value) && validatedSignature.value !== currentSignature.value
)

const headerDesc = computed(() => {
  const base = def.value ? `编码 ${def.value.code}` : ''
  const version = def.value?.defaultVersion ? ` · 当前发布 v${def.value.defaultVersion}` : ' · 尚未发布'
  return `${base}${version} · 画布建图 / 草稿 / 校验 / 发布 / 调试`
})

const draftHint = computed(() => {
  if (!def.value) {
    return ''
  }
  return def.value.status === 'HAS_RELEASE'
    ? '调试按当前画布跑，不会发布，也不会改线上默认。工作台「运行」用当前发布版本。'
    : '尚未发布。调试仍按当前画布跑；工作台「运行」要等有默认发布版本后才能用。'
})

const selectedNode = computed<GraphNode<WorkflowFlowNodeData> | null>(() => {
  const sel = selected.value
  if (!sel || sel.kind !== 'node') {
    return null
  }
  const found = vf.nodes.value.find((node) => node.id === sel.id)
  return (found as GraphNode<WorkflowFlowNodeData> | undefined) ?? null
})

const selectedEdge = computed<GraphEdge<WorkflowFlowEdgeData> | null>(() => {
  const sel = selected.value
  if (!sel || sel.kind !== 'edge') {
    return null
  }
  const found = vf.edges.value.find((edge) => edge.id === sel.id)
  return (found as GraphEdge<WorkflowFlowEdgeData> | undefined) ?? null
})

/** 只有带输入输出的节点才展示映射编辑；START 是「定义合同」，不给自己配来源 */
const showMapping = computed(() => {
  const type = selectedNode.value?.data?.nodeType
  return type === 'END' || type === 'AGENT' || type === 'TOOL' || type === 'SUBGRAPH'
})

/** 图内所有 START 节点编码：入参映射把 $.outputs.&lt;start&gt; 归一成 $.input，需要这份名单 */
const startCodes = computed(() =>
  vf.nodes.value
    .map((node) => (node.data || {}) as WorkflowFlowNodeData)
    .filter((data) => String(data.nodeType) === 'START')
    .map((data) => String(data.code ?? ''))
    .filter(Boolean)
)

/**
 * 画布节点 → 映射来源项。
 * <p>下拉主标签只写节点名（人话入口），节点类型与编码放到次要说明里；
 * 不再把「类型 · 名字（code）」当默认展示。
 *
 * @param node 画布节点
 * @return 映射来源项
 */
function toMappingSource(node: Node<WorkflowFlowNodeData>): WorkflowMappingSource {
  const data = (node.data || {}) as WorkflowFlowNodeData
  const code = String(data.code ?? '')
  const name = String(data.name ?? '')
  const outputMapping = (data.config?.outputMapping ?? {}) as Record<string, string>
  return {
    id: node.id,
    code,
    name: name || code,
    label: name || code,
    // 有出参映射时，真实产出键就是这些；没有映射时后端只保留 {text}
    outputKeys: Object.keys(outputMapping)
  }
}

/**
 * 入参映射的「来源节点」候选：当前节点的所有前置节点（多跳祖先）。
 * <p>START 已排除：它的输出等价于运行入参，在编辑器里由固定的「运行入参（$.input）」选项表示，
 * 避免同一份取值出现两个来源选项。
 */
const mappingSources = computed<WorkflowMappingSource[]>(() => {
  const node = selectedNode.value
  if (!node) {
    return []
  }
  return collectAncestors(node.id)
    .filter((item) => String(item.data?.nodeType) !== 'START')
    .map(toMappingSource)
    .filter((item) => item.code)
})

/**
 * 当前 TOOL 节点所绑工具的 inputSchemaJson。
 * <p>工具下拉直接复用列表接口已返回的 inputSchemaJson（ToolItemRow 自带），
 * 不另开接口；未绑定或列表还没到位时为空串，编辑器保持自由添加。
 */
const selectedToolSchema = computed(() => {
  const toolId = Number(selectedNode.value?.data?.config?.toolId)
  if (!toolId) {
    return ''
  }
  const tool = toolOptions.value.find((item) => item.id === toolId)
  return tool?.inputSchemaJson ?? ''
})

/**
 * TOOL 节点必填入参未配来源的提示文案（画布节点卡片用）。
 * <p>只读工具列表里已有的 schema；空 schema / 没绑定工具时返回空串。
 *
 * @param tool    工具项（可空）
 * @param mapping 当前入参映射
 * @return 提示文案；没有缺失返回空串
 */
function toolInputHint(
  tool: ToolItemRow | undefined,
  mapping?: Record<string, string> | null
): string {
  const missing = missingRequiredSlotKeys(parseToolInputSlots(tool?.inputSchemaJson), mapping)
  return missing.length ? `必填入参未配：${missing.join('、')}` : ''
}

/**
 * AGENT 节点必填入参未配来源的提示。
 * <p>Agent 定义没有声明时返回空串（退回默认「用户输入」槽，不增加拦截）；
 * 有声明但必填键没配来源时，画布直接标出来，草稿仍可保存，发布由后端拦。
 *
 * @param agent   绑定的 Agent（列表随身带入参声明；只关心声明字段）
 * @param mapping 当前入参映射
 * @return 提示文案；没有缺失返回空串
 */
function agentInputHint(
  agent: { inputSchema?: unknown } | undefined,
  mapping?: Record<string, string> | null
): string {
  const slots = declaredInputSlots(agent?.inputSchema)
  if (!slots.length) {
    return ''
  }
  const missing = missingRequiredSlotKeys(slots, mapping)
  return missing.length ? `必填入参未配：${missing.join('、')}` : ''
}

/* ============ 子图绑定与声明 ============ */

/** 子图绑定数据源：已发布工作流、版本列表、按版本缓存的入参声明 */
const subgraph = useSubgraphBindings()

/** 当前 AGENT 节点所绑 Agent 的入参声明（列表随身下发；第一页以外走补查缓存） */
const selectedAgentDeclaration = computed(() => {
  const agentId = Number(selectedNode.value?.data?.config?.agentId)
  if (!agentId) {
    return null
  }
  const agent =
    agentOptions.value.find((item) => item.id === agentId) ?? agentLabelCache.get(agentId)
  return agent?.inputSchema ?? null
})

/** 当前 SUBGRAPH 节点绑定的被调工作流 id */
const subWorkflowId = computed(() => {
  const value = Number(selectedNode.value?.data?.config?.subWorkflowId)
  return Number.isFinite(value) && value > 0 ? value : null
})

/** 当前 SUBGRAPH 节点钉死的发布版本 id */
const subReleaseId = computed(() => {
  const value = Number(selectedNode.value?.data?.config?.subReleaseId)
  return Number.isFinite(value) && value > 0 ? value : null
})

/**
 * 当前 SUBGRAPH 节点钉死版本的入参声明。
 * <p>先读缓存；缓存写入后由 declarationVersion 推进触发重算。只认该版本，不读对方草稿。
 */
const selectedSubgraphDeclaration = computed(() => {
  // 显式建立依赖：声明缓存是普通 Map，写入后靠版本号通知重算
  void subgraph.declarationVersion.value
  return subgraph.declarationFor(subWorkflowId.value, subReleaseId.value)
})

/**
 * 已发布工作流下拉项。
 * <p>只用于「子图绑定」这一处候选：排除当前正在编辑的这条工作流（所有版本都不作为候选），
 * 避免新选到自己形成必然的循环调用。图里已经绑了自身的存量数据仍原样保留，
 * 保存 / 校验 / 发布与运行时的环检测照旧拦截，不在这里静默改绑。
 */
const subgraphWorkflowOptions = computed(() =>
  subgraph.workflows.value.filter((item) => item.id !== workflowId.value)
)

/** 当前选中工作流对应的版本下拉项 */
const subgraphReleaseOptions = computed(() => subgraph.releases.value)

/** 子图绑定相关的加载 / 错误态：模板里需要的是普通值，统一在这里解包 */
const subgraphWorkflowsLoading = computed(() => subgraph.workflowsLoading.value)
const subgraphWorkflowsError = computed(() => subgraph.workflowsError.value)
const subgraphReleasesLoading = computed(() => subgraph.releasesLoading.value)
const subgraphReleasesError = computed(() => subgraph.releasesError.value)
const subgraphDeclarationLoading = computed(() => subgraph.declarationsLoading.value)

/** 当前节点给映射编辑器的入参声明：只有 AGENT / SUBGRAPH 有 */
const selectedInputDeclaration = computed(() => {
  const type = String(selectedNode.value?.data?.nodeType ?? '')
  if (type === 'AGENT') {
    return selectedAgentDeclaration.value
  }
  if (type === 'SUBGRAPH') {
    return selectedSubgraphDeclaration.value
  }
  return null
})

/** 只有子图需要「声明读取中」文案：Agent 声明随列表同步下发，不存在等待 */
const selectedDeclarationLoading = computed(
  () =>
    String(selectedNode.value?.data?.nodeType ?? '') === 'SUBGRAPH' &&
    subgraphDeclarationLoading.value
)

/**
 * 子图节点的必填入参提示。
 * <p>声明来自「钉死版本」；没缓存时返回空串，等预热完成后再由 syncSubgraphLabels 补上。
 *
 * @param data 画布节点 data
 * @return 提示文案；没有声明 / 没有缺失返回空串
 */
function subgraphInputHint(data: WorkflowFlowNodeData): string {
  const workflowId = Number(data.config?.subWorkflowId)
  const releaseId = Number(data.config?.subReleaseId)
  if (!workflowId || !releaseId) {
    return ''
  }
  const slots = declaredInputSlots(subgraph.declarationFor(workflowId, releaseId))
  if (!slots.length) {
    return ''
  }
  const missing = missingRequiredSlotKeys(slots, data.config?.inputMapping)
  return missing.length ? `必填入参未配：${missing.join('、')}` : ''
}

/**
 * 回填所有 SUBGRAPH 节点的展示名、版本号与必填提示。
 * <p>工作流名称来自已发布列表（全局一次），版本号来自当前选中工作流的版本列表；
 * 其它节点的版本号拿不到就留空，不猜。
 */
function syncSubgraphLabels() {
  const byId = new Map(subgraph.workflows.value.map((item) => [item.id, item]))
  const releaseById = new Map(subgraph.releases.value.map((item) => [item.id, item]))
  vf.nodes.value.forEach((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    if (String(data.nodeType) !== 'SUBGRAPH') {
      return
    }
    const workflowId = Number(data.config?.subWorkflowId)
    const releaseId = Number(data.config?.subReleaseId)
    const workflow = byId.get(workflowId)
    const release = releaseById.get(releaseId)
    const subWorkflowName = workflow?.name ?? data.subWorkflowName ?? ''
    // 版本号优先取当前工作流的版本列表，其次取声明接口顺手缓存的版本号
    const subReleaseVersion =
      release?.version ?? subgraph.versionFor(workflowId, releaseId) ?? data.subReleaseVersion ?? null
    const missingInputHint = subgraphInputHint(data)
    if (
      data.subWorkflowName === subWorkflowName &&
      (data.subReleaseVersion ?? null) === subReleaseVersion &&
      (data.missingInputHint ?? '') === missingInputHint
    ) {
      return
    }
    node.data = { ...data, subWorkflowName, subReleaseVersion, missingInputHint }
  })
}

/** 画布上所有已绑定的子图节点：用于批量预热声明缓存 */
function collectSubgraphRefs(): Array<{ workflowId: number; releaseId: number }> {
  return vf.nodes.value
    .map((node) => (node.data || {}) as WorkflowFlowNodeData)
    .filter((data) => String(data.nodeType) === 'SUBGRAPH')
    .map((data) => ({
      workflowId: Number(data.config?.subWorkflowId) || 0,
      releaseId: Number(data.config?.subReleaseId) || 0
    }))
    .filter((item) => item.workflowId > 0 && item.releaseId > 0)
}

/**
 * 铺完图 / 打开子图属性后预热：先拿已发布工作流名称，再批量取各节点钉死版本的声明。
 * <p>整张图没有 SUBGRAPH 节点时直接返回：不为无关页面多发一次列表请求。
 * <p>失败只影响提示与出槽，不阻断画布编辑。
 */
async function primeSubgraphBindings() {
  const hasSubgraph = vf.nodes.value.some(
    (node) => String((node.data as WorkflowFlowNodeData | undefined)?.nodeType) === 'SUBGRAPH'
  )
  if (!hasSubgraph) {
    return
  }
  await subgraph.ensureWorkflows()
  syncSubgraphLabels()
  const refs = collectSubgraphRefs()
  if (!refs.length) {
    return
  }
  await subgraph.ensureDeclarations(refs)
  syncSubgraphLabels()
}

/**
 * 选中子图节点后补齐它自己的版本列表与声明。
 * <p>版本列表按工作流懒加载：一张图里通常只钉一两个工作流，不必全量拉。
 */
async function hydrateSelectedSubgraph() {
  await subgraph.ensureWorkflows()
  const data = selectedNode.value?.data
  if (!data || String(data.nodeType) !== 'SUBGRAPH') {
    return
  }
  const workflowId = Number(data.config?.subWorkflowId) || 0
  await subgraph.loadReleases(workflowId || null)
  const fresh = selectedNode.value?.data
  const releaseId = Number(fresh?.config?.subReleaseId) || 0
  if (workflowId && releaseId) {
    await subgraph.ensureDeclaration(workflowId, releaseId)
  }
  syncSubgraphLabels()
}

/**
 * START 节点的入参声明写回 config。
 * <p>只在 START 上生效：其它节点即便带了 inputSchema 字段也不在这里改。
 *
 * @param value 归一后的声明；null 表示没有声明（键留空，不存空对象）
 */
function setInputDeclaration(value: unknown) {
  const node = selectedNode.value
  if (!node || String(node.data.nodeType) !== 'START') {
    return
  }
  node.data = {
    ...node.data,
    config: { ...node.data.config, inputSchema: value ?? undefined }
  }
}

/**
 * 换绑被调工作流。
 * <p>换绑后版本 id 必须重置：旧版本可能属于上一个工作流，留着会被后端判「版本与工作流不匹配」。
 * 优先自动钉住该工作流的默认发布版本，编排者仍可在下拉里换成历史版本。
 *
 * @param value 下拉选中的 workflowId；清空时为 undefined / 空串
 */
function setSubgraphWorkflow(value: number | string | undefined) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  const workflowId = value === undefined || value === null || value === '' ? undefined : Number(value)
  const workflow = subgraphWorkflowOptions.value.find((item) => item.id === workflowId)
  const releaseId = workflow?.defaultReleaseId ?? undefined
  node.data = {
    ...node.data,
    config: { ...node.data.config, subWorkflowId: workflowId, subReleaseId: releaseId },
    subWorkflowName: workflow?.name ?? '',
    subReleaseVersion: workflow?.defaultVersion ?? null,
    // 换绑后先清掉旧提示，等声明加载完成再按新版本重算
    missingInputHint: ''
  }
  void hydrateSelectedSubgraph()
}

/**
 * 改子图钉死的发布版本。
 * <p>同名键保留已配来源：只换「定义」，不洗掉用户配好的映射；不再属于新声明的旧键
 * 由映射编辑器标成「未在定义中」并允许删除。
 *
 * @param value 下拉选中的 releaseId；清空时为 undefined / 空串
 */
function setSubgraphRelease(value: number | string | undefined) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  const releaseId = value === undefined || value === null || value === '' ? undefined : Number(value)
  const release = subgraphReleaseOptions.value.find((item) => item.id === releaseId)
  node.data = {
    ...node.data,
    config: { ...node.data.config, subReleaseId: releaseId },
    subReleaseVersion: release?.version ?? null,
    missingInputHint: ''
  }
  const workflowId = Number(node.data.config?.subWorkflowId)
  if (workflowId && releaseId) {
    void subgraph.ensureDeclaration(workflowId, releaseId).then(() => syncSubgraphLabels())
  }
}

/** 声明缓存更新（子图预热 / 换版本）后重算画布必填提示 */
watch(
  () => subgraph.declarationVersion.value,
  () => syncSubgraphLabels()
)

/** 选中 / 切换到子图节点时补齐版本列表与声明 */
watch(
  [() => selectedNode.value?.id, () => String(selectedNode.value?.data?.nodeType ?? '')],
  () => {
    const data = selectedNode.value?.data
    if (!data || String(data.nodeType) !== 'SUBGRAPH') {
      return
    }
    void hydrateSelectedSubgraph()
  }
)

/**
 * 循环打回来源：指向当前循环节点的 LOOP_BACK 边起点。
 * <p>同一来源有多条回边时只保留一项；边被删除 / 改类型后候选自然减少，
 * 不按节点类型猜来源。
 */
const loopBackSources = computed<WorkflowMappingSource[]>(() => {
  const node = selectedNode.value
  if (!node || String(node.data?.nodeType) !== 'LOOP') {
    return []
  }
  const seen = new Set<string>()
  const result: WorkflowMappingSource[] = []
  vf.edges.value.forEach((edge) => {
    const data = (edge.data || {}) as WorkflowFlowEdgeData
    if (String(data.edgeType) !== 'LOOP_BACK' || String(edge.target ?? '') !== node.id) {
      return
    }
    const sourceId = String(edge.source ?? '')
    if (!sourceId || seen.has(sourceId)) {
      return
    }
    const sourceNode = vf.nodes.value.find((item) => item.id === sourceId)
    if (!sourceNode) {
      return
    }
    const source = toMappingSource(sourceNode)
    if (!source.code) {
      return
    }
    seen.add(sourceId)
    result.push(source)
  })
  return result
})

/** 已自动预填的循环节点：nodeId → 预填路径；用户改过或来源变多时失效 */
const loopAutoPrefill = new Map<string, string>()

/**
 * 打回边只有一条时预填来源节点；多条留空，交给下拉高亮让用户拍板。
 * <p>只填空值，用户已配置 / 手动改过的值一律不覆盖。
 * <p>自动预填后再加一条回边：若当前值仍是自动预填的那条，撤回到空，避免保留“猜”的来源；
 * 用户自己改过的值不撤。
 * <p>监听节点切换与回边集合变化：先连边再选节点、或反过来，都能触发。
 */
watch(
  [
    () => selectedNode.value?.id,
    () => String(selectedNode.value?.data?.nodeType ?? ''),
    () => loopBackSources.value.map((item) => item.code).join('|')
  ],
  () => {
    const node = selectedNode.value
    if (!node || String(node.data?.nodeType) !== 'LOOP') {
      return
    }
    const id = String(node.id)
    const current = String(node.data.config?.feedbackPath ?? '').trim()
    const auto = loopAutoPrefill.get(id)
    const sources = loopBackSources.value

    // 用户改过自动预填值：交还控制权，后续不再自动撤 / 自动改
    if (auto !== undefined && current !== auto) {
      loopAutoPrefill.delete(id)
    }

    // 来源变多且当前仍是自动预填值：撤回到空，让用户自己选意见来源
    if (auto !== undefined && current === auto && sources.length > 1) {
      loopAutoPrefill.delete(id)
      setLoopField('feedbackPath', '')
      return
    }

    // 自动预填值跟随唯一来源更新（旧回边被换掉时不留一条指向已删节点的路径）
    if (auto !== undefined && current === auto && sources.length === 1) {
      const next = singleBackSourcePrefill('', sources)
      if (next && next !== current) {
        loopAutoPrefill.set(id, next)
        setLoopField('feedbackPath', next)
      }
      return
    }

    // 已有配置（含用户手选）不覆盖
    if (current) {
      return
    }

    // 只有一条回边才预填；多条 / 没有时留空，不猜
    const prefill = singleBackSourcePrefill('', sources)
    if (!prefill) {
      return
    }
    loopAutoPrefill.set(id, prefill)
    setLoopField('feedbackPath', prefill)
  },
  { immediate: true }
)

/**
 * 反向广度优先收集前置节点（多跳祖先）。
 * <p>不区分边类型：ERROR 分支同样会产出输出，理应可被下游引用。
 * <p>已访问集合同时充当环保护：画布允许画出环，成环由校验器拦截，这里不能死循环。
 *
 * @param nodeId 起点节点 id（结果不含自身）
 * @return 祖先节点，按距离由近到远
 */
function collectAncestors(nodeId: string): Node<WorkflowFlowNodeData>[] {
  // 反邻接表：目标节点 → 直接前驱节点 id 列表
  const incoming = new Map<string, string[]>()
  vf.edges.value.forEach((edge) => {
    const source = String(edge.source ?? '')
    const target = String(edge.target ?? '')
    if (!source || !target) {
      return
    }
    const list = incoming.get(target)
    if (list) {
      list.push(source)
    } else {
      incoming.set(target, [source])
    }
  })
  const visited = new Set<string>([nodeId])
  const queue: string[] = [nodeId]
  const result: Node<WorkflowFlowNodeData>[] = []
  while (queue.length) {
    const current = queue.shift() as string
    for (const source of incoming.get(current) ?? []) {
      // 已访问直接跳过：既去重也防环
      if (visited.has(source)) {
        continue
      }
      visited.add(source)
      queue.push(source)
      const found = vf.nodes.value.find((item) => item.id === source)
      if (found) {
        result.push(found as Node<WorkflowFlowNodeData>)
      }
    }
  }
  return result
}

/** HUMAN 处理人规则解析结果；非法时模板给红字提示（发布会被后端拒） */
const assigneeParsed = computed(() => parseAssigneeRule(selectedNode.value?.data?.config?.assigneeRule))

/**
 * 从规则原文判断类型（只看前缀，不看值是否合法）。
 * <p>为什么不用 parseAssigneeRule().kind：用户刚切到「按角色」时值是空的，
 * 解析会报 problem，若按 problem 回落 EMPTY，下拉会立刻弹回「不限」。
 */
function ruleKindOf(raw?: string | null): string {
  const text = String(raw ?? '').trim()
  const upper = text.toUpperCase()
  if (upper.startsWith('ROLE:')) {
    return 'ROLE'
  }
  if (upper.startsWith('USER:')) {
    return 'USER'
  }
  return 'EMPTY'
}

/**
 * 处理人规则类型。切换类型时按新语法重建规则串：
 * EMPTY→空串、ROLE→`ROLE:`、USER→`USER:`，旧值不跨语法复用（避免拼出非法串）。
 */
const assigneeKind = computed({
  get: () => ruleKindOf(selectedNode.value?.data?.config?.assigneeRule),
  set: (kind: string) => {
    const node = selectedNode.value
    if (!node) {
      return
    }
    const rule = kind === 'ROLE' ? 'ROLE:' : kind === 'USER' ? 'USER:' : ''
    node.data = { ...node.data, config: { ...node.data.config, assigneeRule: rule } }
  }
})

/** 处理人规则值：ROLE 取角色码，USER 取逗号分隔的用户 id（保留用户原始输入） */
const assigneeValue = computed({
  get: () => {
    const text = String(selectedNode.value?.data?.config?.assigneeRule ?? '').trim()
    return ruleKindOf(text) === 'EMPTY' ? '' : text.slice(5)
  },
  set: (value: string) => {
    const node = selectedNode.value
    if (!node) {
      return
    }
    const kind = assigneeKind.value
    if (kind === 'EMPTY') {
      return
    }
    const rule = `${kind}:${String(value ?? '').trim()}`
    node.data = { ...node.data, config: { ...node.data.config, assigneeRule: rule } }
  }
})

/** 历史脏数据可能绑了 ROUTER：明确报错，避免用户以为能发布 */
const routerBindingHint = computed(() => {  const agentId = selectedNode.value?.data?.config?.agentId
  if (!agentId) {
    return ''
  }
  const agent = agentOptions.value.find((item) => item.id === agentId)
  if (!agent) {
    return '该 Agent 不在可绑定列表中（可能已停用或为 ROUTER），发布会被拒绝'
  }
  return ''
})

/** 边标签输入框：空串表示「按类型自动显示」 */
const edgeLabelInput = computed({
  get: () => selectedEdge.value?.data?.label ?? '',
  set: (value: string) => setEdgeLabel(value)
})

/** 当前画布签名（不含 viewport，避免平移也判脏） */
const currentSignature = computed(() => graphSignature(buildGraph()))

/** 相对最近一次载入/保存是否有改动 */
const dirty = computed(() => currentSignature.value !== baseline.value)

/**
 * 落库前的 config 兜底：入参映射不写空键。
 * <p>空值等价于「没配」，但 AGENT 一旦存了空串键，运行时会拿到空文本而不再走
 * input.message → query → text → content 的兜底，因此保存前统一剔掉。
 *
 * @param config 画布节点 config
 * @return 需要清洗时返回新对象，否则原样返回（避免无意义 diff）
 */
function sanitizeNodeConfig(config: WorkflowNodeConfig): WorkflowNodeConfig {
  const mapping = config.inputMapping
  if (!mapping || typeof mapping !== 'object') {
    return config
  }
  const cleaned = dropEmptyInputMapping(mapping)
  if (sameMapping(cleaned, mapping)) {
    return config
  }
  return { ...config, inputMapping: cleaned }
}

/**
 * 图 → 落库结构。
 *
 * @return 当前画布对应的 graph_json（含 viewport）
 */
function buildGraph(): WorkflowGraph {
  const view = getViewport()
  const nodes: WorkflowGraphNode[] = vf.nodes.value.map((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    return {
      id: node.id,
      code: String(data.code ?? ''),
      type: String(data.nodeType ?? ''),
      name: String(data.name ?? ''),
      config: sanitizeNodeConfig((data.config ?? {}) as WorkflowNodeConfig),
      ui: {
        x: Math.round(node.position?.x ?? 0),
        y: Math.round(node.position?.y ?? 0)
      }
    }
  })
  const edges: WorkflowGraphEdge[] = vf.edges.value.map((edge) => {
    const data = (edge.data || {}) as WorkflowFlowEdgeData
    return {
      id: edge.id,
      source: String(edge.source ?? ''),
      target: String(edge.target ?? ''),
      type: String(data.edgeType ?? 'NORMAL'),
      label: data.label ?? null,
      config: (data.config ?? {}) as Record<string, unknown>
    }
  })
  const limits: WorkflowGraphLimits = {}
  // 只有真的填了才写：空值不落 limits 键，后端按「跟平台硬顶走」处理
  const runMinutes = runMinutesValue.value
  const nodeMinutes = nodeMinutesValue.value
  if (runMinutes !== null && Number.isFinite(runMinutes)) {
    limits.runMinutes = runMinutes
  }
  if (nodeMinutes !== null && Number.isFinite(nodeMinutes)) {
    limits.nodeMinutes = nodeMinutes
  }
  return {
    nodes,
    edges,
    viewport: {
      x: Math.round(view.x),
      y: Math.round(view.y),
      zoom: Number(view.zoom.toFixed(3))
    },
    ...(Object.keys(limits).length ? { limits } : {})
  }
}

/**
 * 图签名：比 nodes / edges / 运行时限，忽略 viewport 与展示字段。
 *
 * <p>时限必须进签名：只改两个上限不动画布也算「有未保存修改」，
 * 否则用户改完时限直接点发布，会被「不脏就不存草稿」的路径静默丢掉。
 *
 * @param graph 图
 */
function graphSignature(graph: WorkflowGraph): string {
  return JSON.stringify({
    nodes: graph.nodes,
    edges: graph.edges,
    limits: graph.limits ?? null
  })
}

/** 落库结构 → 画布节点 */
function toFlowNode(item: WorkflowGraphNode): Node<WorkflowFlowNodeData> {
  return {
    id: String(item.id),
    type: 'workflow',
    position: { x: Number(item.ui?.x ?? 0), y: Number(item.ui?.y ?? 0) },
    data: {
      code: String(item.code ?? ''),
      name: String(item.name ?? item.code ?? ''),
      nodeType: String(item.type ?? ''),
      config: (item.config ?? {}) as WorkflowNodeConfig
    }
  }
}

/** 落库结构 → 画布连线 */
function toFlowEdge(item: WorkflowGraphEdge): Edge<WorkflowFlowEdgeData> {
  const edgeType = normalizeEdgeType(item.type)
  const label = item.label ?? null
  return {
    id: String(item.id),
    source: String(item.source),
    target: String(item.target),
    // 走线用自定义边渲染；落库的边类型仍取 data.edgeType，两者互不影响
    type: 'workflow',
    label: labelText(edgeType, label),
    markerEnd: MarkerType.ArrowClosed,
    data: {
      edgeType,
      label,
      condition: (item.config ?? {}).condition,
      config: (item.config ?? {}) as Record<string, unknown>
    }
  }
}

/** 未知边类型回落 NORMAL（后端仍会报 EDGE_TYPE_UNKNOWN 兜底） */
function normalizeEdgeType(raw: string | undefined): WorkflowEdgeType {
  const text = String(raw || '').toUpperCase()
  if (
    text === 'COND' ||
    text === 'DEFAULT' ||
    text === 'ERROR' ||
    text === 'LOOP_BODY' ||
    text === 'LOOP_BACK' ||
    text === 'LOOP_EXIT'
  ) {
    return text
  }
  return 'NORMAL'
}

/** 边展示文案：自定义标签优先，否则按类型给默认中文 */
function labelText(edgeType: string, label: string | null): string {
  if (label && label.trim()) {
    return label.trim()
  }
  if (edgeType === 'COND') {
    return '条件'
  }
  if (edgeType === 'DEFAULT') {
    return '默认'
  }
  if (edgeType === 'ERROR') {
    return '异常'
  }
  if (edgeType === 'LOOP_BODY') {
    return '进入循环体'
  }
  if (edgeType === 'LOOP_BACK') {
    return '回到循环'
  }
  if (edgeType === 'LOOP_EXIT') {
    return '次数用尽'
  }
  return ''
}

/** 属性面板里的类型中文名 */
function nodeTypeLabel(type: string): string {
  return NODE_TYPE_LABEL[type] || type || '未知'
}

/** 连线类型中文名 */
function edgeTypeLabel(type: string): string {
  return EDGE_TYPE_LABEL[type] || type || '未知'
}

/**
 * 切换专注模式。
 * <p>专注只改变壳层（隐藏全局侧导航、画布吃满高度），左右面板仍是悬浮框，
 * 进入前后保持用户当前的开合状态，不强制收起。
 */
function toggleFocusMode() {
  if (focusMode.value) {
    exitFocusMode()
    return
  }
  focusMode.value = true
  setWorkflowFocus(true)
}

/** 退出专注模式：解除壳层隐藏；悬浮面板状态原样保留 */
function exitFocusMode() {
  focusMode.value = false
  setWorkflowFocus(false)
}

/**
 * 全局 ESC：专注模式下退出。
 * <p>弹窗 / 浮层打开时不抢占 ESC，避免一次按键同时关弹窗和退专注。
 */
function onFocusKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !focusMode.value) {
    return
  }
  if (document.querySelector('.el-overlay')) {
    return
  }
  exitFocusMode()
}

/** 悬浮工具栏放大；以画布中心为锚点。用户主动改视野，同时暂停调试跟随 */
function zoomInCanvas() {
  pauseFollow()
  void zoomIn()
}

/** 悬浮工具栏缩小；以画布中心为锚点。用户主动改视野，同时暂停调试跟随 */
function zoomOutCanvas() {
  pauseFollow()
  void zoomOut()
}

/** 悬浮工具栏自适应：与首屏载入使用同一套 padding / maxZoom。同样视为用户改过视野 */
function fitCanvas() {
  pauseFollow()
  void fitView({ padding: 0.2, maxZoom: 1.1, duration: 250 }).catch(() => false)
}

/** 画布容器。高度落到真实布局后再适配，避免按 0 高把节点钉在顶部 */
const canvasHost = ref<HTMLElement | null>(null)

/**
 * 右侧调试框的实际遮挡矩形。所属域：workflow 的前端画布层。
 *
 * <p>不写死 680px 也不写死左右留白：调试框宽度、贴边距离都由它自己的样式决定，
 * 这里只取真实 DOM 矩形，宽度变化后跟随自动跟着变。
 *
 * @return 遮挡矩形（相对窗口）；框没打开时为空数组
 */
function debugPanelRects() {
  const panel = document.querySelector('.wf-debug-panel')
  if (!panel) {
    return []
  }
  const rect = panel.getBoundingClientRect()
  return [{ left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom }]
}

/**
 * 调试跟随：接到运行详情后，把当前执行节点对到左侧可见区中心。
 *
 * <p>只平移、不改缩放，写的是 VueFlow 视口而不是节点坐标；视口不参与草稿签名，不会置脏。
 */
const {
  paused: followPaused,
  hasTarget: followHasTarget,
  following: followFollowing,
  onDetail: onFollowDetail,
  pause: pauseFollow,
  resume: resumeFollow,
  reposition: repositionFollow,
  stop: stopFollow
} = useWorkflowRunFollow({
  isOpen: () => trialVisible.value,
  getCanvasHost: () => canvasHost.value,
  getObstructionRects: debugPanelRects,
  // 与高亮同一份画布节点：按业务 code 找，不依赖 VueFlow 内部 id
  findNodeByCode: (code) =>
    vf.nodes.value.find(
      (node) => String((node.data as WorkflowFlowNodeData | undefined)?.code ?? '') === code
    ),
  getViewport: () => getViewport(),
  setViewport: (transform, followOptions) => setViewport(transform, followOptions)
})

/** 正在程序化适配视口，这次 move-end 不算用户拖拽 */
let centering = false
/** 已经在等高度并适配，避免 nodes-initialized 和载入各打一次叠在一起 */
let placeQueued = false
/** 用户拖动或缩放过后，不再自动把节点拽回中部 */
let userMovedCanvas = false

/**
 * 用户拖动画布或改了缩放。自动居中只服务默认视野；
 * 调试跟随也在此暂停，本次调试内不再自动移动，等用户点「继续跟随」。
 */
function onCanvasMoveEnd() {
  if (!centering) {
    userMovedCanvas = true
    pauseFollow()
  }
}

/**
 * 节点量完尺寸后补一次居中。载入时若先适配过，这里用真实节点盒再放一次。
 * <p>调试跟随中让位：按当前目标重新对准，不能被载入期的 fitView 抢走缩放。
 */
function onNodesInitialized() {
  if (followFollowing.value) {
    repositionFollow()
    return
  }
  if (userMovedCanvas) {
    return
  }
  void placeNodesInView()
}

/**
 * 把当前节点放进可视区域中部。
 * 等容器有真实高度再 fitView：高度还是 0 时适配会失败，节点就停在坐标原点附近，底下空一大片。
 */
async function placeNodesInView() {
  if (placeQueued) {
    return
  }
  placeQueued = true
  centering = true
  try {
    await nextTick()
    const host = canvasHost.value
    for (let i = 0; i < 10; i += 1) {
      if (host && host.clientHeight > 160) {
        break
      }
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    }
    await fitView({ padding: 0.22, maxZoom: 1.1, duration: 0 }).catch(() => false)
  } finally {
    await nextTick()
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    centering = false
    placeQueued = false
  }
}

/**
 * 专注模式改变画布高度。
 * <p>跟随进行中：按当前目标重新对准（保持用户缩放），不走 fitView；
 * 未跟随时保持原来的默认视野行为，用户还没拖过才重新放到中部。
 */
watch(focusMode, () => {
  if (followFollowing.value) {
    repositionFollow()
    return
  }
  if (userMovedCanvas) {
    return
  }
  void placeNodesInView()
})

/**
 * 读取定义与草稿并铺到画布。
 *
 * @return 无
 */
async function loadAll() {
  const id = workflowId.value
  if (!id) {
    loadError.value = '缺少工作流 id，请从列表进入'
    return
  }
  loading.value = true
  loadError.value = ''
  canvasError.value = ''
  // 重新载入视为新的默认视野，允许再次把节点放到可视区域中部
  userMovedCanvas = false
  try {
    const [defResp, draftResp] = await Promise.all([getWorkflowDef(id), getWorkflowDraft(id)])
    def.value = defResp.data.data
    const graph = draftResp.data.data.graph || { nodes: [], edges: [] }
    platformMaxMinutes.value = draftResp.data.data.platformMaxMinutes || 60
    platformMaxLoopIterations.value = draftResp.data.data.platformMaxLoopIterations || 20
    paintGraph(graph, draftResp.data.data.updateTime || '')
    await nextTick()
    // 不回放旧 viewport：按当前画布高度把节点放到可视区域中部
    await placeNodesInView()
  } catch {
    loadError.value = '工作流加载失败：请确认存在、属于当前租户且草稿完好'
  } finally {
    loading.value = false
  }
}

/**
 * 把 run 详情中的节点状态回填到画布 data.runStatus，用于高亮已走 / 失败 / 等待人工的节点。
 * <p>run 详情里的节点按 code 对应；未出现在 run 中的节点清空状态，
 * 这样切换不同 run 不会残留上一次的颜色。
 *
 * @param detail 最新 run 详情；null 表示清空高亮
 */
function onRunChange(detail: WorkflowRunDetail | null) {
  const statusByCode = new Map<string, string>()
  if (detail) {
    detail.nodeRuns.forEach((node) => statusByCode.set(node.nodeCode, node.status))
  }
  vf.nodes.value.forEach((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    const next = statusByCode.get(String(data.code ?? ''))
    node.data = { ...data, runStatus: next }
  })
  // 高亮与跟随吃同一份 nodeRuns；跟随只平移视口，不改节点坐标
  onFollowDetail(detail)
}

/**
 * 调试框关掉后，把这次 / 这条记录画在节点上的运行态从画布抹掉，回到普通编辑的样子。
 * <p>不另存一份运行态：再次打开调试框、再点记录时由调试框按 run 详情重新回填。
 */
watch(trialVisible, (open) => {
  if (!open) {
    onRunChange(null)
    // 关调试只停跟随、清键与暂停态；视口停在用户最后的位置，不再跳回打开前
    stopFollow()
  }
})

/** 只刷新定义元数据（发布后状态/默认版本） */
async function loadDef() {
  const id = workflowId.value
  if (!id) {
    return
  }
  try {
    const { data } = await getWorkflowDef(id)
    def.value = data.data
  } catch {
    // 元数据刷新失败不覆盖画布，保持当前编辑态
  }
}

/** Agent 下拉：排除 ROUTER（W2），失败给中文错误且禁用绑定 */
async function loadAgentOptions() {
  try {
    const { data } = await pageAgents({ current: 1, size: 100 })
    agentOptions.value = (data.data?.records || []).filter((item) => item.runMode !== 'ROUTER')
  } catch {
    agentOptions.value = []
    agentLoadError.value = 'Agent 列表加载失败，暂时无法绑定 Agent；请刷新页面重试'
  }
  // 标记下拉已就绪：此后回填节点标题时，第一页没覆盖到的 id 才会去补查详情
  agentOptionsReady = true
  // 选项到位 / 失败后统一回填节点标题：失败时清空名称，回退显示节点「显示名」
  syncAgentLabels()
}

/** 工具下拉：含停用项，由文案标注 */
async function loadToolOptions() {
  try {
    toolOptions.value = await listAllItems()
  } catch {
    toolOptions.value = []
    toolLoadError.value = '工具列表加载失败，暂时无法绑定工具；请刷新页面重试'
  }
  // 选项到位 / 失败后统一回填节点标题：失败时清空名称，回退显示节点「显示名」
  syncToolLabels()
}

/** 保存草稿；silent=true 用于校验/发布前的自动保存 */
async function onSaveDraft(silent = false): Promise<boolean> {
  const id = workflowId.value
  if (!id) {
    return false
  }
  if (!canEdit.value) {
    ElMessage.warning('没有编辑权限，无法保存草稿')
    return false
  }
  // 时限本地先拦：后端也会拦，但先给用户一句直接的中文，省一次往返
  if (limitsError.value) {
    ElMessage.error(limitsError.value)
    return false
  }
  saving.value = true
  try {
    const graph = buildGraph()
    const { data } = await saveWorkflowDraft(id, graph, draftUpdateTime.value || undefined)
    draftUpdateTime.value = data.data.updateTime || ''
    baseline.value = graphSignature(graph)
    if (!silent) {
      ElMessage.success('草稿已保存')
    }
    return true
  } catch (err) {
    // 拦截器已弹出后端中文文案；409 表示他人先改过，明确引导重新加载
    const status = (err as { response?: { status?: number } })?.response?.status
    if (status === 409) {
      canvasError.value = '草稿已被他人修改（409）：请先保留你的改动，再点画布顶部「重新加载」拿最新草稿'
    }
    return false
  } finally {
    saving.value = false
  }
}

/**
 * 把一份图铺到画布，并把它当成新的已保存基线。
 * 载入草稿和切换历史版本都走这里，避免两套铺图。
 *
 * @param graph 要显示的图
 * @param updateTime 草稿更新时间；切换后用来做下次保存的乐观锁
 */
function paintGraph(graph: WorkflowGraph, updateTime: string) {
  vf.setNodes((graph.nodes || []).map(toFlowNode))
  vf.setEdges((graph.edges || []).map(toFlowEdge))
  // 运行时限：空表示跟平台走；平台上限由后端折算成分钟下发，页面不出现毫秒与键名
  runMinutesText.value = graph.limits?.runMinutes ? String(graph.limits.runMinutes) : ''
  nodeMinutesText.value = graph.limits?.nodeMinutes ? String(graph.limits.nodeMinutes) : ''
  // Agent / 工具选项可能已先加载完成：铺完节点立刻补一次名称，避免首屏闪「Agent #30」
  syncAgentLabels()
  syncToolLabels()
  // 子图名称 / 钉死版本与必填提示：异步预热，失败只影响提示，不阻断画布
  void primeSubgraphBindings()
  draftUpdateTime.value = updateTime
  selected.value = null
  issues.value = []
  lastResult.value = null
  validatedSignature.value = ''
  // 换了一份图（载入草稿 / 切换版本 / 重新加载）后旧校验结果不再对应，收起结果框
  validationOpen.value = false
  baseline.value = graphSignature(buildGraph())
}

/**
 * 点调试：先静默跑一遍与手动「校验」相同的校验。
 * <p>不通过就拦住，只打开右侧校验结果框（完整列表），不打开调试框；
 * 通过才收起编辑侧两个悬浮框并浮出调试框。
 */
async function onDebug() {
  const passed = await validateBeforeAction()
  if (!passed) {
    // 不通过：调试框保持关闭，结果框已由预检打开，界面停在看问题
    return
  }
  // 预检通过：收起上次留下的校验框，避免它盖住即将浮出的调试框
  validationOpen.value = false
  enterDebugLayout()
  trialVisible.value = true
}

/**
 * 调试 / 发布前的静默校验：与手动点「校验」走同一路径（必要时先落草稿，再校验草稿）。
 * <p>不通过时一定打开右侧校验结果框，让用户看到完整问题列表；通过时不额外打扰。
 *
 * @return 校验通过时为 true
 */
async function validateBeforeAction(): Promise<boolean> {
  const result = await runDraftValidation()
  if (!result) {
    return false
  }
  applyValidation(result, { openPanel: !result.passed })
  if (!result.passed) {
    // 兜底：不通过时结果框必须真的浮出来，不能只在后台更新计数或靠一闪而过的提示
    validationOpen.value = true
  }
  return result.passed
}

/**
 * 按库里的草稿跑一次校验。画布有未保存修改且可编辑时，先静默落草稿。
 * <p>手动校验与调试 / 发布前的静默校验都走这里，保证判定口径完全一致。
 *
 * @return 后端校验结果；没保存成功或请求失败时为 null
 */
async function runDraftValidation(): Promise<WorkflowValidateResp | null> {
  const id = workflowId.value
  if (!id) {
    return null
  }
  if (canEdit.value && dirty.value) {
    const saved = await onSaveDraft(true)
    if (!saved) {
      return null
    }
  }
  validating.value = true
  try {
    const { data } = await validateWorkflow(id)
    return data.data
  } catch {
    return null
  } finally {
    validating.value = false
  }
}

/**
 * 校验画布此刻的图，不先保存。
 *
 * <p>默认「通过就不浮出结果框」：调试开跑前的预检是静默的，只有不通过才把完整问题列表浮出来。
 * 只有手点「校验」那种需要用户立刻看结果的场景才传 {@code openPanel: true}。
 *
 * @param options.openPanel true 强制浮出结果框；不传按校验结果决定（不通过才浮）
 * @return 没有 error 时为 true
 */
async function validateCurrentCanvas(options: { openPanel?: boolean } = {}): Promise<boolean> {
  const id = workflowId.value
  if (!id) {
    return false
  }
  validating.value = true
  try {
    const { data } = await validateWorkflowGraph(id, buildGraph())
    const result = data.data
    // 有提示但没错误也算通过：这是预检，不能因为 warn 就把结果框盖到调试框上
    applyValidation(result, { openPanel: options.openPanel ?? !result.passed })
    if (!result.passed) {
      ElMessage.error(`校验未通过：${result.errors.length} 个错误`)
      return false
    }
    return true
  } catch {
    return false
  } finally {
    validating.value = false
  }
}

/**
 * 发送第一句之前：画布若又改过，再校一次；通过后只把图落到草稿。
 *
 * <p>这次是静默预检：通过时（含只有提示、没有错误）不浮出校验结果框，
 * 免得它盖在已经打开的调试框上；只有不通过才把问题列表浮出来。
 *
 * @return 可以按这份草稿开跑时为 true
 */
async function prepareDebugStart(): Promise<boolean> {
  const passed = await validateCurrentCanvas()
  if (!passed) {
    return false
  }
  // 静默预检通过：收起可能还开着的校验结果框，别让它盖住正在跑的调试框
  validationOpen.value = false
  if (!dirty.value) {
    return true
  }
  if (!canEdit.value) {
    ElMessage.warning('没有编辑权限，无法把当前画布存成草稿')
    return false
  }
  return onSaveDraft(true)
}

/**
 * 切换成功：画布恢复成该版本，默认发布指针也已在服务端指回去。
 *
 * @param result 切换接口返回的版本和图
 */
function onReleaseActivated(result: WorkflowReleaseActivateResult) {
  userMovedCanvas = false
  paintGraph(result.graph || { nodes: [], edges: [] }, result.draftUpdateTime || '')
  if (def.value) {
    def.value = {
      ...def.value,
      status: 'HAS_RELEASE',
      defaultReleaseId: result.id,
      defaultVersion: result.version
    }
  }
  void placeNodesInView()
}

/** 手点「校验」：只出右侧校验结果框，不连带打开调试或进入发布 */
async function onValidate() {
  const result = await runDraftValidation()
  if (!result) {
    return
  }
  applyValidation(result, { openPanel: true })
  if (result.passed) {
    ElMessage.success(`校验通过，${result.warns.length} 条提示`)
  } else {
    ElMessage.error(`校验未通过：${result.errors.length} 个错误`)
  }
}

/** 发布：先静默校验 → 存草稿 → 生成不可变版本 */
async function onPublish() {
  const id = workflowId.value
  if (!id || !canPublish.value) {
    return
  }
  // 与手动「校验」同一条路径：不通过就不进发布，把问题留在右侧校验框
  const passed = await validateBeforeAction()
  if (!passed) {
    return
  }
  let note = ''
  try {
    const result = await ElMessageBox.prompt(
      '可选填发布说明。发布后以当前草稿生成新的不可变版本，历史版本不会改动。',
      '发布工作流',
      {
        confirmButtonText: '发布',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '本次发布说明（最多 512 字）',
        inputValidator: (value: string) => (value || '').length <= 512 || '最多 512 字'
      }
    )
    note = ((result as { value?: string }).value || '').trim()
  } catch {
    return
  }
  if (canEdit.value) {
    const saved = await onSaveDraft(true)
    if (!saved) {
      return
    }
  }
  publishing.value = true
  try {
    const { data } = await publishWorkflow(id, { note: note || undefined })
    ElMessage.success(`已发布 v${data.data.version}`)
    issues.value = []
    lastResult.value = null
    validatedSignature.value = ''
    // 发布成功后旧草稿的校验结果不再有展示意义，一并收起
    validationOpen.value = false
    await loadDef()
  } catch {
    // 发布校验未通过时后端已返回中文原因
  } finally {
    publishing.value = false
  }
}

/**
 * 校验结果 → 右侧校验结果悬浮框。
 *
 * @param result 后端校验返回
 * @param options.openPanel true 强制浮出（手点「校验」）；false 不自动浮出（调试 / 发布前的静默预检）；
 *   不传时沿用旧口径「有问题就浮出」，当前调试与校验入口都显式传值
 */
function applyValidation(result: WorkflowValidateResp, options: { openPanel?: boolean } = {}) {
  const list: IssueItem[] = []
  result.errors.forEach((issue, index) => {
    list.push({ ...issue, level: 'error', key: `e-${index}-${issue.code}` })
  })
  result.warns.forEach((issue, index) => {
    list.push({ ...issue, level: 'warn', key: `w-${index}-${issue.code}` })
  })
  issues.value = list
  lastResult.value = { errors: result.errors.length, warns: result.warns.length }
  validatedSignature.value = currentSignature.value
  // 属性框已开时不清 propsOpen：只是临时让位，关掉校验框后原属性框自动回来
  const shouldOpen = options.openPanel === undefined ? list.length > 0 : options.openPanel
  if (shouldOpen) {
    validationOpen.value = true
  }
}

/** 点击校验结果条目：选中并居中到节点；不关结果框，只有 X 才关 */
function focusIssue(issue: IssueItem) {
  const nodeId = issue.nodeId
  if (!nodeId) {
    return
  }
  selected.value = { kind: 'node', id: nodeId }
  const node = findNode(nodeId)
  if (!node) {
    return
  }
  const width = Number(node.dimensions?.width ?? 160)
  const height = Number(node.dimensions?.height ?? 60)
  // 用户主动定位，等于自己改过视野：暂停调试跟随，避免和自动对准抢
  pauseFollow()
  void setCenter(node.position.x + width / 2, node.position.y + height / 2, { zoom: 1, duration: 300 })
}

/** 拖出悬浮节点库：把节点类型写进 DataTransfer，落点仍由画布 drop 接管 */
function onDragStart(item: WorkflowPaletteItem, event: Event) {
  const dragEvent = event as DragEvent
  if (!item.p0 || !canEdit.value) {
    dragEvent.preventDefault()
    return
  }
  dragEvent.dataTransfer?.setData(DRAG_MIME, item.type)
  dragEvent.dataTransfer?.setData('text/plain', item.type)
  if (dragEvent.dataTransfer) {
    dragEvent.dataTransfer.effectAllowed = 'move'
  }
}

/** 落到画布：屏幕坐标转流坐标后新增节点 */
function onDrop(event: Event) {
  const dragEvent = event as DragEvent
  if (!canEdit.value) {
    ElMessage.warning('没有编辑权限')
    return
  }
  const raw =
    dragEvent.dataTransfer?.getData(DRAG_MIME) || dragEvent.dataTransfer?.getData('text/plain') || ''
  const meta = PALETTE.find((item) => item.type === raw)
  if (!meta || !meta.p0) {
    return
  }
  const position = screenToFlowCoordinate({ x: dragEvent.clientX, y: dragEvent.clientY })
  addNode(meta.type, position)
}

/** 新增节点并选中 */
function addNode(type: WorkflowNodeType, position: { x: number; y: number }) {
  const node: Node<WorkflowFlowNodeData> = {
    id: nextNodeId(),
    type: 'workflow',
    position: { x: Math.round(position.x), y: Math.round(position.y) },
    data: {
      code: nextNodeCode(type),
      name: NODE_NAME[type] || type,
      nodeType: type,
      config: type === 'LOOP' ? { maxIterations: 3, onExceeded: 'DOWNSTREAM' } : {}
    }
  }
  addNodes(node)
  selected.value = { kind: 'node', id: node.id }
  // 新增即选中：属性框按「选中浮出」的规则打开，方便立刻配名称与参数
  revealPropsForSelection(true)
}

/** 生成未占用的 n1、n2… */
function nextNodeId(): string {
  const used = new Set(vf.nodes.value.map((node) => node.id))
  let index = 1
  while (used.has(`n${index}`)) {
    index += 1
  }
  return `n${index}`
}

/** 生成未占用的节点 code（图内唯一） */
function nextNodeCode(type: WorkflowNodeType): string {
  const base = CODE_PREFIX[type] || String(type).toLowerCase()
  const used = new Set(
    vf.nodes.value.map((node) => String(((node.data || {}) as WorkflowFlowNodeData).code || ''))
  )
  if (!used.has(base)) {
    return base
  }
  let index = 2
  while (used.has(`${base}_${index}`)) {
    index += 1
  }
  return `${base}_${index}`
}

/** 生成未占用的 e1、e2… */
function nextEdgeId(): string {
  const used = new Set(vf.edges.value.map((edge) => edge.id))
  let index = 1
  while (used.has(`e${index}`)) {
    index += 1
  }
  return `e${index}`
}

/** 连线：自环与重复连线在画布层先挡一层，最终仍由后端校验兜底 */
function onConnect(connection: Connection) {
  if (!canEdit.value) {
    return
  }
  const source = connection.source
  const target = connection.target
  if (!source || !target) {
    return
  }
  if (source === target) {
    ElMessage.warning('P0 禁止自环，请连接到其它节点')
    return
  }
  const duplicated = vf.edges.value.some((edge) => edge.source === source && edge.target === target)
  if (duplicated) {
    ElMessage.warning('这两个节点之间已存在连线')
    return
  }
  addEdges({
    id: nextEdgeId(),
    source,
    target,
    // 新边同样走自定义边走线；边类型仍由 data.edgeType 决定
    type: 'workflow',
    markerEnd: MarkerType.ArrowClosed,
    data: { edgeType: 'NORMAL', label: null, config: {} }
  })
}

/** 选中节点：浮出属性悬浮框；已关闭时再次选中会重新打开 */
function onNodeClick({ node }: NodeMouseEvent) {
  selected.value = { kind: 'node', id: node.id }
  revealPropsForSelection(true)
}

/** 选中连线：属性同样在右侧悬浮框编辑，联动规则与节点一致 */
function onEdgeClick({ edge }: EdgeMouseEvent) {
  selected.value = { kind: 'edge', id: edge.id }
  revealPropsForSelection(true)
}

/** 点空白清空选中；不关属性框，只点框上的关闭才关 */
function onPaneClick() {
  selected.value = null
}

/**
 * 画布自身异常（缺尺寸、节点类型非法等）兜底：
 * 不白屏，给出中文错误条；画布仍保留，用户可刷新重试。
 */
function onVueFlowError(payload: unknown) {
  const text = payload instanceof Error ? payload.message : String(payload ?? '')
  canvasError.value = `画布异常：${text || '请刷新页面重试'}`
}

/** 键盘删除后清空指向已删除元素的选中（VueFlow 无 delete 事件，用列表长度守卫） */
watch(
  [() => vf.nodes.value.length, () => vf.edges.value.length],
  () => {
    const sel = selected.value
    if (!sel) {
      return
    }
    const exists =
      sel.kind === 'node'
        ? vf.nodes.value.some((node) => node.id === sel.id)
        : vf.edges.value.some((edge) => edge.id === sel.id)
    if (!exists) {
      selected.value = null
    }
  }
)

/**
 * 绑定 / 解绑工具。
 * <p>除 toolId 外同步写入展示用名称：画布节点标题要直接显示工具名，
 * 但这两个字段只存在于画布 data，buildGraph 不会落库。
 *
 * @param value 下拉选中的 toolId；清空时为 undefined / 空串
 */
function setNodeTool(value: number | string | undefined) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  const toolId = value === undefined || value === null || value === '' ? undefined : Number(value)
  const tool = toolOptions.value.find((item) => item.id === toolId)
  node.data = {
    ...node.data,
    config: { ...node.data.config, toolId },
    toolTitle: tool?.title ?? '',
    toolName: tool?.name ?? '',
    // 换绑后必填提示要跟着新工具的定义走；旧映射里同名的键仍然算已配
    missingInputHint: toolInputHint(tool, node.data.config.inputMapping)
  }
}

/**
 * 切换 Agent 节点的「等用户回复」。
 *
 * <p>关掉时显式写 false 而不是删键：草稿里留一个明确的 false，发布快照与后端读取口径一致。
 *
 * @param value 开关值
 */
function setAwaitReply(value: boolean | string | number) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  node.data = {
    ...node.data,
    config: { ...node.data.config, awaitReply: value === true }
  }
}

/**
 * 把 toolId 对应的工具名称回填到所有 TOOL 节点。
 * <p>工具下拉与草稿并行加载，草稿先到时名称还解析不出来；
 * 这里在两边任一就绪后统一补齐，同值短路避免无意义重渲染。
 * <p>同时按工具 schema 算一次「必填入参未配」提示，画布上直接标出来。
 */
function syncToolLabels() {
  const byId = new Map(toolOptions.value.map((item) => [item.id, item]))
  vf.nodes.value.forEach((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    if (String(data.nodeType) !== 'TOOL') {
      return
    }
    const tool = byId.get(Number(data.config?.toolId))
    const toolTitle = tool?.title ?? ''
    const toolName = tool?.name ?? ''
    const missingInputHint = toolInputHint(tool, data.config?.inputMapping)
    if (
      data.toolTitle === toolTitle &&
      data.toolName === toolName &&
      (data.missingInputHint ?? '') === missingInputHint
    ) {
      return
    }
    node.data = { ...data, toolTitle, toolName, missingInputHint }
  })
}

/** 条件节点表达式写回 config */
function setCondition(value: unknown) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  node.data = { ...node.data, config: { ...node.data.config, condition: value } }
}

/**
 * 绑定 / 解绑 Agent。
 * <p>除 agentId 外同步写入展示用名称与编码：画布节点标题要直接显示 Agent 名称，
 * 但这两个字段只存在于画布 data，buildGraph 不会落库。
 *
 * @param value 下拉选中的 agentId；清空时为 undefined / 空串
 */
function setNodeAgent(value: number | string | undefined) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  const agentId = value === undefined || value === null || value === '' ? undefined : Number(value)
  const agent = agentOptions.value.find((item) => item.id === agentId)
  node.data = {
    ...node.data,
    config: { ...node.data.config, agentId },
    agentName: agent?.name ?? '',
    agentCode: agent?.code ?? '',
    // 换绑后必填提示跟着新 Agent 的声明走；旧映射里同名的键仍然算已配
    missingInputHint: agentInputHint(agent, node.data.config.inputMapping)
  }
}

/**
 * 把 agentId 对应的 Agent 名称/编码回填到所有 AGENT 节点。
 * <p>Agent 下拉与草稿是并行加载的，草稿先到时名称还解析不出来；
 * 这里在两边任一就绪后统一补齐，且做同值短路，避免无意义地触发画布重渲染。
 * <p>下拉只取第一页（上限 100 条）：命中不了的 id 再走详情接口补名称，避免画布退化成「Agent #30」。
 */
function syncAgentLabels() {
  const byId = new Map(agentOptions.value.map((item) => [item.id, item]))
  const unresolved: number[] = []
  vf.nodes.value.forEach((node) => {
    const data = (node.data || {}) as WorkflowFlowNodeData
    if (String(data.nodeType) !== 'AGENT') {
      return
    }
    const agentId = Number(data.config?.agentId)
    const agent = byId.get(agentId) ?? agentLabelCache.get(agentId)
    if (!agent && Number.isFinite(agentId) && agentId > 0 && !agentLabelMissing.has(agentId)) {
      unresolved.push(agentId)
    }
    const agentName = agent?.name ?? ''
    const agentCode = agent?.code ?? ''
    // Agent 定义里的入参声明随列表下发：有声明且必填键没配来源时，画布上直接标出来
    const missingInputHint = agentInputHint(agent, data.config?.inputMapping)
    if (
      data.agentName === agentName &&
      data.agentCode === agentCode &&
      (data.missingInputHint ?? '') === missingInputHint
    ) {
      return
    }
    node.data = { ...data, agentName, agentCode, missingInputHint }
  })
  // 下拉还没加载完就先不补查详情：等 loadAgentOptions 收尾后会再同步一次
  if (agentOptionsReady) {
    void resolveAgentLabels(unresolved)
  }
}/**
 * 为下拉第一页里没有的 Agent 补查名称。
 * <p>并发去重 + 失败记入 missing，保证同一 id 不会反复打接口；
 * 补全后清空缓存标记再回填一次节点标题。
 *
 * @param ids 未解析出名称的 agentId 列表
 */
async function resolveAgentLabels(ids: number[]) {
  if (agentLabelResolving) {
    return
  }
  const unique = [...new Set(ids)]
  if (!unique.length) {
    return
  }
  agentLabelResolving = true
  try {
    const results = await Promise.all(
      unique.map(async (id) => {
        try {
          const { data } = await getAgent(id)
          const detail = data.data
          return detail
            ? { id, name: detail.name, code: detail.code, inputSchema: detail.inputSchema ?? null }
            : { id, missing: true as const }
        } catch {
          // 已删除 / 无查看权限：记下来，后续不再重复请求
          return { id, missing: true as const }
        }
      })
    )
    results.forEach((item) => {
      if ('missing' in item) {
        agentLabelMissing.add(item.id)
        return
      }
      agentLabelCache.set(item.id, {
        name: item.name,
        code: item.code,
        inputSchema: item.inputSchema
      })
    })
  } finally {
    agentLabelResolving = false
  }
  syncAgentLabels()
}

/** 入参映射写回 config */
function setInputMapping(value: Record<string, string>) {
  setMapping('inputMapping', value)
}

/** 出参映射写回 config */
function setOutputMapping(value: Record<string, string>) {
  setMapping('outputMapping', value)
}

/** 映射写回 config */
function setMapping(field: 'inputMapping' | 'outputMapping', value: Record<string, string>) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  const config = { ...node.data.config, [field]: value }
  node.data = { ...node.data, config }
  // 必填提示跟着入参映射走：只重算当前节点，避免每次输入都全图扫列表
  if (field === 'inputMapping') {
    const type = String(node.data.nodeType)
    if (type === 'TOOL') {
      const tool = toolOptions.value.find((item) => item.id === Number(config.toolId))
      node.data = { ...node.data, missingInputHint: toolInputHint(tool, value) }
    } else if (type === 'AGENT') {
      const agentId = Number(config.agentId)
      const agent =
        agentOptions.value.find((item) => item.id === agentId) ?? agentLabelCache.get(agentId)
      node.data = { ...node.data, missingInputHint: agentInputHint(agent, value) }
    } else if (type === 'SUBGRAPH') {
      node.data = {
        ...node.data,
        missingInputHint: subgraphInputHint({ ...node.data, config } as WorkflowFlowNodeData)
      }
    }
  }
}

/**
 * 写回循环节点的次数、出路或反馈路径。空次数写成 null，保存时由后端拒绝。
 *
 * @param field 配置键
 * @param value 新值
 */
function setLoopField(field: 'maxIterations' | 'onExceeded' | 'feedbackPath', value: number | string | null) {
  const node = selectedNode.value
  if (!node) {
    return
  }
  node.data = { ...node.data, config: { ...node.data.config, [field]: value } }
}

/** 循环次数：没填时输入框显示空，不把缺省显示成 0。 */
const loopMaxIterations = computed(() => {
  const raw = selectedNode.value?.data.config.maxIterations
  return typeof raw === 'number' && raw > 0 ? raw : null
})

/** 改连线类型：同步 data.edgeType 与画布标签 */
function setEdgeType(value: WorkflowEdgeType) {
  const edge = selectedEdge.value
  if (!edge) {
    return
  }
  const data = (edge.data || { edgeType: 'NORMAL' }) as WorkflowFlowEdgeData
  const nextData: WorkflowFlowEdgeData = { ...data, edgeType: value }
  edge.data = nextData
  edge.label = labelText(value, nextData.label ?? null)
}

/** 改自定义标签；保留原始输入（不去空格），清空即回退为按类型显示 */
function setEdgeLabel(value: string) {
  const edge = selectedEdge.value
  if (!edge) {
    return
  }
  const data = (edge.data || { edgeType: 'NORMAL' }) as WorkflowFlowEdgeData
  const label = value === '' ? null : value
  edge.data = { ...data, label }
  edge.label = labelText(String(data.edgeType || 'NORMAL'), label)
}

/** 条件边的 JSONLogic：同时写 data.condition（画布绑定）与 config.condition（落库结构） */
function setEdgeCondition(value: unknown) {
  const edge = selectedEdge.value
  if (!edge) {
    return
  }
  const data = (edge.data || { edgeType: 'NORMAL' }) as WorkflowFlowEdgeData
  edge.data = {
    ...data,
    condition: value,
    config: { ...(data.config ?? {}), condition: value }
  }
}

/** 删除当前选中的节点（连带连线）或连线 */
function deleteSelected() {
  const sel = selected.value
  if (!sel) {
    return
  }
  if (sel.kind === 'node') {
    removeNodes([sel.id], true)
  } else {
    removeEdges([sel.id])
  }
  selected.value = null
}

/** 重新加载最新草稿；有未保存修改时先确认（用于 409 冲突后恢复） */
async function reloadDraft() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('重新加载会丢弃当前未保存修改，确定继续？', '重新加载草稿', {
        confirmButtonText: '重新加载',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      return
    }
  }
  canvasError.value = ''
  await loadAll()
}

/** 返回列表；有未保存修改时先确认 */
async function goList() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前草稿有未保存修改，确定返回列表？', '提示', {
        confirmButtonText: '返回列表',
        cancelButtonText: '继续编辑',
        type: 'warning'
      })
    } catch {
      return
    }
  }
  void router.push('/orchestration/workflow')
}

/**
 * 当前路由是否仍停在本编辑页（/orchestration/workflow/:id）。
 * <p>KeepAlive 失活后 watch 仍会执行：跳到 /agent/list/12、/rag/kb/3/docs 等
 * 同样带 :id 的路由时，workflowId 会被解析成对方的 id，
 * 若不加闸就会去请求 /api/workflow/defs/12 并弹「工作流不存在」。
 */
function onWorkflowEditorRoute(): boolean {
  return route.path === `/orchestration/workflow/${workflowId.value}`
}

// 同一页面切换到另一条工作流（页签复用一个组件）时重新载入；其它路由一律忽略
watch(workflowId, (next, prev) => {
  if (!next || next === prev) {
    return
  }
  if (!onWorkflowEditorRoute()) {
    return
  }
  void loadAll()
})

onMounted(async () => {
  window.addEventListener('keydown', onFocusKeydown)
  // 首次进入：节点库打开，属性框等选中再浮出，不记忆上次的开合
  resetPanelLayout()
  await Promise.all([loadAll(), loadAgentOptions(), loadToolOptions()])
})

/** 组件真正销毁（关页签）时摘掉全局监听并复位壳层，防止侧栏永久隐藏 */
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onFocusKeydown)
  exitFocusMode()
})

/** KeepAlive 切走时退出专注：壳层布局是全局的，不能跟着缓存页一起走 */
onDeactivated(() => {
  // 调试框是子组件、收不到 deactivated：由本页把失活状态传下去，调试框据此停轮询与秒表
  pageActive.value = false
  if (focusMode.value) {
    exitFocusMode()
  }
})

/**
 * KeepAlive 页签切回时：
 * 未改动则静默刷新（拿最新 updateTime，避免拿旧时间撞 409）；
 * 已有未保存改动则保留画布，不覆盖用户输入。
 */
let activatedOnce = false
onActivated(() => {
  pageActive.value = true
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  // 页签切回等同「重新进入页面」：悬浮面板开合是纯 UI 状态，复位为默认视野
  resetPanelLayout()
  if (workflowId.value && !dirty.value) {
    void loadAll()
  }
})
</script>

<style scoped>
.wf-page {
  display: flex;
  flex-direction: column;
  /* 页签 40px + 主区上下留白（--ah-main-pad-y）。旧的 100vh-200px 会在底栏下空出一截 */
  height: calc(100vh - 40px - var(--ah-main-pad-y, 36px));
  min-height: 0;
  /*
   * 调试悬浮框按整个窗口定位的上下边距：
   * 上 = 页签条 40px + 8px 空隙，正好贴在页签条下沿；
   * 下 = 距窗口底 12px，只留一点空隙，让框基本吃满整屏高度。
   * 子组件用 var() 读取，本页只负责给出与壳层一致的数值。
   */
  --wf-debug-top: 48px;
  --wf-debug-bottom: 12px;
}

/* 专注模式：页签条已隐藏，只扣主区留白 */
.wf-page.is-focus {
  height: calc(100vh - var(--ah-main-pad-y, 18px));
  min-height: 0;
  /* 专注模式没有页签条，上边距同步收到 12px */
  --wf-debug-top: 12px;
  --wf-debug-bottom: 12px;
}

.block-alert {
  margin-bottom: 10px;
}

/* 顶栏分组竖线：版本切换与「校验 + 发布」分开，发布始终在最右 */
.wf-header-divider {
  width: 1px;
  height: 18px;
  margin: 0 2px;
  background: var(--ah-border, #ebeef5);
}

/* 返回列表：与画布顶部按钮组同一套弱对比圆形图标，hover 才提亮，不抢标题视觉 */
.wf-back.el-button.is-circle {
  width: 28px;
  height: 28px;
  border-color: transparent;
  background: transparent;
  color: var(--ah-text-regular);
}

.wf-back.el-button.is-circle:hover {
  border-color: transparent;
  background: var(--ah-neutral-tint);
  color: var(--ah-primary);
}

/*
 * 右侧校验结果悬浮框：复用 .wf-panel 的贴边尺寸（同属性框：右侧 12px、上下 12px、宽 320px），
 * 但它是独立框，不与属性框共用组件或 DOM。只占画布浮层，不把画布挤窄。
 * z-index 必须高于调试框（8）：调试框开着时校验失败，结果框要浮在它上面让人看到。
 * 用双类名提高优先级：后面 .wf-panel { z-index: 7 } 同优先级会把它压回调试框下面。
 */
.wf-panel.wf-validate {
  right: 12px;
  z-index: 9;
  width: 320px;
}

/* 顶部只放计数，不放标题 */
.wf-validate__counts {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.wf-validate__inner {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

/* 结果已过期只在本框内提示，不另起横条或弹窗 */
.wf-validate__stale {
  margin: 0 0 2px;
  color: var(--ah-warning);
  font-size: 12px;
}

/* 0 错误 0 提示时的空态：明确告诉用户校验已通过 */
.wf-validate__empty {
  margin: 0;
  color: var(--ah-text-faint);
  font-size: 12px;
}

/* 一条一行：按纵向列表排列，完整文案折行显示，不截断、不横向滚动 */
.wf-issue {
  display: flex;
  flex: none;
  align-items: flex-start;
  gap: 6px;
  width: 100%;
  padding: 6px 8px;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  background: var(--ah-surface);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.wf-issue.is-error {
  border-color: var(--el-color-danger-light-5);
  background: var(--ah-danger-tint);
  color: var(--ah-danger);
}

.wf-issue.is-warn {
  border-color: var(--el-color-warning-light-5);
  background: var(--ah-warning-tint);
  color: var(--ah-warning);
}

.wf-issue__code {
  flex: none;
  color: inherit;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  opacity: 0.75;
}

.wf-issue__msg {
  flex: 1;
  min-width: 0;
  line-height: 1.5;
  white-space: normal;
  overflow-wrap: anywhere;
}

.wf-body {
  position: relative;
  display: flex;
  flex: 1;
  min-height: 0;
}

/*
 * 左右悬浮框：叠在画布上，不再各占一列把画布挤窄。
 * 上下留白一致（12px）：上方避开画布顶部居中的编辑按钮组，下方不再为底部胶囊额外留大空档。
 */
.wf-panel {
  position: absolute;
  top: 12px;
  bottom: 12px;
  z-index: 7;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgb(15 23 42 / 8%);
  border-radius: 10px;
  background: var(--ah-surface);
  box-shadow: 0 10px 28px rgba(15, 23, 42, 0.12);
}

.wf-palette {
  left: 12px;
  width: 210px;
}

.wf-props {
  right: 12px;
  width: 320px;
}

.wf-panel__head {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--ah-border, #ebeef5);
}

.wf-panel__inner {
  box-sizing: border-box;
  flex: 1;
  min-height: 0;
  padding: 10px 12px;
  overflow-y: auto;
}

.wf-panel__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  min-width: 0;
  color: var(--ah-text-primary, #1f2937);
  font-size: 13px;
  font-weight: 600;
}

/* 面板唯一关闭入口：低干扰，hover 才显形 */
.wf-panel__close {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ah-text-faint);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.wf-panel__close:hover {
  background: var(--ah-neutral-tint);
  color: var(--ah-text-regular);
}

/*
 * 节点库关闭后贴在左边缘的小入口。
 * 常态只露窄条（26px），hover 横向展开浮出文字；点它重新打开悬浮框。
 */
.wf-float-entry {
  position: absolute;
  top: 50%;
  left: 0;
  z-index: 7;
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
  gap: 4px;
  width: 26px;
  height: 72px;
  padding: 0 0 0 4px;
  overflow: hidden;
  border: 1px solid rgb(15 23 42 / 8%);
  border-left: 0;
  border-radius: 0 8px 8px 0;
  /* 半透明浅灰底 + 轻阴影：比纯白描边更显眼，又不喧宾夺主 */
  background: rgb(243 244 246 / 94%);
  color: var(--ah-text-regular);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  white-space: nowrap;
  transform: translateY(-50%);
  transition: width 0.22s ease, background 0.18s ease, color 0.18s ease, box-shadow 0.18s ease;
}

.wf-float-entry:hover,
.wf-float-entry:focus-visible {
  width: 86px;
  background: var(--ah-surface);
  color: var(--ah-primary);
  box-shadow: 0 6px 18px rgb(99 91 255 / 22%);
}

.wf-float-entry:focus-visible {
  outline: 2px solid rgb(99 91 255 / 45%);
  outline-offset: 1px;
}

.wf-float-entry__icon {
  flex: none;
  font-size: 13px;
}

/* 文字默认收起，hover 时随宽度一起浮现 */
.wf-float-entry__label {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.16s ease;
}

.wf-float-entry:hover .wf-float-entry__label,
.wf-float-entry:focus-visible .wf-float-entry__label {
  opacity: 1;
  transition-delay: 0.06s;
}

.wf-wake-enter-active,
.wf-wake-leave-active {
  transition: opacity 0.15s ease;
}

.wf-wake-enter-from,
.wf-wake-leave-to {
  opacity: 0;
}

.wf-palette__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.wf-palette__item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 7px 9px;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  background: var(--ah-surface-muted);
  font-size: 12px;
  cursor: grab;
  user-select: none;
}

.wf-palette__item:hover {
  border-color: var(--el-color-primary-light-6);
  background: var(--ah-primary-tint);
}

.wf-palette__item.is-p1 {
  border-style: dashed;
  color: var(--ah-text-faint);
  cursor: not-allowed;
}

.wf-palette__label {
  color: var(--ah-text-primary, #1f2937);
  font-weight: 600;
}

.wf-palette__item.is-p1 .wf-palette__label {
  color: var(--ah-text-faint);
}

.wf-palette__p1 {
  padding: 0 4px;
  border: 1px solid var(--ah-border);
  border-radius: 4px;
  font-size: 12px;
  line-height: 14px;
}

.wf-palette__hint {
  flex: 1 0 100%;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.4;
}

.wf-canvas {
  position: relative;
  flex: 1;
  align-self: stretch;
  min-width: 0;
  min-height: 0;
  height: auto;
  overflow: hidden;
  border: 1px solid var(--ah-border, #ebeef5);
  border-radius: 8px;
  background: var(--ah-surface-muted);
}

.canvas-alert {
  position: absolute;
  top: 8px;
  left: 8px;
  right: 8px;
  z-index: 5;
}

/* ===== 画布顶部居中悬浮按钮组：保存草稿 / 重新加载 / 专注 ===== */
/*
 * 只留图标、名字走 tooltip；普通编辑与专注模式都摆这一处。
 * 居中叠在画布顶部，避开左右贴边的悬浮框，也不碰底部缩放 / 调试胶囊。
 */
.wf-canvas-actions {
  position: absolute;
  top: 12px;
  left: 50%;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  border: 1px solid rgb(15 23 42 / 8%);
  border-radius: 999px;
  background: var(--ah-surface);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  transform: translateX(-50%);
  /* 只接按钮自身的事件，胶囊留白仍可拖画布 */
  pointer-events: none;
  user-select: none;
}

/* 画布错误条（如 409 冲突）占着最上面时整组下移，避免把提示压住 */
.wf-canvas-actions.is-alerted {
  top: 56px;
}

.wf-canvas-actions > * {
  pointer-events: auto;
}

.wf-canvas-actions__slot {
  display: inline-flex;
}

/* 组内统一 28px 圆形弱对比按钮，与底部缩放胶囊同一观感；hover 才提亮 */
.wf-canvas-actions :deep(.el-button.is-circle) {
  width: 28px;
  height: 28px;
  margin: 0;
  border-color: transparent;
  background: transparent;
  color: var(--ah-text-regular);
}

.wf-canvas-actions :deep(.el-button.is-circle:hover) {
  border-color: transparent;
  background: var(--ah-neutral-tint);
  color: var(--ah-primary);
}

/* 专注中沿用主色浅底强调；退出仍靠 ScaleToOriginal 图标区分 */
.wf-canvas-actions :deep(.el-button.is-circle.is-focus-on) {
  background: var(--ah-primary-tint);
  color: var(--ah-primary);
}

/* 专注态 hover 也只加深主色浅底，不退回中性灰，避免看不出当前在专注里 */
.wf-canvas-actions :deep(.el-button.is-circle.is-focus-on:hover) {
  background: var(--el-color-primary-light-8);
  color: var(--ah-primary);
}

/* 不可点（如草稿无改动时的保存）仍要一眼看出禁用：压暗图标、不清空位置 */
.wf-canvas-actions :deep(.el-button.is-circle.is-disabled) {
  background: transparent;
  color: var(--ah-text-faint);
  cursor: not-allowed;
}

/* ===== 模块二：底部居中悬浮胶囊工具栏 ===== */
.wf-dock {
  position: absolute;
  bottom: 16px;
  left: 50%;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 5px 8px;
  border: 1px solid rgb(15 23 42 / 8%);
  border-radius: 999px;
  background: var(--ah-surface);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  transform: translateX(-50%);
  /* 胶囊留白处穿透：节点拖到工具栏下方时仍能被抓住 */
  pointer-events: none;
  user-select: none;
}

.wf-dock > * {
  pointer-events: auto;
}

.wf-dock__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--ah-text-regular);
  font-size: 14px;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.wf-dock__btn:hover {
  background: var(--ah-neutral-tint);
  color: var(--ah-primary);
}

.wf-dock__zoom {
  min-width: 42px;
  color: var(--ah-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.wf-dock__divider {
  width: 1px;
  height: 16px;
  margin: 0 4px;
  background: var(--ah-border);
}

/*
 * 调试跟随状态：只在有当前执行节点时出现，比调试主按钮轻。
 * 跟随中用主色浅底；被用户拖开后转中性灰并显示「已暂停」，点一下立刻对准并恢复。
 */
.wf-dock__follow {
  height: 24px;
  padding: 0 10px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: var(--ah-primary-tint, rgb(99 91 255 / 12%));
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}

.wf-dock__follow:hover {
  background: var(--el-color-primary-light-8, rgb(99 91 255 / 20%));
}

.wf-dock__follow.is-paused {
  background: var(--ah-neutral-tint, #f3f4f6);
  color: var(--ah-text-secondary, #6b7280);
}

.wf-dock__follow.is-paused:hover {
  background: var(--ah-border, #ebeef5);
  color: var(--ah-text-regular, #4b5563);
}

/* 调试是工具栏唯一主色按钮，视觉权重高于缩放 */
.wf-dock__run {
  height: 28px;
  margin-left: 4px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  background: var(--ah-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
}

.wf-dock__run:hover {
  background: var(--ah-primary-hover);
}

.wf-dock__run:disabled {
  background: var(--el-color-primary-light-5);
  cursor: not-allowed;
}

.wf-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.wf-form :deep(.el-form-item__label) {
  padding-bottom: 3px;
  font-size: 12px;
  line-height: 1.4;
}

.wf-field {
  width: 100%;
}

.wf-tip {
  margin: -4px 0 10px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.5;
}

.wf-tip--error {
  color: var(--ah-danger);
}

/* 运行时限区块：与节点属性用同一条分隔线，避免两块表单挤在一起 */
.wf-limits {
  margin: 0 0 14px;
  padding: 0 0 10px;
  border-bottom: 1px solid var(--ah-border);
}

.wf-limits__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.wf-limits__title {
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 600;
}

.wf-limits__hint {
  color: var(--ah-text-faint);
  font-size: 12px;
}

.wf-delete {
  width: 100%;
}

.wf-props__empty {
  padding: 18px 6px;
  color: var(--ah-text-faint);
  font-size: 12px;
  line-height: 1.7;
  text-align: center;
}

.wf-props__empty-hint {
  font-size: 12px;
}

.wf-footer {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  padding: 8px 12px;
  border: 1px solid var(--ah-border, #ebeef5);
  border-radius: 8px;
  background: var(--ah-surface);
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
}

.wf-footer__right {
  flex: none;
}

.wf-footer__dirty {
  color: var(--ah-warning);
}

.wf-footer .is-error {
  color: var(--ah-danger);
}

.wf-footer .is-warn {
  color: var(--ah-warning);
}

/* VueFlow 铺满容器 */
.wf-canvas :deep(.vue-flow) {
  width: 100%;
  height: 100%;
}
</style>
