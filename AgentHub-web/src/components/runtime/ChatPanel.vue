<template>
  <div class="chat" :class="{ 'is-workbench': isWorkbench }">
    <div ref="scroller" class="messages" @scroll.passive="onScroll">
      <!--
        进行态粘在滚动容器顶，内容变长时仍可见；结束后整条不渲染。
        工作台不再用它当「思考中」占位：进行态只看各参与者自己的思考框 / 内容框（含光标）；
        只有断线这一条需要全局告知，才在工作台保留。
      -->
      <div v-if="liveTurn && (!isWorkbench || detached)" class="phase phase-sticky" :class="'phase-' + liveTurn.phase">
        <span class="phase-bar" />
        <span>{{ detached ? '连接已断开，等待本轮结束' : phaseLabel(liveTurn.phase) }}</span>
        <!-- 断线时不再显示「已等待」，避免让人以为还在收增量 -->
        <!-- 秒表下沉为叶子组件：只有这行文字每秒更新，对话树不跟着整棵重渲 -->
        <ElapsedSeconds v-if="!detached" class="wait" :start-time="liveTurn.startTime" />
        <span v-else class="wait">结束后自动补齐</span>
      </div>

      <div v-if="!rows.length" class="empty">
        <template v-if="isWorkbench">
          <h3>需要我帮您做什么？</h3>
          <p>我是 AgentHub 智能网关，我会为您自动分配合适的专家。</p>
          <div class="quick-cards">
            <button
              v-for="item in quickPromptList"
              :key="item"
              type="button"
              class="quick-card"
              :disabled="!canSend || sending || !sessionReady"
              @click="sendQuick(item)"
            >
              {{ item }}
            </button>
          </div>
        </template>
        <template v-else>
          <h3>开始对话</h3>
          <p>新建会话后在下方输入。Enter 发送，Shift+Enter 换行。</p>
        </template>
      </div>

      <div v-for="row in rows" :key="row.turn.key" class="row" :class="row.turn.role">
        <!-- 用户气泡：登录用户头像（或首字兜底）；与 Agent 形象分开 -->
        <template v-if="row.turn.role === 'user'">
          <div class="speak-row is-user">
            <div class="bubble">{{ row.turn.text }}</div>
            <div
              class="msg-avatar is-user"
              :class="{ 'has-photo': !!userAvatarSrc }"
              :style="userAvatarStyle"
              :title="userDisplayName"
            >
              <img v-if="userAvatarSrc" :src="userAvatarSrc" alt="" />
              <span v-else>{{ userInitial }}</span>
            </div>
          </div>
        </template>

        <!-- 计划执行模式：顺序钉死（计划思考 → 计划卡 → 按步执行 → 终答） -->
        <div v-else-if="row.flow" class="speak-row is-assistant">
          <div
            class="msg-avatar is-assistant"
            :style="assistantAvatarStyle(row.turn)"
            :title="assistantDisplayName(row.turn)"
          >
            <img
              v-if="assistantLook(row.turn).iconSrc"
              :src="assistantLook(row.turn).iconSrc"
              :class="{ 'is-custom': assistantLook(row.turn).custom }"
              alt=""
            />
            <AgentLookArt
              v-else-if="assistantLook(row.turn).art"
              :art="assistantLook(row.turn).art!"
              :color="assistantLook(row.turn).initialColor"
            />
            <span v-else>{{ assistantLook(row.turn).initial }}</span>
          </div>
        <article class="card">
          <!-- 计划流同样不在卡片头挂展示名：名称只出现在终答气泡上方，避免和计划卡抢身份 -->
          <!-- Router 试跑的编排调度卡：在用户气泡之后、思考与终答之前，数据只读不落库 -->
          <RouterTimeline
            v-if="showRouterCard(row.turn)"
            :trace-data="routerTrace?.model ?? null"
            :status="routerCardStatus(row.turn)"
            :error="routerTrace?.error ?? ''"
            :loading="routerTrace?.loading === true"
            :trace-id="routerTrace?.traceId ?? ''"
            @refresh="emit('refresh-router')"
            @open-gantt="emit('open-gantt')"
          />
          <!-- 1. 计划阶段的思考（可折叠）。出计划本身也有推理，不能只给执行阶段留思考位 -->
          <div v-if="row.flow.planPhase.length" class="think-block">
            <button
              type="button"
              class="think-toggle"
              :aria-expanded="isOpen(row.turn, PHASE_KEY, true)"
              :aria-label="isOpen(row.turn, PHASE_KEY, true) ? '收起计划思考' : '展开计划思考'"
              @click.stop.prevent="toggle(row.turn, PHASE_KEY, true)"
            >
              <span class="section-label">计划思考</span>
              <span class="chevron" :class="{ open: isOpen(row.turn, PHASE_KEY, true) }" aria-hidden="true" />
            </button>
            <div v-show="isOpen(row.turn, PHASE_KEY, true)" class="think-panel">
              <ProcessTimeline :steps="row.flow.planPhase" :streaming="row.turn.status === 'streaming'" />
            </div>
          </div>

          <!-- 2. 执行计划卡：待批准 / 执行中 / 已完成 -->
          <div v-if="row.flow.plan" class="plan-block">
            <div class="plan-head">
              <div class="section-label answer-label">执行计划</div>
              <el-tag size="small" effect="plain" :type="planTagType(row.flow.plan.state)">
                {{ planStateText(row.flow.plan.state) }}
              </el-tag>
            </div>
            <ol class="plan-steps">
              <li v-for="step in row.flow.plan.steps" :key="step.seq" class="plan-step">
                <span class="plan-seq">{{ step.seq }}</span>
                <span class="plan-text">
                  <span class="plan-title">{{ step.title }}</span>
                  <span v-if="step.detail" class="plan-detail">{{ step.detail }}</span>
                </span>
              </li>
            </ol>
            <div v-if="canApprove && row.turn.planPending" class="plan-actions">
              <el-button type="primary" size="small" :loading="approving" @click="onApprove(row.turn)">批准执行</el-button>
              <el-button size="small" :disabled="approving" @click="onReject(row.turn)">取消</el-button>
            </div>
            <!-- 批准后接口要跑完整轮才返回：这段时间必须有进行态，不能只剩一个转圈的按钮 -->
            <p v-else-if="approving && row.flow.plan.state === 'EXECUTING'" class="plan-running">
              已批准，正在按计划逐步执行…
            </p>
          </div>

          <!-- 3. 按步执行：每步含自己的思考（可折叠）与结果，顺序即执行顺序 -->
          <section
            v-for="step in row.flow.execution"
            :key="'s-' + step.seq"
            class="exec-step"
            :class="'step-' + step.state"
          >
            <button
              type="button"
              class="exec-head"
              :disabled="!step.steps.length"
              @click.stop.prevent="onStepHead(row.turn, step.seq, step.steps.length)"
            >
              <span class="exec-badge">{{ stepStateText(step.state) }}</span>
              <span class="exec-title">第 {{ step.seq }} 步 · {{ step.title }}</span>
              <template v-if="step.steps.length">
                <span class="exec-hint">
                  {{ isOpen(row.turn, stepKey(step.seq), true) ? '收起思考' : '展开思考' }}
                </span>
                <span class="chevron" :class="{ open: isOpen(row.turn, stepKey(step.seq), true) }" aria-hidden="true" />
              </template>
            </button>
            <div
              v-show="step.steps.length && isOpen(row.turn, stepKey(step.seq), true)"
              class="exec-think"
            >
              <ProcessTimeline
                :steps="step.steps"
                :streaming="step.state === 'RUNNING' || step.state === 'RETRYING'"
              />
            </div>
            <p v-if="step.state === 'DONE' && step.text" class="exec-result">
              完成第 {{ step.seq }} 步：{{ step.text }}
            </p>
            <p v-else-if="step.state === 'RETRYING' && step.text" class="exec-result retry">
              重试中：{{ step.text }}
            </p>
            <p v-else-if="step.state === 'FAILED' && step.text" class="exec-result error">
              第 {{ step.seq }} 步失败：{{ step.text }}
            </p>
          </section>

          <!-- 4. 终答。永远排在计划卡与步骤之后，不允许插队 -->
          <!-- 工作台：名称随终答气泡出现一次，且置于气泡外上方，不当作卡片头 -->
          <div v-if="isWorkbench && row.flow.answer" class="msg-name">{{ assistantDisplayName(row.turn) }}</div>
          <div v-if="row.flow.answer" class="answer-block">
            <!-- 工作台：不展示「最终回答」标题与朗读调试图标，终端用户只看正文气泡 -->
            <div v-if="!isWorkbench" class="answer-head">
              <div class="section-label answer-label">最终回答</div>
              <div v-if="row.turn.status !== 'streaming'" class="speak-actions">
                <el-tooltip :content="speakHintOf(planAnswerKey(row.turn))" placement="top">
                  <button
                    type="button"
                    class="speak-icon"
                    :class="{ active: isSpeaking(planAnswerKey(row.turn)) }"
                    :aria-label="speakHintOf(planAnswerKey(row.turn))"
                    @click="toggleSpeak(planAnswerKey(row.turn), row.flow.answer)"
                  >
                    <el-icon :class="{ 'is-loading': isPreparing(planAnswerKey(row.turn)) }">
                      <Loading v-if="isPreparing(planAnswerKey(row.turn))" />
                      <VideoPause v-else-if="isPlaying(planAnswerKey(row.turn))" />
                      <VideoPlay v-else />
                    </el-icon>
                  </button>
                </el-tooltip>
                <el-tooltip v-if="isSpeaking(planAnswerKey(row.turn))" content="停止朗读" placement="top">
                  <button type="button" class="speak-icon stop" aria-label="停止朗读" @click="stopSpeech">
                    <el-icon><Close /></el-icon>
                  </button>
                </el-tooltip>
              </div>
            </div>
            <div
              class="md answer"
              v-html="markdown(planAnswerSlot(row.turn), displayAnswer(row.flow.answer), row.turn.status === 'streaming')"
            />
            <span v-if="row.turn.status === 'streaming'" class="caret">|</span>
          </div>

          <p v-for="(note, index) in row.flow.notes" :key="'n-' + index" class="note">{{ note }}</p>
          <p
            v-for="(err, index) in row.flow.errors"
            :key="'e-' + index"
            :class="isWorkbench ? 'wb-soft-error' : 'error'"
          >
            {{ displayErrorText(err) }}
          </p>

          <!-- 5. 产物：Agent 写进工作区的文件。排在终答之后，是「结果」而不是「过程」 -->
          <FileCard v-for="file in row.flow.files" :key="'f-' + file.fileName" :file="file" />

          <p v-if="showPlanThinking(row.turn, row.flow)" class="thinking">
            {{ row.turn.planPending ? '计划待批准' : '思考中…' }}
          </p>
        </article>
        </div>

        <!-- 非计划流程（REACT / ROUTER）：保持原有「思考过程 + 块顺序」渲染 -->
        <div v-else class="speak-row is-assistant">
          <!-- 工作台有聊天块时，行首大 avatar 让位：身份改挂在每一块自己的头像 + 名称上，
               否则 36px 头像会贴在块列表左侧，看起来像「外包作答 Agent」 -->
          <div
            v-if="!(isWorkbench && hasParticipantFeed(row.turn))"
            class="msg-avatar is-assistant"
            :style="assistantAvatarStyle(row.turn)"
            :title="assistantDisplayName(row.turn)"
          >
            <img
              v-if="assistantLook(row.turn).iconSrc"
              :src="assistantLook(row.turn).iconSrc"
              :class="{ 'is-custom': assistantLook(row.turn).custom }"
              alt=""
            />
            <AgentLookArt
              v-else-if="assistantLook(row.turn).art"
              :art="assistantLook(row.turn).art!"
              :color="assistantLook(row.turn).initialColor"
            />
            <span v-else>{{ assistantLook(row.turn).initial }}</span>
          </div>
        <article class="card">
          <!-- 展示名不再挂在卡片头：思考面板有自己的「智能网关 / 专家」两步身份，
               作答 Agent 的名称只在正文气泡上方出现，避免和思考面板抢身份 -->
          <!-- Router 试跑的编排调度卡：在用户气泡之后、思考与终答之前，数据只读不落库 -->
          <RouterTimeline
            v-if="showRouterCard(row.turn)"
            :trace-data="routerTrace?.model ?? null"
            :status="routerCardStatus(row.turn)"
            :error="routerTrace?.error ?? ''"
            :loading="routerTrace?.loading === true"
            :trace-id="routerTrace?.traceId ?? ''"
            @refresh="emit('refresh-router')"
            @open-gantt="emit('open-gantt')"
          />
          <!--
            工作台消息流：智能网关、企业综合台（方案）、各位专家统一成同一套聊天块——
            左侧头像 + 名称 + 一块内容框（气泡）。本步结果写在内容框里，不做框外提示行。
            思考默认折叠在名称与内容框之间；全场「查看思考与路由轨迹」不放消息流，执行明细在右上角。
          -->
          <div v-if="isWorkbench && participantFeedOf(row.turn).length" class="wb-actors">
            <WorkbenchActorTree :cards="participantFeedOf(row.turn)">
              <template #default="{ card }">
              <section class="wb-actor" :class="'is-' + card.kind">
                <!--
                  头像与名称、角标同一行并垂直居中（对照 temp/test.html / test-l.html）。
                  下方思考 / 工具 / 气泡 / 工作流卡缩进到名称列，不把头像拉高到整块高度。
                -->
                <div class="wb-actor-topline">
                  <span
                    class="wb-actor-avatar"
                    :class="{ 'is-workflow': isWorkflowCard(card), 'is-running': card.pending === true }"
                    :style="participantAvatarStyle(card)"
                    :title="card.title"
                  >
                    <!-- 工作流卡固定黑色齿轮：不读 lookOf、不按名称换形象，与上方头像同一套方座 -->
                    <WorkflowGearGlyph v-if="isWorkflowCard(card)" />
                    <img
                      v-else-if="card.look?.iconSrc"
                      :src="card.look.iconSrc"
                      :class="{ 'is-custom': card.look.custom }"
                      alt=""
                    />
                    <AgentLookArt
                      v-else-if="card.look?.art"
                      :art="card.look.art"
                      :color="card.look.initialColor"
                    />
                    <span v-else>{{ card.look?.initial || (card.title || '专').slice(0, 1) }}</span>
                  </span>
                  <div class="wb-actor-head">
                    <span class="wb-actor-name">{{ card.title }}</span>
                    <!--
                      角标读配置与卡片类型：入口「对话入口」、方案「方案」、单兵专家「Agent」、工作流「工作流 WF」。
                      is-wf 只给工作流块上紫色底，其余角标样式完全不变。
                    -->
                    <span
                      v-if="actorRoleBadge(card)"
                      class="wb-actor-role"
                      :class="{ 'is-wf': !!card.workflow }"
                    >{{ actorRoleBadge(card) }}</span>
                  </div>
                </div>
                <div class="wb-actor-main">
                  <!--
                    有子卡或汇总时，外框就是这一张方案。思考、工具、正文留在框的上半，
                    专家卡和汇总画在框内，不再跟方案并排，也不把专家名单再写一遍当正文。
                  -->
                  <div :class="{ 'wb-scheme-frame': schemeMembers(card).length > 0 }">
                  <!-- 思考区：进行中先出这一行并保持可看；结束后默认收起，点左侧箭头再展开 -->
                  <div
                    v-if="showThoughtToggle(row.turn, card)"
                    class="wb-actor-thought"
                    :class="{
                      'is-streaming': actorThinkOpenByDefault(row.turn, card),
                      'is-folded': !isOpen(row.turn, actorThinkPart(card), actorThinkOpenByDefault(row.turn, card))
                    }"
                  >
                    <button
                      type="button"
                      class="wb-thought-head"
                      :aria-expanded="isOpen(row.turn, actorThinkPart(card), actorThinkOpenByDefault(row.turn, card))"
                      :aria-label="thoughtToggleLabel(row.turn, card)"
                      @click.stop.prevent="toggle(row.turn, actorThinkPart(card), actorThinkOpenByDefault(row.turn, card))"
                    >
                      <span
                        class="thought-chevron"
                        :class="{ open: isOpen(row.turn, actorThinkPart(card), actorThinkOpenByDefault(row.turn, card)) }"
                        aria-hidden="true"
                      />
                      <span class="section-label">{{ thoughtToggleLabel(row.turn, card) }}</span>
                    </button>
                    <div
                      class="wb-actor-fold"
                      :class="{ 'is-collapsed': !isOpen(row.turn, actorThinkPart(card), actorThinkOpenByDefault(row.turn, card)) }"
                    >
                      <div
                        :ref="actorFoldRef(row.turn.key, card.key)"
                        class="wb-actor-fold-body"
                      >
                        <p v-if="actorThinkingText(card)" class="wb-actor-thinking">{{ actorThinkingText(card) }}</p>
                      </div>
                    </div>
                  </div>
                  <!--
                    工具调用胶囊：同名调用聚合成一条；调用中蓝、有失败红、全成功绿，点开看逐次入参出参。
                    三拍时序：只有思考这一拍结束（工具或结果已出现）后才允许出胶囊，失败也一样。
                  -->
                  <div v-if="!actorThinkingBeat(row.turn, card) && actorToolPills(card).length" class="wb-tool-pills">
                    <template v-for="pill in actorToolPills(card)" :key="pill.key">
                      <button
                        type="button"
                        class="wb-tool-pill"
                        :class="'is-' + pill.state"
                        :aria-expanded="pill.state === 'running' ? undefined : toolDetailOpen[pill.key] === true"
                        @click.stop.prevent="pill.state === 'running' ? undefined : toggleToolDetail(pill.key)"
                      >
                        <span class="wb-tool-icon" aria-hidden="true">
                          <template v-if="pill.state === 'running'">◠</template>
                          <template v-else-if="pill.state === 'done'">✓</template>
                          <template v-else>✕</template>
                        </span>
                        <span class="wb-tool-label">{{ toolPhrase(pill) }}</span>
                        <span v-if="pill.state !== 'running' && toolHasDetail(pill)" class="wb-tool-more">
                          {{ toolDetailOpen[pill.key] === true ? '收起' : '详情' }}
                        </span>
                      </button>
                      <pre
                        v-if="pill.state !== 'running' && toolDetailOpen[pill.key] === true && toolHasDetail(pill)"
                        class="wb-tool-detail"
                      >{{ presentToolDetail(pill.detail) }}</pre>
                    </template>
                  </div>
                  <!--
                    工作流执行卡片（视觉以 temp/test-l.html 为准，过程/结果上下分栏）：
                    块内只有这一张卡，内部子专家只出现在时间轴节点上，不在卡下再拆 Agent 块。
                    上方过程区浅底；下方结果区实线分隔 +「结果」小标题；产物走 Markdown（保留换行）。
                  -->
                  <div v-if="card.workflow" class="wf-card" :class="{ completed: wfCompleted(card.workflow, card.closed) }">
                    <div class="wf-process">
                      <div class="wf-card-header">
                        <!-- 标题不加齿轮：左侧消息流头像已是固定黑齿轮，卡内再画会叠成两个 -->
                        <div class="wf-title">自动化执行流</div>
                        <div class="wf-status" :class="wfStatusClass(card.workflow, card.closed)">
                          <!-- 运行中只画一个旋转沙漏；文案侧不再带 ⏳，避免两个沙漏叠在一起 -->
                          <span v-if="wfStatusClass(card.workflow, card.closed) === 'is-running'" class="wf-spin" aria-hidden="true">⏳</span>
                          {{ wfStatusText(card.workflow, card.closed) }}
                        </div>
                      </div>
                      <div v-if="card.workflow.nodes.length" class="wf-steps">
                        <div
                          v-for="(node, nodeIndex) in card.workflow.nodes"
                          :key="node.code || ('wf-' + nodeIndex)"
                          class="wf-step-item"
                          :class="'is-' + wfStepState(node)"
                        >
                          <div class="wf-step-icon" :class="'is-' + wfStepState(node)">
                            <span v-if="wfStepState(node) === 'active'" class="wf-spin" aria-hidden="true">⏳</span>
                            <template v-else-if="wfStepState(node) === 'done'">✓</template>
                            <template v-else-if="wfStepState(node) === 'failed'">✕</template>
                            <template v-else>{{ nodeIndex + 1 }}</template>
                          </div>
                          <div class="wf-step-main">
                            <div class="wf-step-name">
                              {{ node.name || node.code }}
                              <span v-if="node.code && node.code !== node.name" class="wf-step-code">{{ node.code }}</span>
                            </div>
                            <div class="wf-step-note">{{ wfStepNote(node) }}</div>
                            <!-- 仅图上 TOOL 节点出胶囊；AGENT 内部工具未进快照，不编造 -->
                            <div
                              v-for="pill in wfNodeToolPills(node)"
                              :key="pill.key"
                              class="wb-tool-pills wf-step-tools"
                            >
                              <span class="wb-tool-pill" :class="'is-' + pill.state">
                                <span class="wb-tool-icon" aria-hidden="true">
                                  <template v-if="pill.state === 'running'">◠</template>
                                  <template v-else-if="pill.state === 'done'">✓</template>
                                  <template v-else>✕</template>
                                </span>
                                <span class="wb-tool-label">{{ workflowNodeToolLabel(pill, node.status, toolFaceName(pill.name, toolTitleByName)) }}</span>
                              </span>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                    <!--
                      结果区：正文固定四行，框内滚动；移入后底部箭头展开。
                      文件在正文下面单独一行，不进正文框。
                    -->
                    <div
                      v-if="wfOutputVisible(row.turn, card)"
                      class="wf-result"
                      :class="{ 'is-text': !wfFilesOf(card).length }"
                    >
                      <div class="wf-result-title">{{ wfResultTitle(card) }}</div>
                      <p v-if="wfOutputTip(card)" class="wf-result-tip">{{ wfOutputTip(card) }}</p>
                      <div
                        v-if="wfAnswerOf(card)"
                        class="wb-result-box"
                      >
                        <button
                          type="button"
                          class="wb-copy"
                          aria-label="复制"
                          @click.stop.prevent="copyResult(wfAnswerOf(card))"
                        >
                          <el-icon :size="14"><CopyDocument /></el-icon>
                        </button>
                        <div
                          :ref="answerTextRef(row.turn.key, card.key)"
                          class="md answer wf-output-text"
                          :class="bodyTextClass(row.turn, card)"
                          v-html="markdown(row.turn.key + ':wf:' + card.key, displayAnswer(wfAnswerOf(card)), row.turn.status === 'streaming' && card.pending)"
                        />
                        <button
                          v-if="bodyExpandActionOf(row.turn, card) !== 'none'"
                          type="button"
                          class="wb-result-jump"
                          :class="{ 'is-up': bodyExpandActionOf(row.turn, card) === 'collapse' }"
                          :aria-label="bodyExpandActionOf(row.turn, card) === 'collapse' ? '收回四行' : '展开全文'"
                          @click.stop.prevent="toggleMidBoxExpand(row.turn.key, card.key)"
                        >
                          <span class="wb-jump-chevron" aria-hidden="true" />
                        </button>
                      </div>
                      <div v-if="wfFilesOf(card).length" class="wb-file-row">
                        <FileCard v-for="file in wfFilesOf(card)" :key="file.fileName" :file="file" />
                      </div>
                    </div>
                  </div>
                  <!--
                    空终态说明在正文框外面。它不是正文，也不占四行框。
                  -->
                  <p v-if="card.terminalNote && !card.workflow" class="wb-terminal-note">{{ card.terminalNote }}</p>
                  <!--
                    内容框（气泡）：只放判定句、分流句、报错、正文和选项。
                    正文默认四行，框内可滚；移入后底部箭头展开，再点收回。
                    文件不进这个框，在框下面单独一行。
                  -->
                  <div
                    v-if="actorHasBubble(row.turn, card)"
                    class="answer-block"
                  >
                    <p
                      v-for="(line, resultIndex) in actorResultLines(card)"
                      :key="'r-' + resultIndex"
                      class="wb-actor-result"
                    >{{ line }}</p>
                    <p v-for="(err, errIndex) in card.errors" :key="'err-' + errIndex" class="wb-soft-error">{{ err }}</p>
                    <div
                      v-if="card.answer"
                      class="wb-result-box"
                    >
                      <button
                        type="button"
                        class="wb-copy"
                        aria-label="复制"
                        @click.stop.prevent="copyResult(card.answer)"
                      >
                        <el-icon :size="14"><CopyDocument /></el-icon>
                      </button>
                      <div
                        :ref="answerTextRef(row.turn.key, card.key)"
                        class="md answer"
                        :class="bodyTextClass(row.turn, card)"
                        v-html="markdown(row.turn.key + ':' + card.key, displayAnswer(card.answer), row.turn.status === 'streaming' && card.pending)"
                      />
                      <button
                        v-if="bodyExpandActionOf(row.turn, card) !== 'none'"
                        type="button"
                        class="wb-result-jump"
                        :class="{ 'is-up': bodyExpandActionOf(row.turn, card) === 'collapse' }"
                        :aria-label="bodyExpandActionOf(row.turn, card) === 'collapse' ? '收回四行' : '展开全文'"
                        @click.stop.prevent="toggleMidBoxExpand(row.turn.key, card.key)"
                      >
                        <span class="wb-jump-chevron" aria-hidden="true" />
                      </button>
                    </div>
                    <!--
                      可点选项：模型需要用户拍板时给的 chips（机器块已从正文里摘掉）。
                      只有「整轮最后一条助手消息」上的选项可点：历史轮次的选项已失效，
                      再点会续作到已经翻页的上下文里。点击走断点续作，不重走判词。
                    -->
                    <div v-if="card.choice && card.choice.options.length" class="wb-choice">
                      <!-- 题干永远有一行：模型给了问题就用它，没给就用「请选择」，不让选项孤零零地出现 -->
                      <p class="wb-choice-question">{{ (card.choice.question || '').trim() || '请选择' }}</p>
                      <div class="wb-choice-options">
                        <button
                          v-for="option in card.choice.options"
                          :key="option.id"
                          type="button"
                          class="wb-choice-chip"
                          :disabled="!choiceClickable(row.turn)"
                          @click.stop.prevent="onChoice(row.turn, option)"
                        >{{ option.label }}</button>
                      </div>
                    </div>
                  </div>
                  <div v-if="!card.workflow && card.files.length" class="wb-file-row">
                    <FileCard v-for="file in card.files" :key="file.fileName" :file="file" />
                  </div>
                  <WorkbenchActorNest v-if="schemeMembers(card).length" :cards="schemeMembers(card)" />
                  </div>
                </div>
              </section>
              </template>
            </WorkbenchActorTree>
          </div>

          <!-- 调试皮肤：多路由按子 Agent 分块的原始时间线。工作台消息流不再放全场轨迹 -->
          <div v-if="!isWorkbench && hasProcess(row.turn)" class="think-block">
            <button
              type="button"
              class="think-toggle"
              :aria-expanded="isOpen(row.turn, THINK_KEY, thinkDefaultOpen)"
              :aria-label="isOpen(row.turn, THINK_KEY, thinkDefaultOpen) ? '收起思考过程' : '展开思考过程'"
              @click.stop.prevent="toggle(row.turn, THINK_KEY, thinkDefaultOpen)"
            >
              <span class="section-label">
                {{ thinkToggleLabel(row.turn) }}
              </span>
              <span class="chevron" :class="{ open: isOpen(row.turn, THINK_KEY, thinkDefaultOpen) }" aria-hidden="true" />
            </button>
            <div
              v-show="isOpen(row.turn, THINK_KEY, thinkDefaultOpen)"
              class="think-panel"
              :class="{ 'lanes-multi': lanesOf(row.turn).length > 1 }"
            >
              <section
                v-for="(lane, laneIndex) in lanesOf(row.turn)"
                :key="laneIndex"
                class="think-lane"
                :class="'lane-' + lane.kind"
              >
                <div class="lane-head">
                  <template v-if="lane.kind === 'agent' && routeAgentName(lane.title)">
                    路由到 <span class="route-agent">{{ routeAgentName(lane.title) }}</span>
                  </template>
                  <template v-else>{{ thinkText(lane.title) }}</template>
                  <!-- 内部 route_key 不对终端用户展示：工作台在展示层再脱敏一次 -->
                </div>
                <pre v-if="lane.body" class="lane-body">{{ thinkText(lane.body) }}</pre>
                <ol v-if="lane.steps.length" class="timeline">
                  <li
                    v-for="(step, stepIndex) in lane.steps"
                    :key="stepIndex"
                    class="timeline-item"
                    :class="'step-' + step.type"
                  >
                    <div class="timeline-rail" aria-hidden="true">
                      <span class="timeline-dot" />
                      <span class="timeline-line" />
                    </div>
                    <div class="timeline-main">
                      <div class="step-title">{{ step.title }}</div>
                      <div
                        v-if="step.type === 'reasoning'"
                        class="md think-body"
                        v-html="markdown(stepSlot(row.turn, laneIndex, stepIndex), thinkText(step.text), row.turn.status === 'streaming')"
                      />
                      <pre v-else-if="step.type === 'tool_call'" class="step-call">{{ thinkText(step.text) }}</pre>
                      <div v-else class="step-result">
                        <pre
                          class="step-result-body"
                          :class="{ clamped: !isResultExpanded(row.turn, laneIndex, stepIndex) }"
                        >{{ thinkText(step.text) }}</pre>
                        <button
                          v-if="needsResultToggle(step.text)"
                          type="button"
                          class="result-toggle"
                          @click.stop.prevent="toggleResult(row.turn, laneIndex, stepIndex)"
                        >
                          {{ isResultExpanded(row.turn, laneIndex, stepIndex) ? '收起' : '展开' }}
                        </button>
                      </div>
                    </div>
                  </li>
                </ol>
              </section>
            </div>
          </div>

          <!-- 块列表：key 必须用数据层分配的稳定 id，禁止用数组下标（视觉块是 unshift 到队首的） -->
          <template v-for="(block, index) in row.turn.blocks" :key="blockKey(block, index)">
            <!-- 图片文字提取结果：本轮素材，排在思考与终答之前，可折叠 -->
            <div v-if="block.kind === 'vision'" class="vision-block">
              <button
                type="button"
                class="think-toggle"
                :aria-expanded="isOpen(row.turn, VISION_KEY, false)"
                :aria-label="isOpen(row.turn, VISION_KEY, false) ? '收起图片文字' : '展开图片文字'"
                @click.stop.prevent="toggle(row.turn, VISION_KEY, false)"
              >
                <span class="section-label">图片文字</span>
                <span class="chevron" :class="{ open: isOpen(row.turn, VISION_KEY, false) }" aria-hidden="true" />
              </button>
              <pre v-show="isOpen(row.turn, VISION_KEY, false)" class="vision-body">{{ block.text }}</pre>
            </div>
            <template v-else-if="block.kind === 'assistant' && showLooseAssistant(isWorkbench, participantFeedHasActor(row.turn))">
              <!-- 工作台不走这条：正文只出现在参与者卡里。调试皮肤在还没有参与者卡时仍显示原文 -->
              <div class="answer-block">
              <!-- 工作台：隐藏朗读 ▷ 与「最终回答」黑话标题 -->
              <div v-if="!isWorkbench" class="answer-head">
                <div class="section-label answer-label">最终回答</div>
                <div v-if="row.turn.status !== 'streaming'" class="speak-actions">
                  <el-tooltip :content="speakHintOf(blockAnswerKey(row.turn, block))" placement="top">
                    <button
                      type="button"
                      class="speak-icon"
                      :class="{ active: isSpeaking(blockAnswerKey(row.turn, block)) }"
                      :aria-label="speakHintOf(blockAnswerKey(row.turn, block))"
                      @click="toggleSpeak(blockAnswerKey(row.turn, block), block.text)"
                    >
                      <el-icon :class="{ 'is-loading': isPreparing(blockAnswerKey(row.turn, block)) }">
                        <Loading v-if="isPreparing(blockAnswerKey(row.turn, block))" />
                        <VideoPause v-else-if="isPlaying(blockAnswerKey(row.turn, block))" />
                        <VideoPlay v-else />
                      </el-icon>
                    </button>
                  </el-tooltip>
                  <el-tooltip
                    v-if="isSpeaking(blockAnswerKey(row.turn, block))"
                    content="停止朗读"
                    placement="top"
                  >
                    <button type="button" class="speak-icon stop" aria-label="停止朗读" @click="stopSpeech">
                      <el-icon><Close /></el-icon>
                    </button>
                  </el-tooltip>
                </div>
              </div>
              <div
                class="md answer"
                v-html="markdown(blockSlot(block, index), displayAnswer(block.text), row.turn.status === 'streaming')"
              />
              <span
                v-if="row.turn.status === 'streaming' && index === lastAssistantIndex(row.turn)"
                class="caret"
              >|</span>
              </div>
            </template>
            <p v-else-if="block.kind === 'system'" class="note">{{ block.text }}</p>
            <p
              v-else-if="block.kind === 'error' && !participantFeedHasActor(row.turn)"
              :class="isWorkbench ? 'wb-soft-error' : 'error'"
            >
              {{ displayErrorText(block.text) }}
            </p>
            <!-- 产物卡片：工作台分块时跟在对应专家气泡里，这里只留给非工作台 -->
            <FileCard v-else-if="block.kind === 'file' && block.file && !participantFeedHasActor(row.turn)" :file="block.file" />
          </template>
          <p v-if="showThinking(row.turn)" class="thinking">
            {{ row.turn.planPending ? '计划待批准' : '思考中…' }}
          </p>
        </article>
        </div>
      </div>

      <!--
        消息流最底部的轻量步骤提示：只说明这一步在等什么（规划中 / 思考中 / 调用工具中 / 作答中）。
        不做居中胶囊、不做粘性条；本轮结束或出错后整条消失。
      -->
      <p v-if="stepHint" class="wb-step-hint">
        <span class="wb-step-hint-dot" aria-hidden="true" />
        <span>{{ stepHint }}</span>
      </p>
    </div>

    <div class="composer" :class="{ 'has-at-panel': atPanelOpen }">
      <!-- 已选图片缩略图：带删除角标，发送前可逐个移除 -->
      <div v-if="attachments.length" class="thumbs">
        <div v-for="(item, index) in attachments" :key="item.id" class="thumb">
          <img :src="item.dataUrl" :alt="item.name" />
          <button
            type="button"
            class="thumb-remove"
            :aria-label="`移除 ${item.name}`"
            :disabled="sending"
            @click="removeAttachment(index)"
          >
            ×
          </button>
        </div>
      </div>
      <!-- @ 提及片：与正文分离，不进 {query} -->
      <div v-if="isWorkbench && selectedRoute?.agentName" class="mention-chip-row">
        <span class="mention-chip">
          @{{ selectedRoute.agentName }}
          <button
            type="button"
            class="mention-chip-x"
            :disabled="sending"
            aria-label="取消指定线路"
            @click="emit('clear-route')"
          >
            ×
          </button>
        </span>
      </div>
      <!-- @ 候选面板：展示名检索，不暴露 route_key -->
      <ul v-if="atPanelOpen" class="at-panel" role="listbox" aria-label="指定线路">
        <li v-if="!filteredAtCandidates.length" class="at-empty">无匹配线路</li>
        <li
          v-for="(item, index) in filteredAtCandidates"
          :key="item.routeKey"
          class="at-item"
          :class="{ active: index === atActiveIndex }"
          role="option"
          :aria-selected="index === atActiveIndex"
          @mousedown.prevent="confirmAtPick(item)"
        >
          <span class="at-name">{{ item.agentName || '未命名线路' }}</span>
          <span v-if="item.intentDesc" class="at-desc">{{ item.intentDesc }}</span>
          <span v-if="item.targetType && item.targetType !== 'AGENT'" class="at-type">
            {{ item.targetType === 'MA' ? '方案' : item.targetType === 'WORKFLOW' ? '工作流' : '' }}
          </span>
        </li>
      </ul>
      <el-input
        v-model="text"
        type="textarea"
        :rows="isWorkbench ? 2 : 3"
        :autosize="isWorkbench ? { minRows: 2, maxRows: 10 } : undefined"
        :resize="isWorkbench ? 'none' : 'vertical'"
        maxlength="8000"
        :placeholder="composerPlaceholder"
        :disabled="!canSend || sending"
        @keydown="onComposerKeydown"
        @input="onComposerInput"
      />
      <div class="composer-bar">
        <div class="composer-left">
          <!--
            自动朗读开关（喇叭）。开启后只朗读「之后完成」的最终回答（绑了 TTS 走模型音频，否则浏览器），
            绝不补读历史记录；关闭不再自动读，且立刻停掉正在朗读的那一条。
            开/关不用文字标注：开启时实心高亮，关闭时描边，靠形态与颜色一起区分。
          -->
          <el-tooltip v-if="!isWorkbench" :content="speakerHint" placement="top">
            <button
              type="button"
              class="voice-icon-btn speaker-btn"
              :class="{ on: autoSpeak }"
              :aria-pressed="autoSpeak"
              :aria-label="autoSpeak ? '关闭自动朗读' : '开启自动朗读'"
              @click="toggleAutoSpeak"
            >
              <el-icon><Headset /></el-icon>
            </button>
          </el-tooltip>
          <!--
            语音听写：未落地能力在工作台不摆可点按钮（避免伪装可用）；
            调试形态仍保留置灰入口并说明原因。
          -->
          <el-tooltip v-if="showMicBtn" :content="recordHint" placement="top">
            <button
              type="button"
              class="voice-icon-btn mic-btn"
              :class="{ recording: voiceRecording, muted: !canRecord && !voiceRecording && !transcribing }"
              :aria-disabled="!canRecord && !voiceRecording"
              :aria-label="voiceRecording ? '结束录音并识别' : '开始语音输入'"
              @click="toggleRecording"
            >
              <el-icon :class="{ 'is-loading': transcribing }">
                <Loading v-if="transcribing" />
                <Microphone v-else />
              </el-icon>
            </button>
          </el-tooltip>
          <el-tooltip v-if="voiceRecording" content="取消本次录音" placement="top">
            <button type="button" class="voice-icon-btn cancel-btn" aria-label="取消本次录音" @click="cancelVoiceRecording">
              <el-icon><Close /></el-icon>
            </button>
          </el-tooltip>
          <el-tooltip v-if="showImageBtn" :content="uploadHint" placement="top">
            <button
              type="button"
              class="voice-icon-btn image-btn"
              :class="{ muted: !canUploadNow }"
              :aria-disabled="!canUploadNow"
              aria-label="上传图片"
              @click="pickImages"
            >
              <el-icon><Picture /></el-icon>
            </button>
          </el-tooltip>
          <input
            ref="filePicker"
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif"
            multiple
            class="file-input"
            @change="onFilesPicked"
          />
          <!-- 工作台徽标：只有本次 @ 才写「已指定××」；未指定一律「自动匹配专家」，不替代 @ -->
          <span
            v-if="isWorkbench && gatewayBadge"
            class="gateway-badge"
            :title="gatewayBadgeTitle"
          >
            ⚡ {{ gatewayBadge }}
          </span>
          <span class="hint">{{ sessionReady ? '' : blockedHint || '请先新建会话或从左侧选择' }}</span>
        </div>
        <el-button v-if="canSend" type="primary" :loading="sending" :disabled="!sessionReady || sending" @click="submit">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 试跑 / 工作台对话区。所属域：runtime。只展示传入的时间线，不自己请求接口。
 *
 * 两种渲染分支：
 * - 计划执行模式（含 plan / step 块）：顺序钉死为
 *   计划思考（可折叠）→ 计划卡 → 按步执行（每步含可折叠思考与结果）→ 终答。
 *   终答与草稿一律排在计划卡之后，绝不抢到前面。
 * - REACT / ROUTER：沿用「思考过程（按子 Agent 分块）+ 块顺序」渲染。
 *
 * 工作台皮肤额外：输入框 @ 指定线路（提及片与 query 分离）、输入区徽标显示线路状态；
 * 调试皮肤（TrialPanel）不启用 @。
 *
 * 折叠默认展开，仅当用户点过标题才收起；终答出现也不自动收起。
 */
import { computed, onBeforeUnmount, onErrorCaptured, onMounted, onUpdated, reactive, ref, watch } from 'vue'
import { Microphone, Picture, Headset, VideoPlay, VideoPause, Loading, Close, CopyDocument } from '@element-plus/icons-vue'
import type {
  AssistantBlock,
  ChatTurn,
  RouterTraceView,
  TrialFileView,
  TrialWorkflowNodeView,
  TrialWorkflowView
} from '@/types/runtime'
import type { WorkbenchRouteOption } from '@/types/workbench'
import type { AgentRobotShape } from '@/constants/agentRobotShapes'
import { ElMessage } from 'element-plus'
import { transcribeAudio } from '@/api/voice'
import { useVoiceRecorder } from '@/composables/voiceRecorder'
import { useSpeechPlayer } from '@/composables/speechPlayer'
import {
  hasRouterTrace,
  isPlanFlow,
  lanesForDebugSkin,
  planStateText,
  processLanes,
  stepStateText,
  turnSections
} from '@/components/runtime/trialMessages'
import type { TurnSections } from '@/components/runtime/trialMessages'
import ProcessTimeline from '@/components/runtime/ProcessTimeline.vue'
import { buildParticipantFeed, feedWaitsForEntry, isFinishedStatusShell, showLooseAssistant, visitParticipantCards } from '@/components/runtime/workbench/workbenchParticipantFeed'
import { ParticipantFeedCache } from '@/components/runtime/workbench/workbenchFeedCache'
import type {
  ParticipantCard,
  ParticipantChoiceOption
} from '@/components/runtime/workbench/workbenchParticipantFeed'
import { aggregateToolPills, maskToolCallNames, presentToolDetail, toolFaceName } from '@/components/runtime/workbench/workbenchToolPill'
import {
  schemeChildrenSettled,
  workflowOutputState
} from '@/components/runtime/workbench/workflowOutputView'
import { WORKFLOW_ROLE_LABEL } from '@/components/runtime/workbench/workbenchThinkTimeline'
import {
  isMidProcessBox,
  isThinkOpenByDefault,
  BOX_MAX_LINES,
  bodyExpandAction,
  foldOpenOf,
  formatThoughtElapsed,
  isUnlimitedAnswerBox,
  nextFoldValue
} from '@/components/runtime/workbench/workbenchContentBox'
import { liveStepHintOf } from '@/components/runtime/workbench/workbenchStepHint'
import RouterTimeline from '@/components/runtime/RouterTimeline.vue'
import FileCard from '@/components/runtime/FileCard.vue'
import WorkbenchActorTree from '@/components/runtime/workbench/WorkbenchActorTree.vue'
import WorkbenchActorNest from '@/components/runtime/workbench/WorkbenchActorNest.vue'
import ElapsedSeconds from '@/components/runtime/ElapsedSeconds.vue'
import AgentLookArt from '@/components/agent/AgentLookArt.vue'
import WorkflowGearGlyph from '@/components/runtime/workbench/WorkflowGearGlyph.vue'
import {
  workflowNodeToolLabel,
  workflowNodeToolPill
} from '@/components/runtime/workbench/workbenchWorkflowNode'
import { WORKFLOW_AVATAR_COLOR, isWorkflowAvatarCard } from '@/components/runtime/workbench/workbenchAvatar'
import { resolveFollowAfterScroll } from '@/components/runtime/chatFollow'
import { listAllItems } from '@/api/tool'
import { presentVisibleResult } from '@/components/runtime/workbench/workbenchVisibleText'
import { renderMarkdownMemo } from '@/utils/simpleMarkdown'
import {
  friendlyWorkbenchError,
  stripWorkbenchLeadHeadings
} from '@/utils/workbenchFriendlyError'
import {
  detectWorkbenchAtTrigger,
  filterWorkbenchAtCandidates,
  stripWorkbenchAtFragment
} from '@/utils/workbenchAtRoute'
import { workbenchRouteBadgeTitle } from '@/utils/workbenchRouteBadge'
import {
  resolveWorkbenchAgentName,
  stripRouteDecisionJson,
  stripWorkbenchInternalKeys
} from '@/components/runtime/workbench/workbenchMessageMeta'
import {
  USER_AVATAR_FALLBACK_COLOR,
  initialOf,
  resolveAgentAvatar,
  resolveAvatarUrl,
  resolveFallbackRobotAvatar
} from '@/constants/agentLooks'
import { useUserStore } from '@/stores/user'

const props = defineProps<{
  turns: ChatTurn[]
  sending: boolean
  canSend: boolean
  sessionReady: boolean
  /** 不能发送时的说明。结案和未选会话用不同文案 */
  blockedHint?: string
  /**
   * 是否已与后端断开、只能等落库。
   * 整页刷新后前端不再持有 SSE，增量拿不到；此时状态条如实标注，不假装还在思考。
   */
  detached?: boolean
  /** 是否有批准计划权限。无权限时计划卡只读展示 */
  canApprove?: boolean
  /** 批准请求进行中，按钮转圈且禁止重复点 */
  approving?: boolean
  /**
   * 当前 Agent 是否已绑定可用的 VISION 模型。
   * false 时上传图片按钮置灰并给出 Hover 提示——后端也会拒，但让用户先看到原因体验更好。
   */
  visionEnabled?: boolean
  /** 当前 Agent id。语音听写 / 合成都要落到具体 Agent 的模型槽；为空时语音入口不可用 */
  agentId?: number
  /**
   * 当前 Agent 是否已绑定可用的 ASR 模型。
   * false 时「说话」按钮置灰并说明需绑定语音识别模型——听写不做任何浏览器识别回退。
   */
  asrEnabled?: boolean
  /**
   * 当前 Agent 是否已绑定可用的 TTS 模型。
   * false 时朗读<b>仍可用</b>：降级浏览器 speechSynthesis，只影响请求路径与提示文案。
   */
  ttsEnabled?: boolean
  /**
   * Router 试跑的编排卡数据（obs span 树归纳，只读且仅前端态）。
   * null 表示不展示：非 Router、无追踪权限或还没拿到数据时都由外层传 null。
   */
  routerTrace?: RouterTraceView | null
  /**
   * 展示皮肤：workbench=工作台沉浸（空状态 / 思考折叠 / 气泡 / 输入区）；
   * 缺省=调试试跑，行为与样式保持原样。
   */
  skin?: 'default' | 'workbench'
  /**
   * 页签是否在前台。工作台 KeepAlive 失活时为 false，停 400ms 思考时钟。
   * 缺省视为在前台：试跑抽屉不传，行为与改前一致。
   * 只停表，不改轮次、不收起思考面板。
   */
  pageActive?: boolean
  /** 工作台思考摘要用的线路展示名；勿写死「接待助手」 */
  routeHint?: string
  /**
   * 入口块展示名：读默认接待方案配置（现网 ma_def 名称，如 Router）。
   *
   * <p>消息流里的入口块必须与编排列表 / 详情同名；前端不维护对外别名。
   * 缺省时投影层才退回内置兜底，正常页面一定传。
   */
  entryName?: string
  /** 工作台空状态快捷提问；点卡片即填入并发送 */
  quickPrompts?: string[]
  /** 工作台输入区网关徽标文案，如「自动匹配专家」 */
  gatewayBadge?: string
  /** @ 面板候选（本会话快照方案启用项）；只展示名，不暴露 route_key */
  routeCandidates?: WorkbenchRouteOption[]
  /** 已选定的下一轮强制线路（提及片） */
  selectedRoute?: WorkbenchRouteOption | null
  /** 是否允许打开 @ 面板 */
  canPickRoute?: boolean
}>()

const emit = defineEmits<{
  send: [content: string, images?: string[]]
  /** 批准待执行计划；携带该轮 planId 便于定位 */
  approve: [planId?: string]
  /** 取消待执行计划 */
  reject: [planId?: string]
  /** 编排卡点了「刷新」；数据源在外层，本组件不自己请求 */
  'refresh-router': []
  /** 紧凑时间线点了「查看详细甘特图」；抽屉由外层页面持有 */
  'open-gantt': []
  /** 工作台 @ 选定线路（routeKey） */
  'pick-route': [routeKey: string]
  /** 清除 @ 提及片 */
  'clear-route': []
  /**
   * 点了 Agent 给出的可点选项：携带 optionId 与展示文案。
   *
   * <p>外层据此走**断点续作**（独立端点、绕开入口判词、同一个 Agent 接着干），
   * 不是再发一条普通消息——否则会被入口重新分流到别人身上。
   */
  'choice-selected': [optionId: string, label: string]
}>()

/** 是否工作台沉浸皮肤 */
const isWorkbench = computed(() => props.skin === 'workbench')
/** 工作台思考过程默认收起；调试仍默认展开 */
const thinkDefaultOpen = computed(() => !isWorkbench.value)

const userStore = useUserStore()
/** 当前登录用户展示名 */
const userDisplayName = computed(
  () => userStore.profile?.nickname || userStore.profile?.username || '用户'
)
const userInitial = computed(() => initialOf(userDisplayName.value))
const userAvatarSrc = computed(() => resolveAvatarUrl(userStore.profile?.avatarUrl))
const userAvatarStyle = computed(() =>
  userAvatarSrc.value
    ? undefined
    : { background: USER_AVATAR_FALLBACK_COLOR, color: '#fff' }
)

/** 气泡用的 Agent 形象解析结果 */
interface AssistantLookView {
  name: string
  initial: string
  iconSrc?: string
  /** 线稿图元（未命中机器人时为 undefined） */
  art?: AgentRobotShape[] | null
  /** 底座色：恒为白底方座 */
  color: string
  /** 线稿色 / 首字兜底色 */
  initialColor: string
  /** 自定义上传图：铺满裁切 */
  custom: boolean
}

/**
 * 解析本轮 AI 气泡形象：候选按 agentId / 名称匹配；多专家同会话时优先用思考车道里的 Agent 名。
 * 未配置形象时与时间线一致，走固定机器人兜底（不再是首字方座）。
 *
 * @param turn 本轮对话
 */
function assistantLook(turn: ChatTurn): AssistantLookView {
  const candidates = props.routeCandidates || []
  let matched: WorkbenchRouteOption | undefined
  // 1) 思考车道里的 Agent 名（多专家时更能对上本轮实际线路）
  for (const lane of lanesOf(turn)) {
    if (lane.kind !== 'agent') {
      continue
    }
    const name = routeAgentName(lane.title) || lane.title
    if (!name) {
      continue
    }
    matched = candidates.find((item) => (item.agentName || '').trim() === name.trim())
    if (matched) {
      break
    }
  }
  // 2) @ 选定 / 当前强制线路
  if (!matched && props.selectedRoute) {
    matched = props.selectedRoute
  }
  // 3) 调试皮肤：按当前 agentId
  if (!matched && props.agentId != null) {
    matched = candidates.find((item) => item.agentId === props.agentId)
  }
  const name =
    (matched?.agentName || '').trim() ||
    (props.routeHint || '').trim() ||
    '助手'
  // 与时间线共用同一套兜底：没配形象 / 配置认不出图形时给固定机器人，
  // 气泡里也不再只露「企」这种首字方座
  const view = lookOf(name)
  return {
    name,
    initial: view.initial,
    iconSrc: view.iconSrc,
    art: view.art,
    color: view.color,
    initialColor: view.initialColor,
    custom: view.custom
  }
}

/**
 * AI 头像样式：白底方座不变色；首字兜底时文字用该套霓虹色。
 *
 * @param turn 本轮
 */
function assistantAvatarStyle(turn: ChatTurn) {
  const look = assistantLook(turn)
  return {
    background: look.color,
    color: look.initialColor
  }
}

/**
 * 工作台 AI 名称行文案：优先取本轮思考车道里真实出现的「路由到 XXX」，
 * 多专家同会话时各条用自己的名称；候选里有规范展示名则用规范名。
 * 没有车道（单专家直答）时退回当前线路展示名；仍为空或占位则「智能助手」。
 *
 * @param turn 本轮助手消息
 * @return 可展示的 Agent 名称
 */
function assistantDisplayName(turn: ChatTurn): string {
  const candidates = props.routeCandidates || []
  for (const lane of lanesOf(turn)) {
    if (lane.kind !== 'agent') {
      continue
    }
    const laneName = routeAgentName(lane.title)
    if (!laneName) {
      continue
    }
    // 车道名可能只是 Agent 简称，候选里能对上时用统一展示名
    const matched = candidates.find((item) => (item.agentName || '').trim() === laneName)
    return resolveWorkbenchAgentName(matched?.agentName || laneName)
  }
  // 无车道：沿用头像解析出的线路名，但过滤掉「线路已失效」等状态占位
  return resolveWorkbenchAgentName(assistantLook(turn).name)
}

/** @ 面板是否打开 */
const atPanelOpen = ref(false)
/** @ 后的检索片段（不含 @） */
const atQuery = ref('')
/** 键盘高亮下标 */
const atActiveIndex = ref(0)
/**
 * 触发 @ 时，输入框里「@检索片段」的起始下标；关闭面板时用于剥离。
 * -1 表示当前没有挂起的 @ 片段。
 */
const atTriggerStart = ref(-1)

/** 按展示名过滤候选；永不展示 route_key */
const filteredAtCandidates = computed(() =>
  filterWorkbenchAtCandidates(props.routeCandidates, atQuery.value)
)

/** 工作台占位：提示可用 @ 指定 */
const composerPlaceholder = computed(() =>
  isWorkbench.value
    ? '输入问题，@ 指定线路；Enter 发送，Shift+Enter 换行'
    : '输入问题，Enter 发送，Shift+Enter 换行'
)

/** 徽标 hover：与文案同一口径（抽到纯函数，避免「写着自动匹配、提示却说已指定」） */
const gatewayBadgeTitle = computed(() => workbenchRouteBadgeTitle(props.gatewayBadge || ''))

/**
 * 根据 textarea 内容同步 @ 面板开闭与检索词。
 * 只认最后一个未闭合的 @（其后无空格）；有提及片后仍可再 @ 换人。
 */
function syncAtPanelFromText() {
  if (!isWorkbench.value || props.canPickRoute === false) {
    closeAtPanel()
    return
  }
  const hit = detectWorkbenchAtTrigger(text.value)
  if (!hit) {
    closeAtPanel()
    return
  }
  atTriggerStart.value = hit.start
  atQuery.value = hit.query
  if (!atPanelOpen.value) {
    atActiveIndex.value = 0
  }
  atPanelOpen.value = true
  // 检索变化时把高亮钳在范围内
  if (atActiveIndex.value >= filteredAtCandidates.value.length) {
    atActiveIndex.value = Math.max(0, filteredAtCandidates.value.length - 1)
  }
}

/** 关闭 @ 面板（不改正文） */
function closeAtPanel() {
  atPanelOpen.value = false
  atQuery.value = ''
  atTriggerStart.value = -1
  atActiveIndex.value = 0
}

/**
 * 选定候选：剥离输入框里的 @片段，发出 pick-route；提及片由外层 selectedRoute 驱动。
 *
 * @param item 候选
 */
function confirmAtPick(item: WorkbenchRouteOption) {
  if (!item?.routeKey) {
    return
  }
  text.value = stripWorkbenchAtFragment(text.value, atTriggerStart.value)
  closeAtPanel()
  emit('pick-route', item.routeKey)
}

/**
 * 输入区键盘：面板打开时上下/Enter/Esc 优先；否则 Enter 发送。
 *
 * @param event 键盘事件（el-input 透传）
 */
function onComposerKeydown(event: Event | KeyboardEvent) {
  const e = event as KeyboardEvent
  if (!e || typeof e.key !== 'string') {
    return
  }
  if (atPanelOpen.value) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      const max = filteredAtCandidates.value.length
      if (max > 0) {
        atActiveIndex.value = (atActiveIndex.value + 1) % max
      }
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      const max = filteredAtCandidates.value.length
      if (max > 0) {
        atActiveIndex.value = (atActiveIndex.value - 1 + max) % max
      }
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      // Esc：关掉面板并剥离未完成的 @片段，正文保留其余
      text.value = stripWorkbenchAtFragment(text.value, atTriggerStart.value)
      closeAtPanel()
      return
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      const pick = filteredAtCandidates.value[atActiveIndex.value]
      if (pick) {
        confirmAtPick(pick)
      }
      return
    }
  }
  // 面板关闭：Enter 发送，Shift+Enter 换行（不 prevent）
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    submit()
  }
}

/** el-input 内容变化时同步 @ 面板 */
function onComposerInput() {
  syncAtPanelFromText()
}

watch(
  () => props.canPickRoute,
  (ok) => {
    if (ok === false) {
      closeAtPanel()
    }
  }
)
/** 快捷提问：外层传入；空则三条中性示例（不编造未接入能力） */
const quickPromptList = computed(() =>
  props.quickPrompts?.length
    ? props.quickPrompts
    : [
        '我想咨询一个业务问题，请帮我匹配合适的专家',
        '请介绍一下你能协助处理哪些类型的请求',
        '帮我看看当前有什么需要优先处理的事项'
      ]
)

/** 待发送的图片。id 只用于列表 key 与删除定位 */
interface Attachment {
  id: number
  name: string
  /** data:{mime};base64,…，随发送一起提交给后端 */
  dataUrl: string
}

/** 单张图片的字节上限。与后端 VisionOcrTexts.MAX_IMAGE_CHARS 的量级对齐（约 6MB） */
const MAX_IMAGE_BYTES = 6 * 1024 * 1024

/** 单轮图片张数上限。与后端 VisionOcrTexts.MAX_IMAGES 一致，前后端不要各写一个数 */
const MAX_IMAGES = 4

/** 非计划流程里「思考过程」的折叠键后缀。 */
const THINK_KEY = 'think'
/** 计划阶段思考的折叠键后缀。 */
const PHASE_KEY = 'plan-phase'
/** 图片文字提取结果的折叠键后缀。默认收起，不抢终答的位置。 */
const VISION_KEY = 'vision'

/**
 * 点批准：把该轮的 planId 交给外层。外层负责调接口并在成功后刷新时间线。
 * @param item 计划所在的那一轮
 */
function onApprove(item: ChatTurn) {
  if (props.sending || props.approving) {
    return
  }
  emit('approve', item.planId)
}

/**
 * 点取消：同上，但走拒绝接口，不执行任何步骤。
 * @param item 计划所在的那一轮
 */
function onReject(item: ChatTurn) {
  if (props.sending || props.approving) {
    return
  }
  emit('reject', item.planId)
}

/**
 * 点单步标题行：只有该步真的带来了思考/工具时才切换折叠，
 * 否则点击是空操作（避免点出空白面板）。
 * @param item        该步所属的助手轮
 * @param seq         步骤序号
 * @param stepCount   该步已收集的思考步数
 */
function onStepHead(item: ChatTurn, seq: number, stepCount: number) {
  if (!stepCount) {
    return
  }
  toggle(item, stepKey(seq), true)
}

/** 计划状态对应的标签色。失败用 danger，待批准用 warning 提示需要人工介入 */
function planTagType(state?: string): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  if (state === 'PENDING') {
    return 'warning'
  }
  if (state === 'DONE') {
    return 'success'
  }
  if (state === 'EXECUTING') {
    return 'primary'
  }
  if (state === 'REJECTED') {
    return 'info'
  }
  return 'danger'
}

const text = ref('')
const scroller = ref<HTMLElement>()
/** 原生文件选择器句柄。用 input 而非 el-upload：只需读文件转 base64，不需要上传进度与分片 */
const filePicker = ref<HTMLInputElement>()
/** 已选待发图片。发送成功后才清空，失败保留让用户重试 */
const attachments = ref<Attachment[]>([])
/** 自增 id，仅用于列表 key 与删除定位 */
let attachmentSeq = 0

/** 语音转写进行中：按钮转圈，避免用户连点重复上传 */
const transcribing = ref(false)

/** 听写是否可用：必须已绑定 ASR 模型且落到具体 Agent。未绑定时不做任何浏览器识别回退 */
const canRecord = computed(() => props.asrEnabled === true && !!props.agentId && !props.sending && !transcribing.value)

const {
  recording: voiceRecording,
  seconds: voiceSeconds,
  start: startVoiceRecording,
  stop: stopVoiceRecording,
  cancel: cancelVoiceRecording
} = useVoiceRecorder({
  // 60 秒与后端 max-audio-bytes（10MB）是同一档量级：正常 webm/opus 远小于 10MB
  maxSeconds: 60,
  maxBytes: 10 * 1024 * 1024,
  onComplete: handleRecorded,
  onCancel: () => ElMessage.info('已取消录音'),
  onError: (message) => ElMessage.warning(message)
})

/** 工作台：未落地的语音入口不摆；录音中仍要显示以便结束/取消 */
const showMicBtn = computed(
  () => !isWorkbench.value || props.asrEnabled === true || voiceRecording.value
)
/** 工作台：未落地的图片入口不摆，避免伪装成可用 */
const showImageBtn = computed(() => !isWorkbench.value || props.visionEnabled === true)

/** 「说话」按钮的 Hover 说明。图标按钮没有可见文字，状态与原因全部靠它交代 */
const recordHint = computed(() => {
  if (!props.agentId) {
    return '当前会话没有 Agent，无法使用语音输入'
  }
  if (props.asrEnabled !== true) {
    return '未绑定语音识别模型，请前往 Agent 的「模型配置」Tab 绑定 ASR 模型'
  }
  if (transcribing.value) {
    return '正在识别，请稍候'
  }
  if (voiceRecording.value) {
    return `录音中 ${voiceSeconds.value}s，点击结束并识别（不会自动发送）`
  }
  return '点击开始录音，再点一次结束并识别（识别后不会自动发送）'
})

/** 点「说话」：未在录音则开始，录音中则结束并转写。 */
async function toggleRecording() {
  if (voiceRecording.value) {
    stopVoiceRecording()
    return
  }
  if (!canRecord.value) {
    return
  }
  try {
    await startVoiceRecording()
  } catch (error) {
    ElMessage.warning(errorMessage(error, '无法开始录音，请检查麦克风权限'))
  }
}

/**
 * 录音结束 → 调后端识别 → 把文字填进输入框。
 * 明确不自动发送：用户确认文字后再点发送，避免识别错误直接进入会话。
 *
 * @param audio 整段录音
 */
async function handleRecorded(audio: Blob) {
  const agentId = props.agentId
  if (!agentId) {
    return
  }
  transcribing.value = true
  try {
    const recognized = (await transcribeAudio(agentId, audio, recordingFileName(audio))).trim()
    if (!recognized) {
      ElMessage.warning('没有识别到内容，请靠近麦克风再说一次')
      return
    }
    // 已有草稿时追加，避免覆盖用户手打的内容
    text.value = text.value.trim() ? `${text.value.trim()} ${recognized}` : recognized
  } catch (error) {
    ElMessage.warning(errorMessage(error, '语音识别失败，请重试'))
  } finally {
    transcribing.value = false
  }
}

/**
 * 上传文件名：扩展名决定上游按什么容器解码，必须与录音 MIME 一致。
 *
 * @param audio 录音 Blob
 * @return 带扩展名的文件名
 */
function recordingFileName(audio: Blob): string {
  const type = audio.type.toLowerCase()
  if (type.includes('wav')) {
    return 'voice.wav'
  }
  if (type.includes('mpeg') || type.includes('mp3')) {
    return 'voice.mp3'
  }
  if (type.includes('mp4') || type.includes('m4a')) {
    return 'voice.m4a'
  }
  if (type.includes('ogg')) {
    return 'voice.ogg'
  }
  return 'voice.webm'
}

/**
 * 朗读播放器：同一条回答支持 播放 / 暂停 / 继续 / 停止。
 * 绑定 TTS 走模型音频；未绑定或合成失败自动降级浏览器朗读。
 */
const {
  activeKey: speakingAnswerKey,
  state: speechState,
  toggle: toggleSpeak,
  stop: stopSpeech
} = useSpeechPlayer({
  getAgentId: () => props.agentId,
  ttsEnabled: () => props.ttsEnabled === true,
  // 非打断轻提示：合成失败降级、浏览器不支持等，不弹红色报错
  onNotice: (message) => ElMessage.info(message)
})

/** 朗读按钮 Hover 说明：未绑定也能读，只是换浏览器引擎 */
const speakHint = computed(() =>
  props.ttsEnabled === true ? '使用 Agent 绑定的语音合成模型朗读' : '未绑定语音合成模型，将使用浏览器朗读'
)

/**
 * 自动朗读开关（喇叭）。默认关闭：进入页面不会突然出声。
 * 打开只影响「之后」完成的回答；会话历史与切换会话回放的旧回答一律不读。
 */
const autoSpeak = ref(false)

/**
 * 已经处理过的回答键。只用来防重复朗读，不参与「能不能读」的判断。
 */
const autoSpoken = new Set<string>()

/**
 * 本轮真正在本页跑过（出现过流式/待批准）的助手轮 key。
 *
 * <p>自动朗读只认这批轮次：历史回放、切换会话、刷新后续订拿到的旧回答从没在这里流过，
 * 因此绝不会被自动念出来。这也解决了「打开开关立刻念起历史记录」的问题——
 * 开关只影响此刻之后完成的回答。
 */
const liveTurns = new Set<string>()

/** 喇叭开关的 Hover 说明：把边界讲清楚，避免用户以为打开会补读历史 */
const speakerHint = computed(() =>
  autoSpeak.value
    ? '自动朗读已开启：只朗读之后完成的最终回答，点击关闭并停止当前朗读'
    : '自动朗读已关闭：点击开启，之后完成的最终回答自动朗读（不会补读历史）'
)

/**
 * 打开 / 关闭自动朗读。
 *
 * <p>打开不补读任何已有回答：历史记录不是用户此刻想听的内容，
 * 一开开关就念起来会被当成 bug（也确实打断了用户）。
 * 关闭时立刻停掉正在读的那一条，不等它读完。
 */
function toggleAutoSpeak(): void {
  autoSpeak.value = !autoSpeak.value
  if (!autoSpeak.value) {
    stopSpeech()
    return
  }
  // 打开时给一句反馈：否则用户看到按钮变色却听不到声音，会以为没生效。
  // 只有确实有回答正在生成时才不说——那条一会自己会读出来。
  if (!hasStreamingTurn()) {
    ElMessage.info('自动朗读已开启，之后完成的回答会自动朗读')
  }
}

/**
 * 当前是否有回答正在生成 / 等待批准。用于开关反馈的文案取舍。
 *
 * @return true 表示还有一轮在跑
 */
function hasStreamingTurn(): boolean {
  return props.turns.some(
    (turn) => turn.role === 'assistant' && (turn.status === 'streaming' || turn.planPending === true)
  )
}

/**
 * @param key 回答键
 * @return 该条是否正在播放/准备中/暂停
 */
function isSpeaking(key: string): boolean {
  return speakingAnswerKey.value === key
}

/**
 * @param key 回答键
 * @return 该条是否还在等模型合成
 */
function isPreparing(key: string): boolean {
  return speakingAnswerKey.value === key && speechState.value === 'preparing'
}

/**
 * @param key 回答键
 * @return 该条是否正在出声（暂停不算）
 */
function isPlaying(key: string): boolean {
  return speakingAnswerKey.value === key && speechState.value === 'playing'
}

/**
 * 单条朗读按钮的说明。图标按钮没有可见文字，含义全靠 tooltip 与 aria-label 交代。
 *
 * @param key 回答键
 * @return 中文说明
 */
function speakHintOf(key: string): string {
  if (speakingAnswerKey.value === key) {
    if (speechState.value === 'preparing') {
      return '正在合成语音，点击取消'
    }
    return speechState.value === 'paused' ? '继续朗读这条回答' : '暂停朗读'
  }
  return speakHint.value
}

/**
 * 一条「已完成的最终回答」。自动朗读只认这个结构：
 * 计划流用终答，非计划流用最后一段 assistant 块（与页面渲染口径一致）。
 */
interface SpeakTarget {
  key: string
  text: string
}

/**
 * 取一条助手轮「最终回答」的朗读键与原文。计划流用终答，非计划流用最后一段 assistant 块
 * （与页面渲染口径一致）；没有正文时返回 null。
 *
 * @param turn 助手轮
 * @return 回答键与原文；无可读内容时为 null
 */
function answerOf(turn: ChatTurn): SpeakTarget | null {
  if (isPlanFlow(turn)) {
    const answer = turnSections(turn.blocks).answer
    return answer ? { key: planAnswerKey(turn), text: answer } : null
  }
  // 非计划流：一段回答可能被拆成多块，只读最后一段正文，避免一段话被念两遍
  let last: SpeakTarget | null = null
  for (const block of turn.blocks) {
    if (block.kind === 'assistant' && block.text) {
      last = { key: blockAnswerKey(turn, block), text: block.text }
    }
  }
  return last
}

/**
 * 自动朗读的唯一入口：先给「本页真的在跑」的轮次记账，再只读刚完成的那些。
 *
 * <p>为什么不用「所有已完成回答」：会话历史、切换会话、刷新后续订回放都会把旧回答塞进
 * props.turns，按旧口径会在打开开关时把历史念一遍。这里以「曾在本页流式出现过」为闸，
 * 历史轮从未流过，自然不会被读。
 *
 * <p>入册与朗读解耦：开关关闭期间完成的回答也记进 autoSpoken，之后再打开不会补念。
 */
function flushAutoSpeak(): void {
  for (const turn of props.turns) {
    if (turn.role !== 'assistant') {
      continue
    }
    // 正在生成 / 等待批准：先记账，等它真正结束那一拍再读
    if (turn.status === 'streaming' || turn.planPending) {
      liveTurns.add(turn.key)
      continue
    }
    // 历史回放：本页从没见过它流过，不读、也不记账（与开关无关）
    if (!liveTurns.has(turn.key)) {
      continue
    }
    const target = answerOf(turn)
    if (!target || autoSpoken.has(target.key)) {
      continue
    }
    autoSpoken.add(target.key)
    if (!autoSpeak.value) {
      continue
    }
    // 与手动朗读共用同一个播放器：新一条会顶掉上一条，不会叠着念
    void toggleSpeak(target.key, target.text)
  }
}

/**
 * 自动朗读的最小依赖签名。
 *
 * <p>只收集「哪些助手轮存在、是否仍在进行 / 待批准」这几个浅字段：
 * 流式增量只改 blocks 里的正文，不会让签名变化，因此不再像 deep 监听那样
 * 每来一个 delta 就深遍历整棵 turns（历史轮越多越贵）。
 */
const speakSignature = computed(() =>
  props.turns
    .filter((turn) => turn.role === 'assistant')
    .map((turn) => `${turn.key}|${turn.status}|${turn.planPending === true ? 1 : 0}`)
    .join(';')
)

/**
 * 自动朗读的触发点：助手轮进入流式 / 结束 / 待批准解除时扫一遍。
 * 只在「回答已结束」时才会命中，因此流式增量不会误读半句。
 */
watch(speakSignature, flushAutoSpeak)

/**
 * 候选池浅签名：形象与目标类型都来自候选池，池子一变缓存必须失效。
 */
const candidateSignature = computed(() =>
  (props.routeCandidates || [])
    .map((item) => `${item.agentName || ''}|${item.targetType || ''}|${item.avatarConfig ? 1 : 0}`)
    .join(',')
)

/**
 * 每轮消息流投影的「签名 + 缓存」闸。所属域：runtime（前端展示）。
 *
 * <p>签名与缓存都收在 {@link ParticipantFeedCache} 里：模板每轮至少要问三次「有没有分块」，
 * 没有闸就会按「轮 × 块」反复重扫。签名覆盖身份 / begin / 工作流状态，
 * 因此「原位补身份」这类不改变块数量与文字长度的变化也会让缓存失效。
 */
const feedCache = new ParticipantFeedCache()

/** 每帧算一遍每轮签名；投影按签名命中缓存（见 {@link ParticipantFeedCache}） */
const feedSignatures = computed(() =>
  feedCache.signatures(props.turns, (props.entryName || '').trim(), candidateSignature.value)
)

watch(
  () => props.turns,
  () => {
    feedCache.clear()
    // 切会话：上一份时间线的测量缓存 / 思考节拍状态都不再有效
    midBoxMeasuredLen.clear()
    openThinkKeys.clear()
  }
)

/**
 * 本轮是否已经有「这一步真的开始」的证据。
 *
 * <p>只认会产出参与者的真实事件：路由判定 / 思考 / 工具 / 正文 / 产物 / 报错。
 * 图片文字（vision）与计划块只属于素材与计划，不构成任何参与者已开始；
 * 用它们挂出入口块就是「预挂空壳」。
 *
 * @param blocks 本轮块
 * @return true 表示可以开始投影参与者
 */
function hasParticipantEvidence(blocks: AssistantBlock[]): boolean {
  return blocks.some(
    (block) =>
      block?.kind === 'route' ||
      block?.kind === 'reasoning' ||
      block?.kind === 'tool' ||
      block?.kind === 'assistant' ||
      block?.kind === 'file' ||
      block?.kind === 'error'
  )
}

/**
 * 工作台消息流：入口块、方案块、专家块同一种聊天块，按真实顺序排。
 *
 * <p>形象仍走 lookOf。拼接终答不挂到最后一位，也不再在块下面重复贴一整段。
 * 入口块名称取配置（{@link props.entryName}），投影层不写死对外别名。
 *
 * <p>**不预挂**：发送当下这一轮还没有任何真实事件，这里直接返回空，
 * 等入口判定 / 思考 / 工具 / 正文真正到达才出块——避免先弹「入口」再改标题和正文。
 *
 * @param turn 本轮助手消息
 * @return 参与者卡片；不是工作台、还没有真实事件或时间线为空时为空
 */
function participantFeedOf(turn: ChatTurn): ParticipantCard[] {
  if (!isWorkbench.value || !turn || !Array.isArray(turn.blocks)) {
    return []
  }
  if (!hasParticipantEvidence(turn.blocks)) {
    return []
  }
  const signature = feedSignatures.value.get(turn.key) || ''
  /*
   * 签名 / 缓存闸：签名覆盖参与者身份与 begin 占位标记，因此 begin 原位补身份后
   * 必然未命中缓存、重新投影——「有身份就保留 pending 壳」的判定在直播路径真正执行。
   * 投影再崩也只影响本轮：返回空数组让下面的逐块渲染接手，整页不会跟着变白。
   */
  return feedCache.project(turn, signature, () => {
    try {
      const cards = buildParticipantFeed({
        routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
        lanes: lanesOf(turn),
        blocks: turn.blocks,
        streaming: turn.status === 'streaming',
        entryName: props.entryName,
        fallbackName: assistantDisplayName(turn),
        // 入口身份：入口判定的推理按 id 认回入口块（位置口径之外的第二道保险）
        entryParticipantId: turn.entryParticipantId,
        // 续作轮没有入口判定：不去等入口报身份，专家自己的思考直接出块
        expectEntry: turn.continuation !== true,
        lookOf,
        descriptionOf
      })
      // 没有 begin 身份、只剩状态句的卡在收尾丢掉。框里的子卡同一口径，不能只滤根上那一层。
      return omitFinishedShells(cards, turn.status === 'streaming')
    } catch (error) {
      console.warn('[工作台消息流] 本轮分块投影失败，已回退到逐块渲染', error)
      // 记成空卡片：同一签名下不必每帧再抛一次
      feedCache.rememberEmpty(turn, signature)
      return []
    }
  })
}

/**
 * 方案框里要排的成员：直接子卡在前，汇总在最后。
 *
 * <p>子卡是投影按 begin 收好的，这里只决定框内顺序，不再重算谁属于哪个方案。
 *
 * @param card 当前这张卡
 * @return 框内成员；没有子卡也没有汇总时为空
 */
function schemeMembers(card: ParticipantCard): ParticipantCard[] {
  const children = Array.isArray(card.children) ? card.children : []
  return card.summary ? [...children, card.summary] : children
}

/**
 * 收尾时丢掉没有 begin 身份、只剩状态句的卡。直播保留，框里的子卡和汇总同一口径。
 *
 * @param cards     根卡或某一层子卡
 * @param streaming 本轮是否仍在流式输出
 * @return 还要画出来的卡
 */
function omitFinishedShells(cards: ParticipantCard[], streaming: boolean): ParticipantCard[] {
  if (streaming) {
    return cards
  }
  const kept: ParticipantCard[] = []
  for (const card of cards) {
    if (isFinishedStatusShell(card)) {
      continue
    }
    const children = card.children?.length ? omitFinishedShells(card.children, streaming) : card.children
    const summary = card.summary && !isFinishedStatusShell(card.summary) ? card.summary : undefined
    const childrenChanged = (card.children?.length || 0) !== (children?.length || 0)
    const summaryChanged = card.summary !== summary
    if (!childrenChanged && !summaryChanged) {
      kept.push(card)
      continue
    }
    kept.push({
      ...card,
      children: children?.length ? children : undefined,
      summary
    })
  }
  return kept
}

/**
 * 本轮消息流里是否已经有「专家 / 方案」块。
 *
 * <p>为什么单独判：只剩网关块时（方案还在跑、工作流只报一句、历史数据里只有判定），
 * 逐块的正文 / 报错 / 文件必须照常显示；一按「有分块就全交给分块」把正文藏光，
 * 用户看到的就是网关块下面一片空白。
 * 专家收进方案框之后仍算有参与者，不能只看根上那一层。
 *
 * @param turn 本轮助手消息
 * @return true 表示已有专家 / 方案 / 结论块，逐块渲染让位
 */
function participantFeedHasActor(turn: ChatTurn): boolean {
  return visitParticipantCards(participantFeedOf(turn)).some(
    (card) => card.kind === 'actor' || card.kind === 'conclusion'
  )
}

/**
 * 本轮是否已有聊天块（含网关块）。
 *
 * <p>与 {@link participantFeedHasActor} 的区别：这里连网关块也算。
 * 用于「行首大 avatar 是否让位」——网关块已经自带头像与名称，
 * 再在行首挂一个大头像就是同一轮出现两个身份。
 *
 * @param turn 本轮助手消息
 * @return true 表示消息流里已有任意聊天块
 */
function hasParticipantFeed(turn: ChatTurn): boolean {
  if (participantFeedOf(turn).length > 0) {
    return true
  }
  /*
   * 「等入口报身份」这一拍消息流里故意没有块（见 feedWaitsForEntry）。
   * 若此时回落到逐块渲染，行首会先挂上上一位专家的大头像——用户看到的还是「先闪一下专家」。
   * 这里把行首身份一并收起来，等入口块真正出现再画。
   */
  return feedWaitsForEntry({
    blocks: turn.blocks,
    routeTexts: turn.blocks.filter((block) => block.kind === 'route').map((block) => block.text),
    entryParticipantId: turn.entryParticipantId,
    expectEntry: turn.continuation !== true
  })
}

/** 工作流头像齿轮的线稿色：固定近黑，与消息流里的名称文字同色系，不走 lookOf 的彩色 */
const WORKFLOW_GEAR_COLOR = WORKFLOW_AVATAR_COLOR

/**
 * 是不是工作流块（执行卡片或它的占位块）。
 *
 * <p>口径集中在 `workbenchAvatar`：有卡片快照，或角色位就是「工作流 WF」。
 *
 * @param card 参与者卡片
 * @return true 表示这一块是工作流
 */
function isWorkflowCard(card: ParticipantCard): boolean {
  return isWorkflowAvatarCard(card)
}

/**
 * 参与者头像样式：圆圈中间白底，银圈由样式画；线稿色用该套形象色。
 *
 * <p>工作流是唯一例外：头像固定白底黑齿轮，不读 lookOf（有没有保存形象都不改）。
 *
 * @param card 参与者卡片
 * @return 内联样式
 */
function participantAvatarStyle(card: ParticipantCard) {
  // 圆圈中间固定白底，描边由样式画成银圈；形象色只留给字，不再铺成方座底色
  if (isWorkflowCard(card)) {
    return {
      background: '#fff',
      color: WORKFLOW_GEAR_COLOR
    }
  }
  return {
    background: '#fff',
    color: card.look?.initialColor || '#111827'
  }
}

/**
 * 该候选的简介 / 意图说明：判路由命中时后端 reason 只是内部标签，
 * 这是网关句唯一能引用的「为什么是它」事实依据（现网字段 `intentDesc`）。
 *
 * @param name 展示名
 * @return 简介；候选不在本会话或没有简介时为空串
 */
function descriptionOf(name: string): string {
  return (candidateOf(name)?.intentDesc || '').trim()
}

/**
 * 按展示名取候选项：用于目标类型与形象解析；对不上返回 undefined（按普通 Agent 处理）。
 *
 * @param name 展示名
 * @return 候选；空表示不在本会话候选里
 */
function candidateOf(name: string) {
  const wanted = (name || '').trim()
  if (!wanted) {
    return undefined
  }
  return (props.routeCandidates || []).find((item) => (item.agentName || '').trim() === wanted)
}

/**
 * 该名字的形象配置：先查入口候选，再查方案内部的子候选。
 *
 * <p>方案内部子专家不在入口候选池里，形象只能从入口方案那一行的 `children` 取；
 * 两者都没有返回 undefined，交给调用方决定兜底（方案用固定机器人，专家用首字）。
 *
 * @param name 展示名
 * @return 形象配置；未配置 / 不认识的参与者为 undefined
 */
function avatarConfigOf(name: string) {
  const top = candidateOf(name)
  if (top) {
    return top.avatarConfig || undefined
  }
  const wanted = (name || '').trim()
  if (!wanted) {
    return undefined
  }
  for (const option of props.routeCandidates || []) {
    const child = (option.children || []).find((item) => (item.agentName || '').trim() === wanted)
    if (child) {
      return child.avatarConfig || undefined
    }
  }
  return undefined
}

/**
 * 专家 / 方案形象：已配置的用配置，没配置（或配置认不出图形）的一律给固定机器人。
 *
 * <p>为什么不再退「首字方座」：时间线、方案块、子专家块都是「参与者」，只露一个汉字
 * 既分不清谁是谁，也和单兵专家的机器人形象不在同一套里。兜底用
 * {@link resolveFallbackRobotAvatar} 按名字稳定挑现成 8 套之一，刷新不会变脸；
 * 不新增上传链路，也不另造 MA 形象协议。
 *
 * @param name 展示名
 * @return 时间线内的小头像视图
 */
function lookOf(name: string) {
  const config = avatarConfigOf(name)
  let view = config ? resolveAgentAvatar(config, name) : null
  if (!view || (!view.imgSrc && !view.art)) {
    // 未配置 / 配置形态认不出：走固定机器人，避免「企」「制」这种首字方座
    view = resolveFallbackRobotAvatar(name)
  }
  return {
    initial: view.initial,
    iconSrc: view.imgSrc || undefined,
    art: view.art,
    color: view.background,
    initialColor: view.initialColor,
    custom: view.isCustom
  }
}

/**
 * 参与者名称右侧的角标：入口「对话入口」、方案「方案」、单兵专家「Agent」。
 *
 * <p>口径读配置与卡片类型，不写死展示名：
 * <ul>
 *   <li>入口块（kind=gateway）：名称是配置里的入口方案名（如 Router），角标恒为「对话入口」；</li>
 *   <li>方案（roleLabel=方案）：分流与框内汇总都标「方案」；</li>
 *   <li>整轮收口（roleLabel=最终产物）：标「最终产物」，排在全部方案卡之后；</li>
 *   <li>其余参与者（单兵专家 / 方案内部子专家）：标「Agent」，不允许空着。</li>
 * </ul>
 *
 * @param card 参与者卡片
 * @return 角标文案；不会返回空串
 */
function actorRoleBadge(card: ParticipantCard): string {
  if (card.kind === 'gateway') {
    return '对话入口'
  }
  if (card.roundDeliverable || card.roleLabel === '最终产物') {
    return '最终产物'
  }
  if (card.roleLabel === '方案') {
    return '方案'
  }
  // 工作流块：角标固定「工作流 WF」，与卡片里的执行流是同一件事
  if (card.workflow || card.roleLabel === WORKFLOW_ROLE_LABEL) {
    return WORKFLOW_ROLE_LABEL
  }
  return 'Agent'
}

/**
 * 这位参与者的思考折叠键。默认收起，点开才看思考和工具。
 *
 * @param card 参与者卡片
 * @return 折叠区段名
 */
function actorThinkPart(card: ParticipantCard): string {
  return `actor-think:${card.key}`
}

/**
 * 折叠区里要展示的说明行：网关判定句 / 方案分流句已经在内容框里出现过，
 * 不再进折叠（否则同一句出现两次）。
 *
 * @param card 参与者卡片
 * @return 折叠区说明行
 */
function actorFoldLines(card: ParticipantCard): string[] {
  const lines = Array.isArray(card.lines) ? card.lines : []
  if (card.kind === 'gateway' || card.roleLabel === '方案') {
    return []
  }
  return lines
}

/**
 * 内容框里的本步结果句：网关写判定（分发给谁），方案写分给谁。
 * 专家用自己的正文 / 报错 / 文件卡，不走这里（返回空数组）。
 *
 * @param card 参与者卡片
 * @return 可见结果行；没有时为空数组
 */
function actorResultLines(card: ParticipantCard): string[] {
  const lines = Array.isArray(card.lines) ? card.lines : []
  if (card.kind !== 'gateway' && card.roleLabel !== '方案') {
    return []
  }
  return lines
}

/**
 * 工作流卡片：状态徽标是否已完成（成功后描边与头部才转绿）。
 *
 * @param workflow 卡片视图
 * @return true 表示运行成功
 */
function wfCompleted(workflow: TrialWorkflowView, closed?: string): boolean {
  if (closed === 'done') {
    return true
  }
  return (workflow.status || '').toUpperCase() === 'SUCCESS'
}

/**
 * 工作流卡片：状态徽标文案。口径与后端 `WorkflowRunJson.statusText` 一致，
 * 未知状态按「运行中」处理，不伪造已完成。
 * <p>运行中只回纯文案「运行中」：模板里已有旋转 ⏳，文案再带沙漏会叠成两个图标。
 *
 * @param workflow 卡片视图
 * @return 形如「运行中」「✅ 已完成」
 */
function wfStatusText(workflow: TrialWorkflowView, closed?: string): string {
  // 这一位的终态先于快照状态：终态到了就停转，不等整轮、也不等节点快照改口
  if (closed === 'done') {
    return '✅ 已完成'
  }
  if (closed === 'failed') {
    return '❌ 运行失败'
  }
  if (closed === 'aborted') {
    return '⏹ 已取消'
  }
  const status = (workflow.status || '').toUpperCase()
  if (status === 'SUCCESS') {
    return '✅ 已完成'
  }
  if (status === 'FAILED') {
    return '❌ 运行失败'
  }
  if (status === 'CANCELLED') {
    return '⏹ 已取消'
  }
  if (status === 'PAUSED_HUMAN') {
    return '⏸ 等待人工处理'
  }
  return '运行中'
}

/**
 * 工作流卡片：状态徽标的样式类（运行中蓝、成功绿、失败红、等待橙）。
 *
 * @param workflow 卡片视图
 * @return 样式类名
 */
function wfStatusClass(workflow: TrialWorkflowView, closed?: string): string {
  if (closed === 'done') {
    return 'is-success'
  }
  if (closed === 'failed') {
    return 'is-failed'
  }
  if (closed === 'aborted') {
    return 'is-cancelled'
  }
  const status = (workflow.status || '').toUpperCase()
  if (status === 'SUCCESS') {
    return 'is-success'
  }
  if (status === 'FAILED') {
    return 'is-failed'
  }
  if (status === 'CANCELLED') {
    return 'is-cancelled'
  }
  if (status === 'PAUSED_HUMAN') {
    return 'is-waiting'
  }
  return 'is-running'
}

/**
 * 节点状态 → 时间轴样式：进行中（蓝点转圈）/ 已完成（绿勾）/ 失败（红叉）/ 未开始（灰点）。
 *
 * <p>只用后端给的真实状态，不在前端推算「下一步该亮了」。
 *
 * @param node 节点视图
 * @return done / active / failed / pending
 */
function wfStepState(node: TrialWorkflowNodeView): string {
  const status = (node.status || '').toUpperCase()
  if (status === 'SUCCESS') {
    return 'done'
  }
  if (status === 'RUNNING') {
    return 'active'
  }
  if (status === 'FAILED') {
    return 'failed'
  }
  return 'pending'
}

/**
 * 节点下方的一句说明：优先用后端已脱敏的 note，缺失时按真实状态给中性文案。
 *
 * @param node 节点视图
 * @return 非空说明
 */
function wfStepNote(node: TrialWorkflowNodeView): string {
  const note = (node.note || '').trim()
  if (note) {
    return note
  }
  const state = wfStepState(node)
  if (state === 'done') {
    return '已完成'
  }
  if (state === 'active') {
    return '执行中'
  }
  if (state === 'failed') {
    return '执行失败'
  }
  return '等待前置节点'
}

/**
 * 产物区里的文件：工作流节点产出的真实文件（没有就是空表）。
 *
 * @param card 参与者卡片
 * @return 文件列表
 */
function wfFilesOf(card: ParticipantCard): TrialFileView[] {
  return Array.isArray(card.files) ? card.files : []
}

/**
 * 产物区正文：终答文本与文件**并列**展示，不再「有文件就清空文字」。
 *
 * <p>以前的写法一见文件就把文字置空：文件先被错误地挂到工作流卡上时，
 * 用户看到「整段文字 → 闪成文件卡 → 方案跑完文件改挂走、文字又回来」。
 * 归属修好后，文件只在确实属于该工作流时才会出现在这里；即便如此，
 * 文字也不该被文件挤掉——二者是同一份结果的不同形态，同时展示即可。
 *
 * @param card 参与者卡片
 * @return 终答文本；没有时为空串
 */
function wfAnswerOf(card: ParticipantCard): string {
  return (card.workflow?.answer || '').trim()
}

/**
 * 所属方案的子块是否还没全部结束。为 true 时工作流正文先按四行展示，不整屏。
 *
 * <p>不看整轮 streaming。没有方案父块时，只看这张卡自己有没有终态。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return true 表示先按四行展示
 */
function wfHoldOf(turn: ChatTurn, card: ParticipantCard): boolean {
  return !schemeChildrenSettled(participantFeedOf(turn), card)
}

/**
 * 工作流结果区是否出现。
 *
 * <p>有正文或文件就出现。所属方案还没结束时也出现，只是正文限高四行，
 * 不再收成「已产出 N 段」那一行。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return true 表示画出结果区
 */
function wfOutputVisible(turn: ChatTurn, card: ParticipantCard): boolean {
  return workflowOutputState(
    card.workflow,
    wfAnswerOf(card),
    wfFilesOf(card).length,
    false,
    wfHoldOf(turn, card)
  ) !== 'hidden'
}

/**
 * 结果区副文案：有真实文件时补一句交付物说明；纯文本正文只靠「结果」小标题，避免叠两句。
 *
 * <p>运行中不补「执行完毕」：那时文件是中间产物，说「完毕」是假话。
 *
 * @param card 参与者卡片
 * @return 一行提示；无文件或仍在运行时为空串
 */
function wfOutputTip(card: ParticipantCard): string {
  if (!wfFilesOf(card).length) {
    return ''
  }
  const status = (card.workflow?.status || '').toUpperCase()
  // 收尾态才说「执行完毕」：运行中出现的文件属于中间产物
  return status === 'RUNNING' || status === 'CREATED' ? '流程产出文件：' : '流程执行完毕，为您生成交付物：'
}

/**
 * 节点上的工具胶囊列表：0 或 1 条（仅 TOOL 节点）。
 *
 * @param node 工作流卡节点
 * @return 可直接 v-for 的数组
 */
function wfNodeToolPills(node: TrialWorkflowNodeView) {
  const pill = workflowNodeToolPill(node)
  return pill ? [pill] : []
}

/**
 * 思考区里要展示的内容：只有推理正文，工具调用单独做胶囊、不堆进思考正文。
 *
 * <p>演示稿的思考框是一段连续文字；工具是它下面的独立胶囊。
 * 因此这里把 tool_call / tool_result 从推理文本里摘出去，避免同一件事出现两遍。
 *
 * @param card 参与者卡片
 * @return 推理文本；没有时为空
 */
function actorThinkingText(card: ParticipantCard): string {
  return cardViewOf(card).thinkingText
}

/**
 * 从参与者块里取工具胶囊，按工具名聚合。
 *
 * @param card 参与者卡片
 * @return 胶囊列表（按工具首次出现顺序）
 */
function actorToolPills(card: ParticipantCard): ToolPillView[] {
  return cardViewOf(card).pills
}

/** 调用名 → 工具项标题。目录还没到或没有权限时为空，胶囊退回「工具」而不是英文函数名。 */
const toolTitleByName = reactive<Record<string, string>>({})

/**
 * 拉一次工具项目录，只用已有的 title 字段。
 *
 * <p>失败（无权限、网络）只留空表，不在前端写死英文名对照。
 */
async function loadToolFaceTitles() {
  if (!isWorkbench.value) {
    return
  }
  try {
    const rows = await listAllItems()
    for (const row of rows) {
      const name = (row.name || '').trim()
      const title = (row.title || '').trim()
      if (name && title) {
        toolTitleByName[name] = title
      }
    }
  } catch {
    // 工作台账号不一定有工具目录权限：没有标题就不显示函数名
  }
}

/**
 * 胶囊上的一句。聚合仍按英文调用名，句子里只放中文名。
 *
 * @param pill 聚合后的胶囊
 * @return 如「已调用【知识库检索】」
 */
function toolPhrase(pill: ToolPillView): string {
  const face = `【${toolFaceName(pill.name, toolTitleByName)}】`
  if (pill.state === 'running') {
    const times = pill.total > 1 ? `（${pill.total}）` : ''
    return `正在调用${face}${times}…`
  }
  if (pill.state === 'done') {
    const times = pill.total > 1 ? `（${pill.total} 次）` : ''
    return `已调用${face}${times}`
  }
  const error = pill.firstError ? ` · ${pill.firstError}` : ''
  return `${face}：成功 ${pill.ok} / 失败 ${pill.failed}${error}`
}

/** 思考耗时的刷新时钟。只有本轮还在流式时才走表，回放不编造秒数。 */
const thoughtNow = ref(Date.now())
/** 这一位思考开始的时刻（只记本次页面上看见它还在跑的时候） */
const thinkStartedAt = new Map<string, number>()
/** 这一位结束后冻住的毫秒，避免停表后还跟着时钟涨 */
const thinkFrozenMs = new Map<string, number>()
let thoughtTimer = 0

/**
 * 思考行上的耗时。没在这次停留里看见它跑过，就不写秒数（回放没有逐步耗时）。
 *
 * @param turn 本轮
 * @param card 参与者
 * @return 毫秒；没有观察起点时为 null
 */
function observedThoughtMs(turn: ChatTurn, card: ParticipantCard): number | null {
  const key = `${turn.key}:${card.key}`
  const now = thoughtNow.value
  const running = turn.status === 'streaming' && card.pending === true
  if (running) {
    if (!thinkStartedAt.has(key)) {
      thinkStartedAt.set(key, now)
    }
    return Math.max(0, now - (thinkStartedAt.get(key) as number))
  }
  if (thinkFrozenMs.has(key)) {
    return thinkFrozenMs.get(key) as number
  }
  if (thinkStartedAt.has(key)) {
    const elapsed = Math.max(0, now - (thinkStartedAt.get(key) as number))
    thinkFrozenMs.set(key, elapsed)
    return elapsed
  }
  return null
}

/**
 * 折叠行文案。有真实耗时才带「(耗时 Xs)」。
 *
 * @param turn 本轮
 * @param card 参与者
 * @return 一行标题
 */
function thoughtToggleLabel(turn: ChatTurn, card: ParticipantCard): string {
  const elapsed = formatThoughtElapsed(observedThoughtMs(turn, card) ?? 0)
  return elapsed ? `思考与工具执行过程 (耗时 ${elapsed})` : '思考与工具执行过程'
}

/**
 * 思考行什么时候出现。
 *
 * <p>已经有推理就一直在。还在跑、结果还没落地时也先把这一行摆上，
 * 不等整轮结束才出现。结束后如果从头到尾没有推理，就不留空行。
 *
 * @param turn 本轮
 * @param card 参与者
 * @return true 表示画出折叠行
 */
function showThoughtToggle(turn: ChatTurn, card: ParticipantCard): boolean {
  if (actorHasThink(card)) {
    return true
  }
  return card.pending === true && turn.status === 'streaming' && !actorHasResult(card)
}

/**
 * 工作流结果区标题。完成后用演示稿那句，进行中不写成「已完毕」。
 *
 * @param card 参与者
 * @return 标题
 */
function wfResultTitle(card: ParticipantCard): string {
  const done = card.workflow ? wfCompleted(card.workflow, card.closed) : false
  return done ? '流程执行完毕，结果如下：' : '执行结果'
}

/**
 * 复制结果框里用户看见的正文（检索 JSON 已收成引用文本）。
 *
 * @param raw 卡片上的原文
 */
async function copyResult(raw: string) {
  const text = displayAnswer(raw)
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

/** 卡片派生视图：思考正文 + 工具胶囊（模板每块要问多次，只算一次） */
interface CardView {
  thinkingText: string
  pills: ToolPillView[]
}

/**
 * 卡片派生视图缓存。
 *
 * <p>为什么用 WeakMap 按卡片对象缓存：投影按内容签名重建卡片（签名变了就是新对象），
 * 因此缓存天然随投影失效，不需要额外的签名比对；而同一帧内模板对同一块会问 4 次以上
 * 「思考文本 / 工具胶囊」，每次都要遍历全部步骤并拼接全文，长思考 + 多工具时是纯浪费。
 */
const cardViewMemo = new WeakMap<ParticipantCard, CardView>()

/**
 * 取（或首次计算）一张卡片的派生视图。
 *
 * @param card 参与者卡片
 * @return 思考正文与工具胶囊
 */
function cardViewOf(card: ParticipantCard): CardView {
  const hit = cardViewMemo.get(card)
  if (hit) {
    return hit
  }
  const view: CardView = {
    thinkingText: buildThinkingText(card),
    pills: aggregateToolPills(card.key, Array.isArray(card.steps) ? card.steps : [])
  }
  cardViewMemo.set(card, view)
  return view
}

/**
 * 计算一张卡片的思考正文（只有推理，不含工具调用）。
 *
 * @param card 参与者卡片
 * @return 推理文本；没有时为空
 */
function buildThinkingText(card: ParticipantCard): string {
  const steps = Array.isArray(card.steps) ? card.steps : []
  const parts: string[] = []
  for (const step of steps) {
    if (step.type !== 'reasoning') {
      continue
    }
    const text = (step.text || '').trim()
    if (text) {
      parts.push(text)
    }
  }
  // 专家块还会把依据句放进 lines；网关 / 方案的话已经在内容框里，不重复
  return [...actorFoldLines(card), ...parts].join('\n').trim()
}

/**
 * 工具胶囊条目：同一参与者、同一工具名的**所有调用**合成一条。
 *
 * <p>聚合规则收在 {@link aggregateToolPills}（纯函数，有单测）：
 * 同名并行只出一条、计数正确、首错优先且不被熔断文案覆盖。
 */
type ToolPillView = ReturnType<typeof aggregateToolPills>[number]

/**
 * 工具详情是否展开（键 = 胶囊 key）。
 *
 * <p>成功胶囊可点开看入参出参；失败胶囊原因已写在胶囊上，点开可看细节。
 */
const toolDetailOpen = reactive<Record<string, boolean>>({})

/**
 * 切换某条工具胶囊的详情展开态。
 *
 * @param key 胶囊 key
 */
function toggleToolDetail(key: string) {
  toolDetailOpen[key] = toolDetailOpen[key] !== true
}

/**
 * 正文节点 / 思考文字的 DOM 句柄。键为「轮 key:参与者 key」。
 *
 * <p>正文限高四行，展开看全文，不在框里跟滚。思考文字单独限高四行并在框内滚动，
 * 所以滚动句柄登记在思考文字上，不登记在带标题的整框上。
 */
const answerTextEls = new Map<string, HTMLElement>()
const actorFoldEls = new Map<string, HTMLElement>()

/**
 * 正文节点 ref：用来量这段文字有没有超过四行。文件行不进这个节点。
 *
 * @param turnKey 本轮 key
 * @param cardKey 参与者 key
 * @return Vue 的 ref 回调
 */
function answerTextRef(turnKey: string, cardKey: string) {
  const key = `${turnKey}:${cardKey}`
  return (el: unknown) => {
    if (el instanceof HTMLElement) {
      answerTextEls.set(key, el)
    } else {
      answerTextEls.delete(key)
    }
  }
}

/**
 * 思考文字 ref：滚动发生在这四行文字里，标题行不跟着滚。
 *
 * @param turnKey 本轮 key
 * @param cardKey 参与者 key
 * @return Vue 的 ref 回调
 */
function actorFoldRef(turnKey: string, cardKey: string) {
  const key = `${turnKey}:${cardKey}`
  return (el: unknown) => {
    if (el instanceof HTMLElement) {
      const thinking = el.querySelector('.wb-actor-thinking')
      actorFoldEls.set(key, thinking instanceof HTMLElement ? thinking : el)
    } else {
      actorFoldEls.delete(key)
    }
  }
}

/** 需要在本次更新后执行「中间框跟滚」的轮次；同一帧只处理一次 */
const pendingScrollKeys = new Set<string>()
/** 上一帧仍处于「思考这一拍」的块（键＝轮:参与者）；用于识别刚收起的那一拍 */
const openThinkKeys = new Set<string>()
/** 已排队的跟滚帧号；0 表示当前没有排队 */
let actorScrollFrame = 0

/**
 * 把仍在流式中的思考文字滚到底部（跟看最后一行）。
 *
 * 思考文字和结果框都在四行里跟到最新一行。
 * 用户往上翻过（离开底部超过一行）就不再拽回去。
 */
function tickMidProcessScroll() {
  actorScrollFrame = 0
  for (const key of pendingScrollKeys) {
    const fold = actorFoldEls.get(key)
    if (fold) {
      followScrollable(fold)
    }
    const answer = answerTextEls.get(key)
    // 展开后的全文不再被四行框拽到底；收在四行里时才跟最新一行
    if (answer && answer.classList.contains('is-clamped')) {
      followScrollable(answer)
    }
  }
  pendingScrollKeys.clear()
}

/**
 * 把可滚动元素滚到底，但只在用户仍停在底部附近时执行。
 *
 * @param el 可滚动容器
 */
function followScrollable(el: HTMLElement) {
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  // 离开底部超过一行的距离，视为用户正在回看历史，不再拽回底部
  if (distance > 48) {
    return
  }
  el.scrollTop = el.scrollHeight
}

/**
 * 登记所有仍在流式中的轮次，等下一帧统一跟滚。
 *
 * <p>由 {@link onUpdated} 在增量真正写进 DOM 之后调用：一帧最多读一次 scrollHeight、
 * 写一次 scrollTop，不随每个 delta 抖动。
 *
 * <p>同时识别「思考这一拍刚结束」的块（展开 → 收起）：折叠动画开始前先把内框滚到底，
 * 让收起时看到的是最后一行，而不是被裁在中段；也避免内外两层滚动在同一帧互相拽位置。
 */
function requestMidProcessScroll() {
  if (!isWorkbench.value) {
    return
  }
  let dirty = false
  for (const turn of props.turns) {
    if (turn.role !== 'assistant' || turn.status !== 'streaming') {
      continue
    }
    for (const card of participantFeedOf(turn)) {
      const key = `${turn.key}:${card.key}`
      if (actorThinkOpenByDefault(turn, card)) {
        openThinkKeys.add(key)
      } else if (openThinkKeys.delete(key)) {
        // 刚结束第一拍：折叠前把内框滚到底（一次布局读，每个块每拍只发生一次）
        const fold = actorFoldEls.get(key)
        if (fold) {
          fold.scrollTop = fold.scrollHeight
        }
      }
      // 只跟中间过程：最终结果不限高，不需要跟滚
      if (!actorBoxMidProcess(turn, card)) {
        continue
      }
      pendingScrollKeys.add(key)
      dirty = true
    }
  }
  if (!dirty || actorScrollFrame) {
    return
  }
  actorScrollFrame = requestAnimationFrame(tickMidProcessScroll)
}

/**
 * 正文是否被用户展开（键 = 「轮 key:参与者 key」）。
 *
 * <p>中间过程正文默认四行。点「展开全文」去掉限高，点「收起」恢复。
 * 思考框不走这个开关，它在四行里滚动。
 */
const midBoxExpanded = reactive<Record<string, boolean>>({})
/**
 * 中间过程框被四行截断的判定缓存（键 = 「轮 key:参与者 key」）。
 *
 * <p>只在渲染后实测一次 scrollHeight / clientHeight，模板不读布局：
 * 流式期每帧在模板里读布局会强制同步排版，长会话会明显掉帧。
 * 展开后仍记为「本可截断」，这样「收起」入口不会因为框已放开而消失。
 */
const midBoxOverflow = reactive<Record<string, boolean>>({})

/**
 * 正文单行高度（px）：与 `.chat.is-workbench .md.answer` 的
 * `font-size: 14px` × `line-height: 1.6` 一致。
 *
 * <p>为什么用常量而不是 `getComputedStyle(el).lineHeight`：读计算样式会强制同步样式计算，
 * 流式期每帧对每个块各读一次，是和 `scrollHeight` 一起把主线程拖住的主要原因。
 * 这两处样式属于界面固定口径，改字号时同步改这里（下方 CSS 注释也留了对照）。
 */
const MID_BOX_LINE_HEIGHT_PX = 14 * 1.6
/** 两次实测之间的最小间隔（毫秒）：流式期降频，攒到下一个间隔再统一量 */
const MID_BOX_MEASURE_MIN_GAP_MS = 80
/** 每个框上次测量时的内容长度：长度没变就不可能溢出，直接跳过读布局 */
const midBoxMeasuredLen = new Map<string, number>()
/** 上一次真正测量的时间戳（毫秒） */
let midBoxMeasuredAt = 0

/**
 * 量一次每段正文是否超过四行，决定要不要给「展开全文」。
 *
 * <p>只量正文节点，不把文件卡、终态说明算进去。文件在框外，说明也在框外，
 * 它们都很矮，若和正文量在一起，点展开不会改变看见的高度。
 * 展开之后 max-height 是 none，仍用「四行行高」和 scrollHeight 比，收起入口才不会丢。
 *
 * <p>降频与去重（P0-B9）：
 * <ul>
 *   <li>行高 / 内边距用常量，不再每帧 `getComputedStyle`；</li>
 *   <li>内容长度没变的框直接跳过，不读 `scrollHeight`；</li>
 *   <li>流式期两次实测至少间隔 {@link MID_BOX_MEASURE_MIN_GAP_MS}，
 *       结束态（非流式）不节流，保证「展开全文」在终答落地那一帧就能出；</li>
 *   <li>判定值不变就不写回响应式，避免额外一次重渲染。</li>
 * </ul>
 */
function measureMidBoxOverflow() {
  if (!isWorkbench.value) {
    return
  }
  const now = Date.now()
  const throttled = now - midBoxMeasuredAt < MID_BOX_MEASURE_MIN_GAP_MS
  // 四行正文：限高加在文字上，内边距在外层气泡，不计入这四行
  const limit = BOX_MAX_LINES * MID_BOX_LINE_HEIGHT_PX
  for (const turn of props.turns) {
    if (turn.role !== 'assistant') {
      continue
    }
    for (const card of participantFeedOf(turn)) {
      const key = `${turn.key}:${card.key}`
      const el = answerTextEls.get(key)
      // 最终结果不限高；没有正文节点也不给展开按钮
      if (!bodyTextLimited(turn, card)) {
        if (midBoxOverflow[key]) {
          midBoxOverflow[key] = false
        }
        continue
      }
      if (!el) {
        continue
      }
      // 内容长度没变：上一拍量过且结论没变，不必再读一次布局
      const contentLength = boxContentLength(card)
      if (midBoxMeasuredLen.get(key) === contentLength) {
        continue
      }
      // 流式期降频：同一帧量所有块代价最高，攒到下一个间隔统一量
      if (throttled && turn.status === 'streaming') {
        continue
      }
      // 四行按文字节点的 scrollHeight 和四行行高比，不读整只气泡
      const overflowing = el.scrollHeight - limit > 2
      midBoxMeasuredLen.set(key, contentLength)
      if (midBoxOverflow[key] !== overflowing) {
        midBoxOverflow[key] = overflowing
      }
    }
  }
  if (!throttled) {
    midBoxMeasuredAt = now
  }
}

/**
 * 正文长度：只用来判断「文字有没有变」，不是展示值。文件名不算，避免文件一到就误判成正文变了。
 *
 * @param card 参与者卡片
 * @return 正文字符数
 */
function boxContentLength(card: ParticipantCard): number {
  if (card.workflow) {
    return (card.workflow.answer || '').length
  }
  return (card.answer || '').length
}

/**
 * 这一段正文现在该出「展开全文」还是「收起」。
 *
 * <p>没超过四行时是 none：按钮不出现，避免点了高度不变。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return none / expand / collapse
 */
function bodyExpandActionOf(turn: ChatTurn, card: ParticipantCard) {
  const key = `${turn.key}:${card.key}`
  return bodyExpandAction(
    bodyTextLimited(turn, card),
    midBoxExpanded[key] === true,
    midBoxOverflow[key] === true
  )
}

/**
 * 展开 / 收起一个中间过程框：只影响该框，不改其它参与者。
 *
 * @param turnKey 本轮 key
 * @param cardKey 参与者 key
 */
function toggleMidBoxExpand(turnKey: string, cardKey: string) {
  const key = `${turnKey}:${cardKey}`
  // 限高加在正文节点的 is-clamped 上：这里一翻转，四行和全文就会换过来
  midBoxExpanded[key] = midBoxExpanded[key] !== true
}

/**
 * 是否有可折叠的思考。空壳和状态句已在投影里去掉，这里不再补「没有额外的思考记录」。
 *
 * <p>判定口径是「这一块**真的有推理文字**」：思考框里只画推理，
 * 工具调用在它下面单独做胶囊。因此只有工具、没有推理的步骤不画思考框
 * （否则点开是空框）；判定 / 分流这一步的判定句与分流句在内容框里，也不算思考。
 *
 * @param card 参与者卡片
 * @return true 表示画出「查看思考过程与工具调用」
 */
function actorHasThink(card: ParticipantCard): boolean {
  return actorThinkingText(card).length > 0
}

/**
 * 这一块是否已经出现工具调用 / 工具结果。
 *
 * <p>只看过程事件本身：有 tool_call 或 tool_result 就说明工具这一拍已经开始，
 * 与它的成功 / 失败无关——失败也要等思考结束后才允许出红色胶囊。
 *
 * @param card 参与者卡片
 * @return true 表示已有工具事件
 */
function actorHasTool(card: ParticipantCard): boolean {
  const steps = Array.isArray(card.steps) ? card.steps : []
  return steps.some((step) => step.type === 'tool_call' || step.type === 'tool_result')
}

/**
 * 这一块的**真实结果**是否已经出现。
 *
 * <p>网关 / 方案（分流那条）的结果是内容框里那句判定句 / 分流句，写在 lines 里；
 * 专家与汇总的结果是正文 / 报错 / 产物。专家折叠区的「依据」行不算结果——
 * 它一直在，若算结果，思考框就永远不会展开。
 *
 * @param card 参与者卡片
 * @return true 表示已有真实结果
 */
function actorHasResult(card: ParticipantCard): boolean {
  if (card.kind === 'gateway' || (card.kind === 'actor' && card.roleLabel === '方案')) {
    return actorResultLines(card).length > 0
  }
  const answer = (card.answer || '').trim()
  const errors = Array.isArray(card.errors) ? card.errors : []
  const files = Array.isArray(card.files) ? card.files : []
  return !!answer || errors.length > 0 || files.length > 0
}

/**
 * 思考区是否还在流式（＝这一步仍处在「三拍」的第一拍）。
 *
 * <p>三拍时序的唯一判定入口：思考框展开跟滚、工具胶囊是否出现、结果框是否出现，
 * 全部读这一个函数，避免三处各判各的、出现「思考还在滚，胶囊先冒出来」。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return true 表示仍在第一拍
 */
function actorThinkOpenByDefault(turn: ChatTurn, card: ParticipantCard): boolean {
  return isThinkOpenByDefault(
    card,
    turn.status === 'streaming',
    card.pending,
    actorHasTool(card),
    actorHasResult(card)
  )
}

/**
 * 这一步是否仍在思考（且真有可见推理）：只有此时才禁止出工具胶囊与结果框。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return true 表示思考框正在流式输出
 */
function actorThinkingBeat(turn: ChatTurn, card: ParticipantCard): boolean {
  return actorHasThink(card) && actorThinkOpenByDefault(turn, card)
}

/**
 * 这条工具胶囊是否可展开详情：有实际内容才给入口，避免点开是空块。
 *
 * @param pill 工具胶囊
 * @return true 表示可点开
 */
function toolHasDetail(pill: ToolPillView): boolean {
  return (pill.detail || '').trim().length > 0
}

/**
 * 内容框是否处于「中间过程」形态：最高四行、框内滚动。
 *
 * <p>口径收在 {@link isMidProcessBox} 里（纯函数，有单测）：
 * 流式过程中所有块都限高；本轮结束后只有整轮**最后一条**（`card.isFinal`）放开、
 * 完整展开，其余已经写完的块仍保持四行——不要把某位子专家放大成终局。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return true 表示需要限高
 */
function actorBoxMidProcess(turn: ChatTurn, card: ParticipantCard): boolean {
  return isMidProcessBox(card, turn.status === 'streaming')
}

/**
 * 这段正文是否按四行截断。
 *
 * <p>入口、专家、分流停在四行里滚。综答（方案汇总）不截断，流式时正文往下长。
 * 用户点了展开之后，限高类名去掉，这里对仍限高的块返回 true，好让收回箭头留在页面上。
 *
 * @param turn 本轮助手消息（签名与调用处对齐，限高不看整轮状态）
 * @param card 参与者卡片
 * @return true 表示这段文字处在四行口径下
 */
function bodyTextLimited(turn: ChatTurn, card: ParticipantCard): boolean {
  const text = card.workflow ? wfAnswerOf(card) : (card.answer || '')
  if (!text.trim()) {
    return false
  }
  // 综答随流式全文往下长。入口、专家、分流仍停在四行里滚。
  if (isUnlimitedAnswerBox(card)) {
    return false
  }
  return true
}

/**
 * 正文节点的限高类名。展开时去掉 is-clamped，高度立刻放开；收起时加回来。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return 类名表
 */
function bodyTextClass(turn: ChatTurn, card: ParticipantCard): Record<string, boolean> {
  const key = `${turn.key}:${card.key}`
  const expanded = midBoxExpanded[key] === true
  return {
    'is-clamped': bodyTextLimited(turn, card) && !expanded
  }
}

/**
 * 内容框是否要画：必须已有本步**真实结果**，且思考这一拍已经结束。
 *
 * <p>不再用 `pending` 兜底：进行中挂一块空结果框（还带闪烁光标），正是要修掉的
 * 「结果框提前出现」。网关与方案的话就是那句结果，必须有框；专家有正文 / 报错 / 产物才出框。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return true 表示画出内容框
 */
function actorHasBubble(turn: ChatTurn, card: ParticipantCard): boolean {
  // 工作流块画的是执行卡片，不再套一层内容框（否则会出现「卡片 + 空框」两层）
  if (card.workflow) {
    return false
  }
  if (actorThinkingBeat(turn, card)) {
    return false
  }
  const errors = Array.isArray(card.errors) ? card.errors : []
  return !!(
    card.answer ||
    actorResultLines(card).length ||
    errors.length ||
    // 文件在框外单独一行，不因为有文件就再套一只空正文框
    // 只有一组可点选项、没有正文的块也是「本步结果」：不把它判成空壳，否则用户看不到按钮
    (card.choice && card.choice.options.length)
  )
}

/**
 * 整轮最后一条助手消息的 key。
 *
 * <p>为什么只认最后一条：选项代表「当前这一步还等你拍板」。用户答完之后，
 * 那一组选项就已经过期——历史轮的按钮若还能点，会把已经翻页的上下文再续作一遍。
 *
 * @return 最后一条助手消息的 key；没有助手消息时为空串
 */
const lastAssistantTurnKey = computed(() => {
  for (let index = props.turns.length - 1; index >= 0; index -= 1) {
    const turn = props.turns[index]
    if (turn?.role === 'assistant') {
      return turn.key
    }
  }
  return ''
})

/**
 * 这个选项块现在能不能点。
 *
 * <p>四个前提缺一不可：本轮已停止流式、没有正在发送、当前允许发送、且它就是最后一条助手消息。
 * 正在生成时不放开按钮：此时后端的「最后一条 assistant 事件」还在变，点了会续作到半截上下文。
 *
 * @param turn 选项所在那轮助手消息
 * @return true 表示按钮可点
 */
function choiceClickable(turn: ChatTurn): boolean {
  if (!turn || turn.key !== lastAssistantTurnKey.value) {
    return false
  }
  return !props.sending && props.canSend && turn.status !== 'streaming'
}

/**
 * 点可点选项 → 交给外层走断点续作。
 *
 * <p>本组件不发请求也不改轮次：续作会新增一条用户轮（选项文案）与一条新的助手轮，
 * 由外层统一维护。这里只把 optionId 与文案抛出去。
 *
 * @param turn   选项所在那轮助手消息（仅用于可点性复核）
 * @param option 被点的选项
 */
function onChoice(turn: ChatTurn, option: ParticipantChoiceOption) {
  if (!choiceClickable(turn) || !option?.id) {
    return
  }
  emit('choice-selected', option.id, option.label)
}

/**
 * 生成结束 → 延迟约 500ms 自动收起。
 *
 * <p>为什么留这 500ms：终答最后一个字刚落地就立刻收掉，用户来不及看到最后一条思考；
 * 留半秒既能看到收尾，又不会一直占着版面。
 *
 * @param key 本轮 key
 */
function scheduleAutoCollapse(key: string) {
  if (autoCollapseTimers.has(key)) {
    return
  }
  const timer = window.setTimeout(() => {
    autoCollapseTimers.delete(key)
    // 用户已手动操作过就不自动收起，避免和用户抢方向盘
    if (typeof userThinkOpen[key] !== 'boolean') {
      autoCollapsedThink[key] = true
    }
  }, AUTO_COLLAPSE_DELAY_MS)
  autoCollapseTimers.set(key, timer)
}

/**
 * 自动收起的最小依赖签名：只收「哪些助手轮存在、是否仍在流式」两个浅字段。
 * 流式增量只改块正文，不会让签名变化，因此不会每个 delta 都重扫。
 */
const thinkSignature = computed(() =>
  props.turns
    .filter((turn) => turn.role === 'assistant')
    .map((turn) => `${turn.key}|${turn.status}`)
    .join(';')
)

/** 本次挂载期间真正见过「流式中」的轮次；只有它们才配 500ms 延迟收起 */
const seenStreamingTurns = new Set<string>()

/**
 * 记录上一帧仍是「流式中」的轮次，用于识别「刚结束」这一拍。
 *
 * <p>结束瞬间要做两件事：把内容框滚回第一行，再取消中间过程的高度限制。
 * 两件事必须同一拍处理——先滚回顶部再放开高度，用户才会从第一行完整读，
 * 而不是停在四行窗口的底部看一个被截断的尾巴。
 */
const wasStreamingTurns = new Set<string>()

/** 待处理「刚结束」的轮次；与跟滚一样合批到下一帧 */
const pendingFinishKeys = new Set<string>()
/** 结束复位帧号；0 表示没有排队 */
let finishFrame = 0

/**
 * 流式结束时把思考文字滚回第一行。
 *
 * <p>正文不再在框内滚动，所以这里不碰正文节点。
 *
 * @param key 「轮 key:参与者 key」
 */
function scrollThinkToTop(key: string) {
  const fold = actorFoldEls.get(key)
  if (fold) {
    fold.scrollTop = 0
  }
}

/**
 * 把刚结束的轮次里的思考文字滚回顶部。
 *
 * <p>最终卡的正文限高由投影的 isFinal 撤掉，这里不改样式。
 */
function tickFinishReset() {
  finishFrame = 0
  for (const key of pendingFinishKeys) {
    scrollThinkToTop(key)
  }
  pendingFinishKeys.clear()
}

/**
 * 识别「流式刚结束」的轮次：登记它们的全部内容框 / 思考区，下一帧滚回第一行。
 *
 * @param turn 本轮助手消息
 */
function scheduleFinishReset(turn: ChatTurn) {
  if (!isWorkbench.value) {
    return
  }
  for (const card of participantFeedOf(turn)) {
    pendingFinishKeys.add(`${turn.key}:${card.key}`)
  }
  if (!pendingFinishKeys.size || finishFrame) {
    return
  }
  finishFrame = requestAnimationFrame(tickFinishReset)
}

/**
 * 生成结束 → 排一次自动收起；重新进入流式则撤销收起，让面板再次展开。
 * 只作用于工作台皮肤，且只对真有过程内容的轮次生效。
 * 历史回放（从未见过流式）直接收起，避免刷新后满屏展开的思考面板。
 */
watch(thinkSignature, () => {
  if (!isWorkbench.value) {
    return
  }
  for (const turn of props.turns) {
    if (turn.role !== 'assistant') {
      continue
    }
    if (turn.status === 'streaming') {
      // 同一轮重新开跑（继续生成 / 批准后续跑）：撤销自动收起
      seenStreamingTurns.add(turn.key)
      wasStreamingTurns.add(turn.key)
      delete autoCollapsedThink[turn.key]
      continue
    }
    // 上一帧还在流、这一帧结束了：先滚回第一行，再放开高度限制（同一拍）
    if (wasStreamingTurns.has(turn.key)) {
      wasStreamingTurns.delete(turn.key)
      scheduleFinishReset(turn)
    }
    if (!hasProcess(turn)) {
      continue
    }
    if (seenStreamingTurns.has(turn.key)) {
      scheduleAutoCollapse(turn.key)
    } else {
      autoCollapsedThink[turn.key] = true
    }
  }
})

/**
 * 首次挂载时把历史轮次标记为已收起：回放会话不该一进来就铺开所有思考面板。
 * 只有真正在本次停留期间流式过的轮次才会在结束后延迟收起。
 */
onMounted(() => {
  if (!isWorkbench.value) {
    return
  }
  for (const turn of props.turns) {
    if (turn.role === 'assistant' && turn.status !== 'streaming' && hasProcess(turn)) {
      autoCollapsedThink[turn.key] = true
    }
  }
  void loadToolFaceTitles()
})

/**
 * 只有还有人在跑时才刷新思考行上的耗时。回放没有流式轮次，不启动这只表。
 */
watch(
  () =>
    props.pageActive !== false &&
    props.turns.some((turn) => turn.role === 'assistant' && turn.status === 'streaming'),
  (live) => {
    if (live && !thoughtTimer) {
      thoughtNow.value = Date.now()
      thoughtTimer = window.setInterval(() => {
        thoughtNow.value = Date.now()
      }, 400)
      return
    }
    if (!live && thoughtTimer) {
      window.clearInterval(thoughtTimer)
      thoughtTimer = 0
      // 本轮真结束才补一次时刻。只是页签失活时不写，避免后台再触发一轮渲染
      if (props.pageActive !== false) {
        thoughtNow.value = Date.now()
      }
    }
  },
  { immediate: true }
)

/**
 * 兜底触发：本轮发送结束（sending 由 true 变 false）后再扫一次。
 * 有些链路的终态是先改 sending 再回填正文，单靠 turns 深监听会漏掉这一拍。
 */
watch(
  () => props.sending,
  (now) => {
    if (!now) {
      flushAutoSpeak()
    }
  }
)

/** 计划执行的终答键：与块渲染区分，避免两条回答共用同一个播放状态 */
function planAnswerKey(item: ChatTurn): string {
  return `plan:${item.key}`
}

/**
 * 非计划流程里 assistant 块的朗读键。
 *
 * @param item  助手轮
 * @param block 块本身
 * @return 唯一键
 */
function blockAnswerKey(item: ChatTurn, block: AssistantBlock): string {
  // 用块稳定 id 而不是下标：插入视觉块时下标会整体位移，播放状态会串到别的块上
  return `block:${item.key}:${block.id ?? block.kind}`
}

/**
 * @param error    捕获到的异常
 * @param fallback 兜底文案
 * @return 可展示的中文原因
 */
function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

// 切 Agent 必须停掉正在进行的朗读与录音：上一条回答的声音不能串到新 Agent 的会话里
watch(
  () => props.agentId,
  () => {
    stopSpeech()
    cancelVoiceRecording()
  }
)

/** 上传按钮是否可用：需要已绑定 VISION 模型，且未超张数上限 */
const canUpload = computed(() => props.visionEnabled === true && attachments.value.length < MAX_IMAGES)

/** 图标按钮的实际可用态：除模型与张数外，发送中也不允许再选图（与旧按钮 disabled 口径一致） */
const canUploadNow = computed(() => canUpload.value && !props.sending)

/** 按钮禁用时的 Hover 说明。已绑定时不弹（由 el-tooltip 的 disabled 控制） */
const uploadHint = computed(() =>
  props.visionEnabled === true
    ? `最多上传 ${MAX_IMAGES} 张图片`
    : '未配置视觉模型，无法识别图片'
)
/** 用户点标题后的折叠覆盖；未写入时用调用方给的默认态。键为「轮 key + 区段后缀」。 */
const foldOverride = reactive<Record<string, boolean>>({})
/** 长工具结果是否展开。 */
const resultOpen = reactive<Record<string, boolean>>({})

/** 工作台思考面板：用户显式点过的展开态（键 = 轮 key）；一旦写入，自动收起不再覆盖 */
const userThinkOpen = reactive<Record<string, boolean | undefined>>({})
/** 已自动收起的轮次；仅在用户没点过时生效 */
const autoCollapsedThink = reactive<Record<string, true>>({})
/** 自动收起定时器；同一轮只挂一个，卸载时统一清掉 */
const autoCollapseTimers = new Map<string, number>()
/** 正文结束后延迟多少毫秒收起思考面板（留半秒让用户看到最后一条思考） */
const AUTO_COLLAPSE_DELAY_MS = 500

/**
 * 渲染行：每条对话配上它的计划分段视图（非计划流程为 undefined）。
 *
 * 用 computed 而不是模板里反复调函数：一次渲染周期内每轮只算一遍，
 * 也避免在模板里对可能为空的返回值做非空断言。
 */
const rows = computed<{ turn: ChatTurn; flow?: TurnSections }[]>(() =>
  props.turns.map((turn) => ({
    turn,
    flow: turn.role === 'assistant' && isPlanFlow(turn) ? turnSections(turn.blocks) : undefined
  }))
)

/** 当前流式中的助手轮；用于顶部粘性状态条。结束后为 null。 */
const liveTurn = computed(() => {
  for (let i = props.turns.length - 1; i >= 0; i -= 1) {
    const item = props.turns[i]
    if (item.role === 'assistant' && item.status === 'streaming' && phaseLabel(item.phase)) {
      return item
    }
  }
  return null
})

/**
 * 消息流最底部的步骤提示：只说明当前这一步在等什么。
 *
 * <p>取最后一轮助手消息：它还在流式才给提示，结束 / 出错 / 待批准一律为空（提示消失）。
 * 未进入工作台（调试皮肤）不显示，避免改动原有试跑界面的版式。
 */
const stepHint = computed(() => {
  // 断线时顶部已有「连接已断开」，这里不再假装还在思考
  if (!isWorkbench.value || props.detached) {
    return ''
  }
  for (let i = props.turns.length - 1; i >= 0; i -= 1) {
    const turn = props.turns[i]
    if (!turn || turn.role !== 'assistant') {
      continue
    }
    if (turn.status !== 'streaming') {
      return ''
    }
    return liveStepHintOf(turn.blocks, turn.status === 'streaming', turn.planPending === true)
  }
  return ''
})

/**
 * 最后一张助手轮的 key。
 * 编排数据只取「最近一轮 trace」，因此只挂在最后一轮上，避免把新一轮的泳道画进旧轮。
 */
const lastAssistantKey = computed(() => {
  for (let i = props.turns.length - 1; i >= 0; i -= 1) {
    if (props.turns[i].role === 'assistant') {
      return props.turns[i].key
    }
  }
  return ''
})

/**
 * 该轮是否渲染编排调度卡。
 * 条件：外层给了数据（无权限 / 非 Router 为 null）、是最后一轮、且带前端态标记。
 * @param item 助手轮
 */
function showRouterCard(item: ChatTurn) {
  return !!props.routerTrace && item.key === lastAssistantKey.value && hasRouterTrace(item)
}

/**
 * 编排卡状态：流式中＝正在调度，失败＝调度失败，其余＝完成。
 * @param item 助手轮
 */
function routerCardStatus(item: ChatTurn): 'running' | 'done' | 'error' {
  if (item.status === 'streaming') {
    return 'running'
  }
  return item.status === 'error' ? 'error' : 'done'
}

/**
 * 用户是否仍停在对话底部附近。
 * 只有它为 true 时才跟滚：上翻阅读历史时，不能被流式增量硬拽回底部。
 */
const nearBottom = ref(true)

/** 距底部多少像素内算「仍在底部」；略大于一行高度，避免轻微抖动就被判成上翻 */
const NEAR_BOTTOM_PX = 80

/** 已排队但还没执行的跟滚帧；同一帧内的多次更新只滚一次 */
let followFrame = 0

/**
 * 上一次由程序写入 `scrollTop` 后读回的实际值（-1 表示本轮还没写过）。
 *
 * <p>用途：区分「用户滚动」与「我们自己滚到底」。后者同样会派发 scroll 事件，
 * 且派发时正文可能又长了，若当成用户上翻就会把跟滚关掉（见 {@link resolveFollowAfterScroll}）。
 */
let selfScrollTop = -1

/**
 * 记录滚动位置：只在滚动事件里读一次布局，判断是否仍在底部附近。
 * 把布局读取从「每个增量」挪到「用户真的滚动」，避免流式期每帧强制同步布局。
 */
function onScroll() {
  const element = scroller.value
  if (!element) {
    return
  }
  nearBottom.value = resolveFollowAfterScroll({
    scrollTop: element.scrollTop,
    scrollHeight: element.scrollHeight,
    clientHeight: element.clientHeight,
    selfScrollTop,
    previous: nearBottom.value,
    threshold: NEAR_BOTTOM_PX
  })
}

/**
 * 强制恢复「跟到底部」。
 *
 * <p>用于语义上必然是「要看最新内容」的时刻：用户刚发问、刚切到另一个会话。
 * 这两处若沿用上一次的滚动状态，从旧会话带着「已上翻」进来就会一直不滚。
 */
function followToBottom() {
  nearBottom.value = true
  scheduleFollowBottom()
}

/**
 * 把跟滚合批到下一帧：一帧最多读一次 scrollHeight、写一次 scrollTop。
 *
 * <p>由 {@link onUpdated} 在内容（含 Markdown 增量）真正写进 DOM 之后触发；
 * 用户已上翻则直接跳过，不抢回滚动位置。
 */
function scheduleFollowBottom() {
  if (followFrame) {
    return
  }
  followFrame = requestAnimationFrame(() => {
    followFrame = 0
    if (!nearBottom.value) {
      return
    }
    const element = scroller.value
    if (!element) {
      return
    }
    // 读布局与写滚动在同一帧完成，只触发一次布局失效，替代原先每个 delta 一次
    element.scrollTop = element.scrollHeight
    // 读回钳制后的真实值：内容不足一屏时会被夹到 0，下次判定要认这个值
    selfScrollTop = element.scrollTop
  })
}

// 用户发问（sending 由 false 变 true）即视为「要看最新内容」：无论之前是否上翻，都重新跟到底
watch(
  () => props.sending,
  (now) => {
    if (now) {
      followToBottom()
    }
  }
)

/**
 * 会话身份（首轮 key）变化：新建会话或切到另一个会话，都要从底部开始看。
 * 不能只依赖上一次的滚动状态，否则从「上翻着的旧会话」切过来会停在顶部。
 */
watch(
  () => props.turns[0]?.key ?? '',
  () => {
    followToBottom()
  }
)

// 每次组件更新（流式增量 / 新轮次 / 排版变化）后安排一次跟滚；不再深监听整棵 turns
onUpdated(() => {
  scheduleFollowBottom()
  // 中间过程内容框 / 思考区在框内跟滚（最终结果不限高，不参与）；
  // 顺带识别「思考刚收起」并在折叠前把内框滚到底
  requestMidProcessScroll()
  // 量一次是否被四行截断，决定要不要出「展开全文」（常量算上限 + 按内容长度/间隔降频）
  measureMidBoxOverflow()
})

/**
 * 挂载时先定位到最新一条。
 * <p>抽屉关掉再打开、页签切回来都会重建组件，而挂载那一刻没有数据变化、不会触发 onUpdated，
 * 不补这一步就会停在最顶部，看起来像「没有自动定位」。
 */
onMounted(followToBottom)

/**
 * 子组件渲染兜底。所属域：runtime（前端对话区）。
 *
 * <p>为什么要有这一层：工作台整页是「左历史 + 右对话」一棵渲染树，结果气泡里的文件卡、
 * 形象组件只要有一个在渲染期抛错（历史块的半截字段最容易踩），整棵树都会挂掉，
 * 表现就是整页空白。这里只丢掉这一小块，其余消息照常显示。
 *
 * <p>只吃渲染 / 挂载阶段的异常：业务请求失败仍由各自的 try/catch 与错误条展示。
 */
onErrorCaptured((error, _instance, info) => {
  if (info === 'render function' || info === 'component update' || info === 'mounted hook') {
    console.warn('[对话消息] 子组件渲染失败，已跳过该小块', error)
    // 返回 false 阻止继续冒泡，避免错误把上层整棵对话树卸载
    return false
  }
  return undefined
})

// 卸载时取消未执行的帧，避免回调在已销毁组件上读 DOM
onBeforeUnmount(() => {
  if (followFrame) {
    cancelAnimationFrame(followFrame)
    followFrame = 0
  }
  // 中间过程跟滚 / 结束复位两帧同理：销毁后回调不能再读 DOM
  if (actorScrollFrame) {
    cancelAnimationFrame(actorScrollFrame)
    actorScrollFrame = 0
  }
  if (finishFrame) {
    cancelAnimationFrame(finishFrame)
    finishFrame = 0
  }
  if (thoughtTimer) {
    window.clearInterval(thoughtTimer)
    thoughtTimer = 0
  }
  pendingScrollKeys.clear()
  pendingFinishKeys.clear()
  openThinkKeys.clear()
  midBoxMeasuredLen.clear()
  answerTextEls.clear()
  actorFoldEls.clear()
  // 自动收起定时器同理：组件销毁后不能再写响应式状态
  for (const timer of autoCollapseTimers.values()) {
    window.clearTimeout(timer)
  }
  autoCollapseTimers.clear()
})

/** 状态码换成商城同款短句。未知阶段不显示空标签。 */
function phaseLabel(phase?: string) {
  if (phase === 'planning') {
    return '任务规划中'
  }
  if (phase === 'vision') {
    return '识别图片文字'
  }
  if (phase === 'reasoning') {
    return '思考中'
  }
  if (phase === 'tool') {
    return '工具执行中'
  }
  if (phase === 'answering') {
    return '回答生成中'
  }
  if (phase === 'error') {
    return '失败'
  }
  return ''
}

/**
 * 本轮思考泳道。
 *
 * <p>工作台必须走严格 {@link processLanes}：没有 begin 不建卡，避免再冒出匿名「执行」。
 * 调试皮肤（单 Agent / 抽屉）没有参与者身份帧，被丢掉的推理和工具由
 * {@link lanesForDebugSkin} 收成一条「思考过程」。两条函数的建卡规则不要并回一处。
 *
 * @param item 助手轮
 */
function lanesOf(item: ChatTurn) {
  if (isWorkbench.value) {
    return processLanes(item.blocks)
  }
  return lanesForDebugSkin(item.blocks)
}

/** 从「路由到 {名}」抽出子 Agent 显示名，供红色强调。 */
function routeAgentName(title: string) {
  const prefix = '路由到 '
  if (!title.startsWith(prefix)) {
    return ''
  }
  return title.slice(prefix.length).trim()
}

function hasProcess(item: ChatTurn) {
  return lanesOf(item).length > 0
}

function hasAnswer(item: ChatTurn) {
  // 历史快照 / 流式半截块可能没有 text：当空处理，不能在这里抛（抛了整页都会白）
  return item.blocks.some((block) => block.kind === 'assistant' && (block.text || '').trim())
}

/**
 * 本轮是否已有可见产出。产物也算：只生成了文件、模型终答为空时，
 * 若仍判「什么都没有」就会一直挂着「思考中…」，与实际情况矛盾。
 *
 * @param item 助手轮
 */
function hasOutput(item: ChatTurn) {
  return hasAnswer(item) || item.blocks.some((block) => block.kind === 'file')
}

/** 非计划流程：还没有任何过程/终答/产物时才显示占位。 */
function showThinking(item: ChatTurn) {
  if (isWorkbench.value) {
    /*
     * 工作台不再有「思考中…」文字占位：
     * 块只有在真实事件到达后才出（入口 / 方案 / 专家各自先思考再出结果），
     * 进行态只体现在思考框或内容框本身（含光标），不在块外再加一行提示。
     */
    return false
  }
  return item.status === 'streaming' && !hasProcess(item) && !hasOutput(item)
}

/**
 * 计划流程：只有这一轮确实还空着才显示占位。
 * 已经有计划思考、计划卡或步骤时再补一行「思考中…」只是噪音。
 * @param item 助手轮
 * @param flow 该轮的分段视图
 */
function showPlanThinking(item: ChatTurn, flow: TurnSections) {
  if (item.status !== 'streaming' || flow.answer) {
    return false
  }
  // 产物同样算产出：只写了文件没有终答时不该再显示「思考中」
  return !flow.planPhase.length && !flow.plan && !flow.execution.length && !flow.files.length
}

/** 单步思考的折叠键后缀。 */
function stepKey(seq: number) {
  return `step-${seq}`
}

/**
 * 折叠键。按轮与区段隔离，避免收起「计划思考」时连带把某一步也收起来。
 * @param item 助手轮
 * @param part 区段后缀（THINK_KEY / PHASE_KEY / stepKey）
 */
function foldKey(item: ChatTurn, part: string) {
  return `${item.key}:${part}`
}

/**
 * 是否展开。用户点过就以覆盖为准，否则用默认值。
 *
 * <p>读值一律走 {@link foldOpenOf}：它直接读 `foldOverride[key]`，
 * 首次点击写入新键时才会被依赖追踪捕获并触发重渲染。早先用
 * `hasOwnProperty` 判断键存在，读缺失键不建立依赖，轮次结束后点标题会「没反应」。
 *
 * @param item        助手轮
 * @param part        区段后缀
 * @param defaultOpen 未操作时的默认态
 */
function isOpen(item: ChatTurn, part: string, defaultOpen: boolean) {
  return foldOpenOf(foldOverride, foldKey(item, part), defaultOpen)
}

/**
 * 切换折叠。
 *
 * @param item        助手轮
 * @param part        区段后缀
 * @param defaultOpen 当前默认态，用于首次点击取反
 */
function toggle(item: ChatTurn, part: string, defaultOpen: boolean) {
  const key = foldKey(item, part)
  // 覆盖值按「当前实际展开态」取反：流式中默认展开时点一次就是收起，再点展开
  foldOverride[key] = nextFoldValue(foldOverride, key, defaultOpen)
}

function resultKey(item: ChatTurn, laneIndex: number, stepIndex: number) {
  return `${item.key}:r:${laneIndex}:${stepIndex}`
}

/** 多行或偏长时才出展开按钮；默认一律一行高度。 */
function needsResultToggle(value: string) {
  return value.includes('\n') || value.length > 48
}

function isResultExpanded(item: ChatTurn, laneIndex: number, stepIndex: number) {
  return resultOpen[resultKey(item, laneIndex, stepIndex)] === true
}

function toggleResult(item: ChatTurn, laneIndex: number, stepIndex: number) {
  const key = resultKey(item, laneIndex, stepIndex)
  resultOpen[key] = !isResultExpanded(item, laneIndex, stepIndex)
}

function lastAssistantIndex(item: ChatTurn) {
  for (let i = item.blocks.length - 1; i >= 0; i -= 1) {
    if (item.blocks[i].kind === 'assistant') {
      return i
    }
  }
  return -1
}

/**
 * 首个助手正文块的下标：工作台把作答 Agent 展示名挂在它上方，
 * 只在第一条正文前出现一次，后续正文块不再重复。
 *
 * @param item 助手轮
 * @return 下标；没有正文块时为 -1
 */
function firstAssistantIndex(item: ChatTurn) {
  return item.blocks.findIndex((block) => block.kind === 'assistant')
}

/**
 * 思考与终答共用轻量 Markdown。流式走安全前缀+表渐进，结束后完整终渲。
 * 原文标签不会执行。
 *
 * <p>按「渲染位置」缓存结果：流式期每帧只有正在增长的那一块签名会变，
 * 历史块的 HTML 直接命中缓存，不再每帧把全部历史重新跑一遍正则。
 *
 * @param slot      渲染位置键（轮次 + 块 + 区段），同一位置必须稳定不变
 * @param value     Markdown 原文
 * @param streaming 是否流式渲染（与终态分开缓存，避免脏 HTML）
 * @return 可直接 v-html 的 HTML
 */
function markdown(slot: string, value: string, streaming = false) {
  return renderMarkdownMemo(slot, value, streaming)
}

/**
 * 工作台思考区文本：抹掉 route_key 等内部键；调试形态原样保留排障信息。
 * 只影响渲染，不改思考过程的数据源。
 *
 * @param raw 思考正文 / 工具行 / 路由决策 JSON
 * @return 可展示文本
 */
function thinkText(raw: string) {
  return isWorkbench.value ? stripWorkbenchInternalKeys(raw) : raw
}

/**
 * 工作台正文展示：剥文首人设/调试小节标题；调试形态原样。
 * 直播终态和回放共用这一条，只影响渲染，不改 turns 落库内容。
 *
 * @param raw 结果框原文（助手正文或工作流终答）
 * @return 交给 Markdown 的可见正文
 */
function displayAnswer(raw: string) {
  // 气泡正文是用户可见终稿：路由 JSON 与检索结构都不原样铺出来
  if (!isWorkbench.value) {
    return raw
  }
  // 调用名换成目录标题：胶囊已是中文，正文不再重复函数名。目录未到时原样
  return maskToolCallNames(
    presentVisibleResult(stripRouteDecisionJson(stripWorkbenchLeadHeadings(raw))),
    toolTitleByName
  )
}

/**
 * 工作台错误展示：硬核不可用 → 友好灰字；其它原样。调试形态原样红字。
 */
function displayErrorText(raw: string) {
  return isWorkbench.value ? friendlyWorkbenchError(raw) : raw
}

/** 计划流终答的缓存槽键。与朗读键区分：这里只标识渲染位置 */
function planAnswerSlot(item: ChatTurn): string {
  return `plan:${item.key}:answer`
}

/**
 * 块级 v-for 的 key。
 * <p>取数据层分配的稳定 id；块一旦建立就永远不变，视觉块/调度卡插到队首时其它块不会重挂。
 * 只有异常数据（外部构造、缺 id）才退化成「种类 + 轮内下标」——此时只保证同轮内不撞键。
 *
 * @param block 块
 * @param index 块在本轮内的下标；仅在缺 id 的兜底分支使用
 * @return 稳定 key
 */
function blockKey(block: AssistantBlock, index: number): string {
  return block.id || `out-${block.kind}-${index}`
}

/**
 * 非计划流程里 assistant 块的 Markdown 缓存槽键。
 * <p>与 v-for key 同源（稳定 id）：块顺序变化不会让历史块命中不到上一帧的 HTML。
 *
 * @param block 块
 * @param index 块在本轮内的下标；仅在缺 id 的兜底分支使用
 * @return 缓存槽键
 */
function blockSlot(block: AssistantBlock, index: number): string {
  return `out:${block.id || `${block.kind}-${index}`}`
}

/** 思考过程里单个步骤的缓存槽键；泳道与步骤序号共同定位 */
function stepSlot(item: ChatTurn, laneIndex: number, stepIndex: number): string {
  return `think:${item.key}:${laneIndex}:${stepIndex}`
}

/**
 * 调试皮肤思考折叠条文案：与改动前一致，固定「思考过程」。
 * 工作台消息流不再放全场轨迹，过程在各专家自己的「查看思考过程与工具调用」里。
 *
 * @param item 助手轮（保留入参以兼容既有调用点）
 * @return 折叠条标题
 */
function thinkToggleLabel(item: ChatTurn) {
  return '思考过程'
}

/**
 * 空状态快捷卡片：填入输入框并立刻发送（与点发送等价）。
 *
 * @param prompt 卡片文案
 */
function sendQuick(prompt: string) {
  const content = prompt.trim()
  if (!content || !props.canSend || !props.sessionReady || props.sending) {
    return
  }
  text.value = content
  submit()
}

function submit() {
  const content = text.value.trim()
  if (!content || !props.canSend || !props.sessionReady || props.sending) {
    return
  }
  // 未绑定视觉模型时即便有残留图片也不提交：后端会直接拒，不如前端先兜住
  const images = props.visionEnabled === true ? attachments.value.map((item) => item.dataUrl) : []
  emit('send', content, images.length ? images : undefined)
  text.value = ''
  attachments.value = []
}

/** 点「图片」：走原生文件选择器，避免 el-upload 额外引入一条上传链路 */
function pickImages() {
  if (!canUploadNow.value) {
    return
  }
  filePicker.value?.click()
}

/**
 * 选中文件后转 data URI。逐个读、边读边校验，
 * 单张超限或超张数时立即停止并提示，不把整批都读进内存。
 * @param event 原生 input 的 change 事件
 */
async function onFilesPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  // 立刻清空 value：否则同一个文件再次选择不会触发 change
  input.value = ''
  if (!files.length) {
    return
  }
  const room = MAX_IMAGES - attachments.value.length
  if (room <= 0) {
    ElMessage.warning(`一次最多上传 ${MAX_IMAGES} 张图片`)
    return
  }
  const picked = files.slice(0, room)
  for (const file of picked) {
    if (file.size > MAX_IMAGE_BYTES) {
      ElMessage.warning(`${file.name} 超过 6MB，请压缩后再上传`)
      continue
    }
    try {
      const dataUrl = await readAsDataUrl(file)
      attachmentSeq += 1
      attachments.value = [...attachments.value, { id: attachmentSeq, name: file.name, dataUrl }]
    } catch {
      ElMessage.warning(`${file.name} 读取失败`)
    }
  }
  // 多选了仍放不下的部分明确告知，而不是静默丢弃
  if (files.length > picked.length) {
    ElMessage.info(`已达 ${MAX_IMAGES} 张上限，其余图片未加入`)
  }
}

/**
 * @param file 浏览器 File
 * @return data:{mime};base64,… 形式的字符串
 */
function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(new Error('read failed'))
    reader.readAsDataURL(file)
  })
}

/**
 * 移除一张待发图片。
 * @param index 在 attachments 中的位置
 */
function removeAttachment(index: number) {
  if (props.sending) {
    return
  }
  attachments.value = attachments.value.filter((_, i) => i !== index)
}
</script>

<style scoped>
.chat {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

/* 工作台：消息流与输入框同一中轴（大屏绝对居中） */
.chat.is-workbench {
  align-items: center;
}

.messages {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
}

.empty {
  padding: 48px 12px;
  color: var(--ah-text-secondary);
  text-align: center;
}

.empty h3 {
  margin: 0 0 8px;
  color: var(--ah-text);
  font-size: 18px;
}

.empty p {
  margin: 0;
  font-size: 13px;
}

.quick-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  max-width: 720px;
  margin: 28px auto 0;
  text-align: left;
}

.quick-card {
  padding: 14px 16px;
  border: none;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  color: var(--ah-text);
  font: inherit;
  font-size: 13px;
  line-height: 1.5;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.quick-card:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}

.quick-card:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

/* 消息行容器负责左右对齐：AI 靠左、User 靠右；限宽居中在上层 .messages 上，勿在行上再收窄 */
.row {
  display: flex;
  width: 100%;
  margin-bottom: 14px;
}

.row.user {
  justify-content: flex-end;
}

.row.assistant {
  justify-content: flex-start;
}

.speak-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  width: 100%;
}

.speak-row.is-user {
  justify-content: flex-end;
}

.speak-row.is-assistant {
  justify-content: flex-start;
}

.speak-row.is-assistant .card {
  flex: 1;
  min-width: 0;
}

.msg-avatar {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  overflow: hidden;
  flex: none;
  border-radius: 50%;
  font-size: 14px;
  font-weight: 650;
}

.msg-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* Agent 形象为「白底圆角方座 + 线框」，与列表/详情同形：方座等比圆角，不做正圆裁切；
   白底落在白卡上会糊，用内描边兜住轮廓 */
.msg-avatar.is-assistant {
  border-radius: 9px;
  box-shadow: inset 0 0 0 1px #e5e7eb;
}

/* 图形自带白底方座，铺满整块，不再留内边距 */
.msg-avatar.is-assistant img {
  padding: 0;
}

/* 工作台 AI 名称行：头像右侧、气泡上方，深灰小字；User 消息不渲染 */
.msg-name {
  margin: 0 0 4px;
  padding-left: 2px;
  color: #374151;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.3;
}

.bubble {
  max-width: 78%;
  padding: 10px 12px;
  border-radius: 12px;
  background: #efeefe;
  line-height: 1.55;
  white-space: pre-wrap;
}

.card {
  width: 100%;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid transparent;
  border-radius: 12px;
}

/* —— 工作台沉浸皮肤：限宽居中 / AI 气泡 / 思考摘要 / 输入区 focus —— */
.chat.is-workbench .messages,
.chat.is-workbench .composer {
  flex: none;
  width: 100%;
  max-width: 800px;
  margin-left: auto;
  margin-right: auto;
}

.chat.is-workbench .messages {
  flex: 1;
  min-height: 0;
  align-self: center;
}

.chat.is-workbench .composer {
  align-self: center;
}

.chat.is-workbench .empty h3 {
  font-size: 22px;
  font-weight: 650;
}

.chat.is-workbench .bubble {
  background: var(--ah-primary, #635bff);
  color: #fff;
  border-radius: 14px;
}

.chat.is-workbench .card {
  padding: 8px 4px;
  background: transparent;
  border: none;
  border-radius: 0;
}

/* AI 正文气泡：与思考框、工作流卡同一档宽度，不按字数各自收缩 */
.chat.is-workbench .answer-block {
  position: relative;
  width: 100%;
  box-sizing: border-box;
  background-color: #f8fafc;
  padding: 16px 20px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  border-bottom-left-radius: 4px;
}

/*
 * 结果正文：默认四行，框内滚动。展开后去掉 is-clamped，高度回到全文。
 * 四行 = 4 × 1.6em。14px / 1.6 与 MID_BOX_LINE_HEIGHT_PX 是同一口径。
 */
.chat.is-workbench .md.answer.is-clamped {
  max-height: calc(4 * 1.6em);
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
}

.chat.is-workbench .md.answer {
  color: #1f2937;
  line-height: 1.6;
}

/* 结果框：复制图标和底部箭头都只在移入文本框时出现 */
.wb-result-box {
  position: relative;
}

.wb-copy {
  position: absolute;
  top: 4px;
  right: 4px;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #6b7280;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
}

.wb-result-box:hover .wb-copy,
.wb-copy:focus-visible {
  opacity: 1;
  pointer-events: auto;
}

.wb-copy:hover {
  border-color: #d1d5db;
  background: #f9fafb;
  color: #374151;
}

.wb-result-jump {
  position: absolute;
  left: 50%;
  bottom: 2px;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 28px;
  height: 18px;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.12);
  transform: translateX(-50%);
  opacity: 0;
  pointer-events: none;
  cursor: pointer;
}

.wb-result-box:hover .wb-result-jump {
  opacity: 1;
  pointer-events: auto;
}

.wb-jump-chevron {
  width: 0;
  height: 0;
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 6px solid #111;
}

.wb-result-jump.is-up .wb-jump-chevron {
  border-top: 0;
  border-bottom: 6px solid #111;
}

/* 文件在正文框下面单独一行，宽度跟结果框对齐，不进气泡，也不替换正文 */
.chat.is-workbench .wb-file-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  margin-top: 8px;
}

/* 没有正文时的终态说明：框外一行，不装进四行正文框 */
.chat.is-workbench .wb-terminal-note {
  margin: 8px 0 0;
  max-width: min(100%, 640px);
  color: #6b7280;
  font-size: 13px;
  line-height: 1.5;
}

/*
 * Agent 需要用户拍板时的可点选项：正文下方一组 chips（机器块已从正文摘掉）。
 * 与正文同一条左缘，不做成居中的对话框——它仍然属于这位参与者的那块结果。
 */
.chat.is-workbench .wb-choice {
  margin-top: 12px;
}

/* 一句话问题：比正文略小、略浅，让 chips 成为视觉落点 */
.chat.is-workbench .wb-choice-question {
  margin: 0 0 8px;
  color: #4b5563;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
}

/*
 * 选项一行一项：竖排铺满文字区，不挤成同一行里的多个胶囊。
 * 选项文案可能是一整句（如「Vue3 + Vite + TS + Element Plus」），
 * 横排换行会让几行混在一起分不清哪行是哪一项。
 */
.chat.is-workbench .wb-choice-options {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  max-width: 680px;
}

/* 选项按钮：白底细边 + 紫 hover，与工作台 primary 一致；不做实心主按钮（多个选项地位相同） */
.chat.is-workbench .wb-choice-chip {
  display: block;
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #d1d5db;
  border-radius: 10px;
  background: #fff;
  color: #374151;
  font-size: 13px;
  line-height: 1.5;
  /* 长文案左对齐、自然折行：一行一项，行首对齐才好扫读 */
  text-align: left;
  overflow-wrap: anywhere;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background-color 0.15s;
}

.chat.is-workbench .wb-choice-chip:hover:not(:disabled) {
  border-color: #635bff;
  color: #635bff;
  background: #f5f3ff;
}

/* 已过期（历史轮）或正在发送：置灰且不可点，避免续作到已翻页的上下文 */
.chat.is-workbench .wb-choice-chip:disabled {
  border-color: #e5e7eb;
  color: #9ca3af;
  background: #f9fafb;
  cursor: not-allowed;
}

/* 兜底：若文首 heading 仍进了 HTML，不展示（strip 函数是主路径） */
.chat.is-workbench .md.answer :deep(h1:first-child),
.chat.is-workbench .md.answer :deep(h2:first-child),
.chat.is-workbench .md.answer :deep(h3:first-child),
.chat.is-workbench .md.answer :deep(h4:first-child),
.chat.is-workbench .md.answer :deep(h5:first-child),
.chat.is-workbench .md.answer :deep(h6:first-child) {
  display: none;
}

.chat.is-workbench .think-toggle {
  padding: 0 0 4px;
  border-bottom: none;
}

/* 工作台展示名：只属于正文气泡，宽度跟气泡一致而不是撑满整列 */
.chat.is-workbench .msg-name {
  width: fit-content;
  max-width: min(100%, 640px);
  margin: 10px 0 4px;
  font-size: 13px;
}

/* 正文气泡上方的身份行：小头像 + 名称，与气泡左缘对齐 */
.chat.is-workbench .wb-answer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0 6px;
}

.chat.is-workbench .wb-answer-head .msg-name {
  margin: 0;
}

.chat.is-workbench .wb-answer-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 24px;
  height: 24px;
  overflow: hidden;
  border-radius: 7px;
  box-shadow: inset 0 0 0 1px #e5e7eb;
  font-size: 11px;
  font-weight: 650;
}

.chat.is-workbench .wb-answer-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 消息流：网关 / 方案 / 专家同一种聊天块，一律左对齐头像 + 名称 + 一块内容框。
   思考默认收起（约 200px 内滚动），本步结果写在内容框里，不再有居中判定条 / 路由条 */
.wb-actors {
  display: flex;
  flex-direction: column;
  gap: 18px;
  margin-top: 4px;
}

/*
 * 方案外框：专家卡画在这只框里面，头像和思考仍是原来的专家卡。
 * 方案自己的思考在框的上半，汇总卡在专家后面，不写进专家正文。
 */
.wb-scheme-frame {
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  margin-top: 4px;
  padding: 12px;
  border: 1px solid #e4e4f7;
  border-radius: 12px;
  background: #fff;
}

/* 子卡的 key 包装不占高度，专家卡仍是方案框的直接内容 */
.wb-scheme-frame :deep(.wb-scheme-member) {
  display: contents;
}

/* 框已经缩在方案内容列里，内部专家卡不要再按 640px 硬撑出框 */
.wb-scheme-frame .wb-actor-main {
  width: auto;
  max-width: 100%;
}

/*
 * 消息流底部的步骤提示：一行浅灰小字 + 呼吸点，左对齐到参与者内容列。
 * 不做胶囊、不做居中、不粘顶——它只回答「下一步在等什么」。
 */
.wb-step-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 10px 0 2px 48px;
  color: #9ca3af;
  font-size: 12px;
  line-height: 1.6;
}

.wb-step-hint-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a5a3ff;
  animation: wb-step-breathe 1.4s ease-in-out infinite;
}

@keyframes wb-step-breathe {
  0%,
  100% {
    opacity: 0.25;
  }

  50% {
    opacity: 0.9;
  }
}

.wb-actor {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: stretch;
}

/* 头像 + 名称 + 角标同一行垂直居中；下方内容缩进到名称列 */
.wb-actor-topline {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.wb-actor-main {
  min-width: 0;
  /* 同一轮里思考框、结果框、工作流卡共用这一档宽度，不再按每张卡的字数各缩各的 */
  width: min(100%, 640px);
  /* 36px 头像 + 12px 间距，与名称列左缘对齐 */
  margin-left: 48px;
  max-width: min(100%, 640px);
}

.wb-actor-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.wb-actor-avatar {
  position: relative;
  display: grid;
  place-items: center;
  flex: none;
  box-sizing: border-box;
  width: 36px;
  height: 36px;
  overflow: hidden;
  border: 1.5px solid #c0c4cc;
  border-radius: 50%;
  background: #fff;
  font-size: 14px;
  font-weight: 650;
}

/* 还在执行：只转外圈的一段银弧，形象本身不转。结束后去掉这一层，留下整圈银边。 */
.wb-actor-avatar.is-running {
  border-color: transparent;
}

.wb-actor-avatar.is-running::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  border-radius: 50%;
  background: conic-gradient(#c0c4cc 0 28%, transparent 0 100%);
  mask: radial-gradient(farthest-side, transparent calc(100% - 1.5px), #000 calc(100% - 1.4px));
  animation: wb-avatar-spin 0.8s linear infinite;
  pointer-events: none;
}

.wb-actor-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/*
 * 工作流头像：固定白底 + 黑色齿轮线稿，外圈与其它参与者同一只银圈。
 */
.wb-actor-avatar.is-workflow {
  background: #fff;
  color: #1f2430;
}

.wb-actor-name {
  font-size: 14px;
  font-weight: 650;
  color: #1f2430;
}

.wb-actor-role {
  padding: 0 6px;
  border-radius: 999px;
  background: #f4f3ff;
  color: #635bff;
  font-size: 12px;
  line-height: 18px;
}

/* 工作流角标：演示稿的紫色标签底（只在工作流块上加，不影响专家 / 方案 / 入口） */
.wb-actor-role.is-wf {
  background: #f3e8ff;
  color: #7e22ce;
  border: 1px solid #d8b4fe;
}

/*
 * 工作流执行卡片（视觉以 temp/test-l.html 为准；过程/结果上下分栏）。
 * 只作用于工作流块：专家 / 方案块仍走 .answer-block 那套气泡，样式互不影响。
 * 最大宽度与入口/方案/专家气泡对齐（640），不拉满整行。
 */
.wf-card {
  width: 100%;
  margin-top: 2px;
  overflow: hidden;
  border: 1px solid #d1fae5;
  border-radius: 8px;
  background: #fff;
  box-shadow: none;
}

/* 只有标题栏是淡绿，标题以下保持白底 */
.wf-process {
  background: #fff;
}

.wf-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid #d1fae5;
  background: #f0fdf4;
}

.wf-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #166534;
  font-size: 15px;
  font-weight: 600;
}

.wf-status {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 13px;
  white-space: nowrap;
  transition: all 0.3s;
}

.wf-status.is-running {
  background: #eff6ff;
  color: #1d4ed8;
}

.wf-status.is-success {
  background: #fff;
  color: #059669;
  border: 1px solid #a7f3d0;
}

.wf-status.is-failed {
  background: #fee2e2;
  color: #991b1b;
}

.wf-status.is-cancelled {
  background: #f1f5f9;
  color: #475569;
}

.wf-status.is-waiting {
  background: #fff7ed;
  color: #c2410c;
}

/* 垂直时间轴：连接线在左，节点圆点压在线上面；一行一项 */
.wf-steps {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px;
}

.wf-steps::before {
  position: absolute;
  top: 28px;
  bottom: 28px;
  left: 28px;
  width: 2px;
  background: #e2e8f0;
  content: '';
  z-index: 0;
}

.wf-step-item {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 16px;
  color: #94a3b8;
  font-size: 14px;
  z-index: 1;
  transition: color 0.3s;
}

.wf-step-item.is-done {
  color: #1e293b;
  font-weight: 500;
}

.wf-step-item.is-active {
  color: #2563eb;
  font-weight: 500;
}

.wf-step-item.is-failed {
  color: #b91c1c;
  font-weight: 500;
}

.wf-step-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 22px;
  height: 22px;
  margin-top: 1px;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #f1f5f9;
  color: #cbd5e1;
  font-size: 12px;
  transition: all 0.3s;
}

.wf-step-icon.is-done {
  background: #10b981;
  color: #fff;
}

.wf-step-icon.is-active {
  background: #3b82f6;
  color: #fff;
  box-shadow: 0 0 0 4px #dbeafe;
}

.wf-step-icon.is-failed {
  background: #ef4444;
  color: #fff;
}

.wf-step-main {
  min-width: 0;
}

.wf-step-name {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.wf-step-code {
  color: #9ca3af;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  font-weight: 400;
}

/* 节点种类角标：只标 Agent，形态靠近消息流角标但不抢工作流紫色 */
.wf-step-kind {
  padding: 0 6px;
  border: 1px solid #c7d2fe;
  border-radius: 4px;
  background: #eef2ff;
  color: #4f46e5;
  font-size: 11px;
  font-weight: 500;
  line-height: 18px;
}

.wf-step-note {
  margin-top: 2px;
  color: #64748b;
  font-size: 12px;
  font-weight: 400;
}

.wf-step-tools {
  margin: 8px 0 0;
}

/* 节点内工具胶囊只读：快照没有入参出参，不可点开详情 */
.wf-step-tools .wb-tool-pill {
  cursor: default;
}

/* 结果区：虚线与过程区隔开，白底。正文四行在框内滚，不另套灰框。 */
.wf-result {
  padding: 16px;
  border-top: 1px dashed #d1fae5;
  background: #fff;
}

/*
  运行中的折叠行：替代整屏结果区。
  与「结果」同一位置（过程区下方、实线之上），形状是一条可点的窄条，
  不抢高度、不压滚动位置；点开后同一张卡就地展开正文。
*/
.wf-result-fold {
  padding: 10px 18px;
  border-top: 1px solid #e2e8f0;
  background: #fff;
}

.wf-result-fold-btn {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 6px 10px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  color: #475569;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
}

.wf-result-fold-btn:hover {
  border-color: #635bff;
  color: #4c46c9;
}

.wf-result-fold-label {
  font-weight: 600;
}

.wf-card.completed .wf-result {
  border-top-color: #d1fae5;
}

.wf-result-title {
  margin: 0 0 10px;
  color: #1e293b;
  font-size: 13px;
  font-weight: 650;
  letter-spacing: 0.02em;
}

.wf-result-tip {
  margin: 0 0 10px;
  color: #64748b;
  font-size: 13px;
}

.wf-output-text {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.wf-spin {
  display: inline-block;
  animation: wf-spin 1s linear infinite;
}

@keyframes wf-spin {
  to {
    transform: rotate(360deg);
  }
}

.wb-actor-fold .think-toggle {
  padding: 0;
}

/* 中间过程框被四行截断时的展开入口：小号文字按钮，不抢内容框的视觉 */
.wb-actor-more {
  margin: 4px 0 0 2px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ah-primary, #635bff);
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
}

.wb-actor-more:hover {
  text-decoration: underline;
}

/* 框内的本步结果句（网关判定 / 方案分流）：内容框里的第一段正文，
   不再自带边框 / 底色 / 缩进——它已经在气泡内，不能看起来像另一条提示 */
.wb-actor-result {
  margin: 0;
  color: #1f2937;
  font-size: 14px;
  line-height: 1.6;
}

/* 结果句后面还有正文（方案有真汇总）时留一点间距，读起来是同一块的「分流 + 结论」 */
.wb-actor-result + .md.answer,
.wb-actor-result + .wb-soft-error {
  margin-top: 10px;
}

/* ===== 思考框（对齐 temp/test.html 的 .thought-box）===== */

/* 整块是一个浅灰圆角框。思考文字自己限高四行并在框内滚动，标题不跟着滚 */
.wb-actor-thought {
  width: 100%;
  box-sizing: border-box;
  margin: 0 0 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f9fafb;
  overflow: hidden;
  transition: background 0.18s ease, border-color 0.18s ease;
}

/* 已收起：只剩标题那一行，点标题展开；底与描边淡出，读起来像一行可点文字 */
.wb-actor-thought.is-folded {
  background: transparent;
  border-color: transparent;
  /* 收起时保留与流式相同的高度上限：
     从「流式中」到「已收起」只改一帧，若这里放开 max-height，
     整框会先弹到全文高度再收起，看起来就是「闪一下」。
     41~88px 之间只有标题行，不会被裁到。 */
  max-height: 88px;
}

.wb-actor-thought.is-folded:hover {
  background: #f3f4f6;
  border-color: #e5e7eb;
}

/* 标题行：整行可点，与演示的 thought-header 一致 */
.wb-thought-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin: 0;
  padding: 8px 12px;
  border: 0;
  background: transparent;
  color: #9ca3af;
  font: inherit;
  font-size: 13px;
  font-weight: 400;
  text-align: left;
  cursor: pointer;
  user-select: none;
}

.wb-thought-head:hover {
  color: #4f46e5;
}

.thought-chevron {
  flex: none;
  width: 0;
  height: 0;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 5px solid currentColor;
  transition: transform 0.2s ease;
}

.thought-chevron.open {
  transform: rotate(90deg);
}

/* 思考正文：固定四行，超出在这四行里面滚动，不把整段铺到页面上 */
.wb-actor-thinking {
  margin: 0;
  padding: 0 12px 12px;
  max-height: calc(4 * 1.6em + 12px);
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  color: #4b5563;
  font-size: 13px;
  line-height: 1.6;
  font-family: ui-monospace, Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ===== 工具调用胶囊（对齐 .tool-pill）===== */

.wb-tool-pills {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 0 10px;
}

.wb-tool-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  max-width: 100%;
  padding: 6px 12px;
  border: 1px solid #bbf7d0;
  border-radius: 6px;
  background: #f0fdf4;
  color: #166534;
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease, color 0.16s ease;
}

/* 调用中：蓝底 + 图标旋转，明确「还在跑」 */
.wb-tool-pill.is-running {
  border-color: #bfdbfe;
  background: #eff6ff;
  color: #1d4ed8;
  cursor: default;
}

.wb-tool-pill.is-running .wb-tool-icon {
  animation: wb-tool-spin 1s linear infinite;
}

/* 失败：红底；原因已写在胶囊上，点开可看细节 */
.wb-tool-pill.is-error {
  border-color: #fecaca;
  background: #fef2f2;
  color: #991b1b;
}

.wb-tool-icon {
  flex: none;
  display: inline-grid;
  place-items: center;
  width: 14px;
  height: 14px;
  font-size: 12px;
  line-height: 1;
}

.wb-tool-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-tool-more {
  flex: none;
  margin-left: 4px;
  color: #9ca3af;
  font-size: 10px;
}

/* 工具详情：深色代码块，与演示的 .tool-detail 一致 */
.wb-tool-detail {
  margin: 0;
  padding: 10px 14px;
  border-radius: 6px;
  background: #1e293b;
  color: #e2e8f0;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

@keyframes wb-tool-spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes wb-avatar-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 思考内容区：思考框自己限高，这里不再二次限制，只保证长文本可读。
 *
 * 折叠动画走「grid 行高 1fr ↔ 0fr」而不是 v-show 的 display 瞬切：
 * display 切换没有过渡，三拍切换（思考 → 胶囊 / 结果框）时整段内容会瞬间消失，
 * 看起来就是「跳一下」。行高可以连续过渡，形态与结构完全不变。 */
.wb-actor-fold {
  display: grid;
  grid-template-rows: 1fr;
  transition: grid-template-rows 0.24s ease;
}

.wb-actor-fold.is-collapsed {
  grid-template-rows: 0fr;
}

.wb-actor-fold-body {
  max-height: none;
  /* 0fr 要真正收起，子元素必须允许被压到 0：min-height + overflow 是这套写法的前提 */
  min-height: 0;
  overflow: hidden;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 13px;
  line-height: 1.55;
}

.wb-actor-line,
.wb-actor-reasoning {
  margin: 0 0 6px;
  color: #4b5160;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.wb-actor-steps {
  margin: 0;
  padding: 0;
  list-style: none;
}

.wb-actor-step-title {
  display: block;
  margin-bottom: 2px;
  color: #6b7280;
  font-size: 12px;
}

.wb-actor-pre {
  margin: 0 0 8px;
  white-space: pre-wrap;
  word-break: break-word;
  color: #374151;
  font-size: 12px;
  font-family: inherit;
}

/* 有思考面板时行首不再有大头像，卡片直接占满宽度 */
.chat.is-workbench .speak-row.is-assistant > .card {
  width: 100%;
}

.chat.is-workbench .think-toggle.is-summary .section-label,
.chat.is-workbench .section-label.is-summary {
  color: #9ca3af;
  font-size: 12px;
  font-weight: 400;
}

/* 生成中标题：只在文字上跑一道浅浅的高光，不做色块 / 进度条 / 跑马灯 */
.chat.is-workbench .think-toggle .section-label.is-streaming {
  background: linear-gradient(90deg, #9ca3af 0%, #635bff 45%, #9ca3af 90%);
  background-size: 220% 100%;
  background-clip: text;
  -webkit-background-clip: text;
  color: transparent;
  animation: wb-think-shimmer 1.8s linear infinite;
}

@keyframes wb-think-shimmer {
  0% {
    background-position: 120% 0;
  }

  100% {
    background-position: -120% 0;
  }
}

.chat.is-workbench .composer {
  padding: 12px 14px 10px;
  border: 1px solid transparent;
  border-radius: 16px;
  background: #f5f7fa;
  box-shadow: none;
  box-sizing: border-box;
}

/* focus：主题色边框 + 紫色光圈（截图验收硬条件） */
.chat.is-workbench .composer:focus-within {
  background: #fff;
  border-color: var(--ah-primary, #635bff);
  box-shadow: 0 0 0 3px rgba(99, 91, 255, 0.22);
}

.chat.is-workbench .composer :deep(.el-textarea__inner) {
  background: transparent !important;
  box-shadow: none !important;
  border-color: transparent !important;
  /* 工作台输入框是普通文本框：两行起、随内容长高，不出现缩放手柄与内部滚动条 */
  resize: none;
  padding: 6px 0;
  min-height: 56px;
  line-height: 1.7;
}

/* 硬核不可用：柔和灰字系统提示，不用刺眼红字 */
.wb-soft-error {
  margin: 8px 0 0;
  padding: 0;
  color: #9ca3af;
  font-size: 13px;
  line-height: 1.5;
}

.gateway-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
  padding: 2px 8px;
  border-radius: 999px;
  background: #eef2ff;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  white-space: nowrap;
}

.mention-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 0 8px;
}

.mention-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px 2px 10px;
  border-radius: 999px;
  background: #efeefe;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
}

.mention-chip-x {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.mention-chip-x:hover:not(:disabled) {
  background: rgba(99, 91, 255, 0.15);
}

.mention-chip-x:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.at-panel {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: calc(100% + 6px);
  z-index: 12;
  max-height: 220px;
  margin: 0;
  padding: 6px 0;
  overflow: auto;
  list-style: none;
  border: 1px solid var(--ah-border-soft, #e8eaef);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
}

.at-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 10px;
  padding: 8px 14px;
  cursor: pointer;
}

.at-item:hover,
.at-item.active {
  background: #f5f3ff;
}

.at-name {
  color: var(--ah-text, #1f2937);
  font-size: 13px;
  font-weight: 600;
}

.at-desc {
  flex: 1;
  min-width: 0;
  color: #9ca3af;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.at-type {
  flex: none;
  padding: 0 6px;
  border-radius: 4px;
  background: #f3f4f6;
  color: #6b7280;
  font-size: 11px;
}

.at-empty {
  padding: 10px 14px;
  color: #9ca3af;
  font-size: 12px;
}

.phase {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--ah-text-secondary);
  font-size: 13px;
}

.phase-sticky {
  position: sticky;
  top: 0;
  z-index: 6;
  margin: 0 0 10px;
  padding: 8px 12px;
  border-bottom: 1px solid #e8eaef;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(4px);
}

.phase-bar {
  width: 3px;
  height: 14px;
  border-radius: 2px;
  background: var(--ah-primary);
}

.phase-error .phase-bar {
  background: var(--el-color-danger);
}

.wait {
  color: var(--ah-text-muted);
}

.thinking {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary, #8c8c8c);
  font-size: 0.875em;
}

.caret {
  color: var(--ah-primary);
}

.section-label {
  margin: 0;
  color: var(--ah-text);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.3;
}

.think-block {
  margin-top: 8px;
  padding: 0;
  border: 0;
  background: transparent;
}

.think-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin: 0;
  padding: 0 0 8px;
  border: 0;
  border-bottom: 2px solid var(--ah-primary, #635bff);
  background: transparent;
  cursor: pointer;
  text-align: left;
  position: relative;
  z-index: 1;
}

.think-toggle:hover .section-label {
  color: #374151;
}

.chevron {
  flex: none;
  margin-left: auto;
  width: 0;
  height: 0;
  border-left: 4px solid transparent;
  border-right: 4px solid transparent;
  border-top: 5px solid #98a2b3;
  transition: transform 0.15s ease;
}

.chevron.open {
  transform: rotate(180deg);
}

.think-panel {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.think-panel.lanes-multi {
  gap: 14px;
}

.think-lane {
  padding: 10px 12px;
  border: 1px solid #e8eaef;
  border-radius: 10px;
  background: #fafbff;
}

.think-lane.lane-decision {
  background: #f7f8fc;
}

.lane-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  color: #a3a3a3;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.3;
}

.route-key {
  color: #98a2b3;
  font-size: 11px;
  font-weight: 500;
}

.lane-body {
  margin: 0 0 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f3f4f8;
  color: #667085;
  font-size: 12px;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
}

.timeline {
  margin: 0;
  padding: 0;
  list-style: none;
}

.timeline-item {
  display: flex;
  gap: 10px;
  align-items: stretch;
}

.timeline-rail {
  position: relative;
  flex: none;
  width: 12px;
}

.timeline-dot {
  position: absolute;
  top: 5px;
  left: 2px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ah-primary, #635bff);
  box-shadow: 0 0 0 2px #eef0ff;
}

.timeline-line {
  position: absolute;
  top: 16px;
  bottom: -4px;
  left: 5px;
  width: 2px;
  background: #e4e7ef;
}

.timeline-item:last-child .timeline-line {
  display: none;
}

.timeline-main {
  flex: 1;
  min-width: 0;
  padding-bottom: 12px;
}

.step-title {
  margin-bottom: 4px;
  color: #a3a3a3;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.3;
}

.route-agent {
  color: var(--el-color-danger, #f56c6c);
  font-weight: 700;
}

.think-body {
  color: #b0b0b0;
  font-size: 0.875em;
  line-height: 1.55;
}

.step-call {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #d6e4ff;
  background: #eef5ff;
  color: #b0b0b0;
  font-size: 0.875em;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, Consolas, monospace;
}

.step-result {
  margin: 0;
}

.step-result-body {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  background: #f7f8fb;
  color: #b0b0b0;
  font-size: 0.875em;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: ui-monospace, Consolas, monospace;
}

.step-result-body.clamped {
  /* 用单行省略，避免 max-height 被 padding 吃成半行 */
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.result-toggle {
  margin-top: 4px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--ah-primary, #635bff);
  cursor: pointer;
  font-size: 12px;
}

.answer-label {
  padding-bottom: 8px;
  border-bottom: 2px solid var(--ah-primary, #635bff);
}

/* 计划卡：白底细框，与「最终回答」区分；步骤用序号圆点，读起来像清单 */
.plan-block {
  margin-top: 12px;
  padding: 10px 12px;
  border: 1px solid #e6e8ef;
  border-radius: 10px;
  background: #fafbff;
}

.plan-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.plan-steps {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.plan-step {
  display: flex;
  gap: 8px;
  padding: 5px 0;
  font-size: 13px;
  line-height: 1.5;
}

.plan-seq {
  flex: none;
  width: 20px;
  height: 20px;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
  font-weight: 650;
  line-height: 20px;
  text-align: center;
  background: rgba(99, 91, 255, 0.1);
  border-radius: 50%;
}

.plan-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.plan-title {
  color: var(--ah-text, #1f2430);
}

.plan-detail {
  margin-top: 2px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.plan-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #ebedf3;
}

.plan-running {
  margin: 8px 0 0;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
}

/* 按步执行：左侧色条区分状态，标题行整行可点（该步有思考时） */
.exec-step {
  margin-top: 8px;
  padding: 6px 10px;
  border-left: 3px solid #e4e7ef;
  border-radius: 6px;
  background: #fcfcfd;
}

.exec-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.exec-head:disabled {
  cursor: default;
}

.exec-badge {
  flex: none;
  padding: 0 6px;
  color: var(--ah-text-secondary, #6b7280);
  background: #f2f3f7;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}

.exec-title {
  flex: 1;
  min-width: 0;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  font-weight: 600;
}

.exec-hint {
  flex: none;
  color: var(--ah-primary, #635bff);
  font-size: 12px;
}

.exec-head .chevron {
  margin-left: 0;
}

.exec-think {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fafbff;
}

.exec-result {
  margin: 6px 0 0;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}

.exec-result.retry {
  color: var(--el-color-warning, #e6a23c);
}

.exec-result.error {
  color: var(--el-color-danger);
}

.exec-step.step-RUNNING {
  border-left-color: var(--ah-primary, #635bff);
}

.exec-step.step-DONE {
  border-left-color: var(--el-color-success-light-3, #95d475);
}

.exec-step.step-FAILED {
  border-left-color: var(--el-color-danger-light-3, #fab6b6);
}

.exec-step.step-RETRYING {
  border-left-color: var(--el-color-warning-light-3, #f3d19e);
}

.answer-block {
  margin-top: 14px;
}

/* 回答标题行：左侧标题保持原下划线，右侧朗读控制与标题同一基线 */
.answer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.answer-head .answer-label {
  flex: 1;
}

/* 朗读控制：准备中 / 播放中 / 暂停共用同一组图标按钮，避免每态换一套 DOM 也不写旁注文字 */
.speak-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding-bottom: 8px;
  white-space: nowrap;
}

/* 单条回答的播放 / 暂停 / 停止：图标按钮，默认低饱和，播放中才点亮主色 */
.speak-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  color: var(--ah-text-secondary, #6b7280);
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.speak-icon .el-icon {
  font-size: 16px;
}

.speak-icon:hover {
  color: var(--el-color-primary, #635bff);
  background: #f2f1ff;
}

/* 这一条正在出声 / 合成：保持主色，用户能一眼看出声音来自哪条回答 */
.speak-icon.active {
  color: var(--el-color-primary, #635bff);
}

.speak-icon.stop:hover {
  color: var(--el-color-danger, #f56c6c);
  background: #fff1f3;
}

.answer {
  margin-top: 8px;
  color: var(--ah-text);
  font-size: 14px;
  line-height: 1.6;
}

.note {
  margin: 8px 0 0;
  color: var(--ah-text-secondary);
  font-size: 12px;
}

/* 图片文字：与「思考过程」同构，默认收起，正文用等宽便于比对识别结果 */
.vision-block {
  margin-top: 8px;
}

.vision-body {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  background: #f7f8fb;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
}

.error {
  margin: 8px 0 0;
  color: var(--el-color-danger);
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3),
.md :deep(h4),
.md :deep(h5),
.md :deep(h6) {
  margin: 0.4em 0 0.25em;
  font-weight: 650;
  line-height: 1.35;
}

.answer :deep(h1) {
  font-size: 17px;
}

.answer :deep(h2) {
  font-size: 16px;
}

.answer :deep(h3) {
  font-size: 15px;
}

.answer :deep(h4) {
  font-size: 14px;
}

.md :deep(blockquote) {
  margin: 0.45em 0;
  padding: 2px 0 2px 10px;
  border-left: 3px solid #d8dce8;
  color: var(--ah-text-secondary, #6b7280);
}

.md :deep(hr) {
  margin: 0.8em 0;
  border: none;
  border-top: 1px solid #e5e7ef;
}

.md :deep(a) {
  color: #635bff;
  text-decoration: none;
}

.md :deep(a:hover) {
  text-decoration: underline;
}

.md :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
}

.md :deep(p) {
  margin: 0.35em 0;
}

.md :deep(ul),
.md :deep(ol) {
  margin: 0.35em 0;
  padding-left: 1.2em;
}

.md :deep(pre) {
  margin: 0.4em 0;
  padding: 8px 10px;
  overflow: auto;
  border-radius: 8px;
  background: #eef0f6;
}

.md :deep(code) {
  font-family: ui-monospace, Consolas, monospace;
}

.md :deep(.md-table-wrap) {
  display: block;
  max-width: 100%;
  margin: 0.45em 0;
  overflow-x: auto;
}

.md :deep(.md-table) {
  display: table;
  width: auto;
  max-width: 100%;
  border-collapse: collapse;
  border: 1px solid #d0d5dd;
  background: #fff;
  font-size: 13px;
  line-height: 1.45;
}

.md :deep(.md-table th),
.md :deep(.md-table td) {
  padding: 6px 10px;
  border: 1px solid #d0d5dd;
  text-align: left;
  vertical-align: top;
  white-space: normal;
}

.md :deep(.md-table th) {
  background: #f3f4f8;
  font-weight: 600;
}

.md :deep(.md-table .align-center) {
  text-align: center;
}

.md :deep(.md-table .align-right) {
  text-align: right;
}

.md :deep(.md-table-pending) {
  margin: 0.45em 0;
  color: var(--ah-text-secondary, #8b8fa3);
  font-size: 13px;
}

.md.think-body :deep(h1),
.md.think-body :deep(h2),
.md.think-body :deep(h3) {
  margin: 0.35em 0 0.2em;
  color: #a3a3a3;
  font-size: 1.05em;
}

.md.think-body :deep(p),
.md.think-body :deep(li) {
  color: inherit;
}

.md.think-body :deep(strong) {
  color: #9a9a9a;
  font-weight: 700;
}

.md.think-body :deep(.md-table) {
  font-size: 1em;
}

.md.think-body :deep(.md-table th),
.md.think-body :deep(.md-table td) {
  padding: 4px 8px;
}

.composer {
  position: relative;
  flex: none;
  margin-top: 12px;
}

/* 缩略图行：紧贴在输入框上方，横向排列，超出可横向滚动 */
.thumbs {
  display: flex;
  flex-wrap: nowrap;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 2px;
  overflow-x: auto;
}

.thumb {
  position: relative;
  flex: none;
  width: 64px;
  height: 64px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
  background: #f7f8fb;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 删除角标：右上角小圆点，hover 才明显，避免压住缩略图主体 */
.thumb-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 13px;
  line-height: 18px;
  text-align: center;
  cursor: pointer;
}

.thumb-remove:hover {
  background: var(--el-color-danger, #f56c6c);
}

.thumb-remove:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.composer-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 图片按钮与语音按钮共用 .voice-icon-btn 的结构；这里只声明它自己的语义类 */

/*
 * 语音图标按钮（喇叭 / 说话 / 取消）：只放图标，不排文字。
 * 图标比常见小按钮略大（16→18px）并统一用主色，disabled 不用灰线框，
 * 而是「保留颜色 + 降透明度 + 拦截点击」，这样一眼能看出它属于哪个能力，而不是坏掉。
 */
.voice-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  color: var(--el-color-primary, #635bff);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 8px;
  cursor: pointer;
  transition: color 0.15s, background 0.15s, opacity 0.15s;
}

.voice-icon-btn .el-icon {
  font-size: 18px;
}

.voice-icon-btn:hover {
  background: #f2f1ff;
}

/* 喇叭开启：实心主色块。开/关不只差一点颜色，形态本身就不一样 */
.speaker-btn.on {
  color: #fff;
  background: var(--el-color-primary, #635bff);
}

.speaker-btn.on:hover {
  background: #5148e0;
}

/* 未绑 ASR：按钮仍可 hover 出说明，但点击不生效，仅降透明度 */
.voice-icon-btn.muted {
  color: var(--el-color-primary, #635bff);
  opacity: 0.35;
  cursor: not-allowed;
}

/* 禁用态不给 hover 底色：避免看起来像可点，实际点了没反应 */
.voice-icon-btn.muted:hover {
  background: transparent;
}

/* 录音中：红色 + 图标脉动，明确「正在采集」而不是普通点击态 */
.voice-icon-btn.recording {
  color: #d03050;
  background: #fff1f3;
}

.voice-icon-btn.recording .el-icon {
  animation: voice-pulse 1s ease-in-out infinite;
}

/* 取消录音：中性色，不与「正在录音」抢视觉 */
.cancel-btn {
  color: var(--ah-text-muted, #9ca3af);
}

@keyframes voice-pulse {
  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.35;
  }
}

/* 原生 file input 只做逻辑承载，不占版面 */
.file-input {
  display: none;
}

.hint {
  color: var(--ah-text-muted);
  font-size: 12px;
}
</style>
