<template>
  <!--
    正文加载中沿用 Element Plus 的 v-loading（与左栏历史列表同一套），不另做骨架：
    只在「当前没有任何消息可展示」时铺遮罩，已有会话之间切换不会多闪一层；
    加载期间不渲染空态。
  -->
  <div
    class="chat"
    :class="{ 'is-workbench': isWorkbench, 'is-composer-only': composerOnly }"
    v-loading="threadLoading && !rows.length"
  >
    <div v-if="!composerOnly" ref="scroller" class="messages" @scroll.passive="onScroll">
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

      <!-- 空态只在「确实没有消息、也不在加载」时出现：否则首屏 / 切会话会先闪一句空话 -->
      <div v-if="!rows.length && !threadLoading" class="empty">
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
          <UserTurnRow :turn="row.turn" :attachments="attachmentsOfTurn(row.turn)" />
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
          <!-- 工作台常驻路由行：入口 → 方案 → 专家，数据来自本轮参与者投影，不另算判定 -->
          <div v-if="isWorkbench && routeChainOf(row.turn).names.length" class="wb-route-chain">
            <span class="wb-route-chain-label">{{ routeChainOf(row.turn).label }}</span>
            <template v-for="(name, routeIndex) in routeChainOf(row.turn).names" :key="'route-' + routeIndex">
              <span v-if="routeIndex > 0" class="wb-route-chain-arrow" aria-hidden="true">→</span>
              <span class="wb-route-chain-node" :class="{ 'is-entry': routeIndex === 0 }">{{ name }}</span>
            </template>
          </div>
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
            <div v-if="!isWorkbench && row.turn.status !== 'streaming'" class="answer-head">
              <div class="section-label answer-label">最终回答</div>
              <SpeakActions
                :speak-key="planAnswerKey(row.turn)"
                :speaking="isSpeaking(planAnswerKey(row.turn))"
                :preparing="isPreparing(planAnswerKey(row.turn))"
                :playing="isPlaying(planAnswerKey(row.turn))"
                :hint="speakHintOf(planAnswerKey(row.turn))"
                @toggle="toggleSpeak(planAnswerKey(row.turn), row.flow.answer)"
                @stop="stopSpeech"
              />
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
            <router-link
              v-if="showModelConfigLink(err)"
              class="wb-error-action"
              to="/access/models"
            >去绑定模型</router-link>
            <router-link
              v-if="showReceptionConfigLink(err)"
              class="wb-error-action"
              :to="receptionConfigHref"
            >去配置对话路由</router-link>
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
          <!-- 工作台常驻路由行：入口 → 方案 → 专家，数据来自本轮参与者投影，不另算判定 -->
          <div v-if="isWorkbench && routeChainOf(row.turn).names.length" class="wb-route-chain">
            <span class="wb-route-chain-label">{{ routeChainOf(row.turn).label }}</span>
            <template v-for="(name, routeIndex) in routeChainOf(row.turn).names" :key="'route-' + routeIndex">
              <span v-if="routeIndex > 0" class="wb-route-chain-arrow" aria-hidden="true">→</span>
              <span class="wb-route-chain-node" :class="{ 'is-entry': routeIndex === 0 }">{{ name }}</span>
            </template>
          </div>
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
                <!--
                  单卡渲染整体下沉到 WorkbenchActorCard：壳层只负责投影、hold 与整会话可点规则。
                  点选仍回到 onChoice，不另发用户气泡，也不在卡片里判断「是不是最后一条」。
                  入口预处理排序所需的 beforeAttachments 只对网关卡回溯，其余卡传共用空表。
                -->
                <WorkbenchActorCard
                  :turn="row.turn"
                  :card="card"
                  :workbench="isWorkbench"
                  :hold="actorHoldOf(row.turn, card)"
                  :choice-enabled="choiceClickable(row.turn)"
                  :can-config-model="canConfigModel"
                  :can-config-reception="canConfigReception"
                  :reception-config-to="receptionConfigHref"
                  :page-active="pageActive"
                  :before-attachments="card.kind === 'gateway' && card.preprocess ? attachmentsBefore(row.turn) : NO_ATTACHMENTS"
                  @choose="onChoice(row.turn, $event)"
                  @other="onChoiceOther(row.turn)"
                />
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
            <!-- 图片文字提取结果：本轮素材，排在思考与终答之前，可折叠。入口预处理不走这里，收在网关卡内 -->
            <PreprocessResultBox
              v-if="block.kind === 'vision' && !isWorkbench"
              title="图片文字"
              :items="visionItems(block.text)"
            />
            <template v-else-if="block.kind === 'assistant' && showLooseAssistant(isWorkbench, participantFeedHasActor(row.turn)) && index === finalAnswerIndex(row.turn.blocks)">
              <!-- 工作台不走这条：正文只出现在参与者卡里。调试皮肤在还没有参与者卡时仍显示原文 -->
              <!-- 终答标题只在这一轮结束后出现。进行中的最后一段先出正文，不提前挂「最终回答」 -->
              <div class="answer-block">
              <div v-if="!isWorkbench && row.turn.status !== 'streaming'" class="answer-head">
                <div class="section-label answer-label">最终回答</div>
                <SpeakActions
                  :speak-key="blockAnswerKey(row.turn, block)"
                  :speaking="isSpeaking(blockAnswerKey(row.turn, block))"
                  :preparing="isPreparing(blockAnswerKey(row.turn, block))"
                  :playing="isPlaying(blockAnswerKey(row.turn, block))"
                  :hint="speakHintOf(blockAnswerKey(row.turn, block))"
                  @toggle="toggleSpeak(blockAnswerKey(row.turn, block), block.text)"
                  @stop="stopSpeech"
                />
              </div>
              <div
                class="md answer"
                :class="{ 'is-table-scroll': answerClampOf(block.text) === 'table' }"
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
              <router-link
                v-if="showModelConfigLink(block.text)"
                class="wb-error-action"
                to="/access/models"
              >去绑定模型</router-link>
              <router-link
                v-if="showReceptionConfigLink(block.text)"
                class="wb-error-action"
                :to="receptionConfigHref"
              >去配置对话路由</router-link>
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
    </div>

    <!--
      输入条整块下沉到 ChatComposer：正文 / 附件 / 草稿 / 语音 / @ 面板 / 拖拽 / 停止发送自带状态。
      composerOnly（MA 运行页）由上面 `v-if="!composerOnly"` 只隐藏消息区，这里始终是同一个根级
      子组件，DOM 与拆前一致，MaRunFlow 的 composer-only 用法零改动。
    -->
    <ChatComposer
      ref="composerRef"
      :workbench="isWorkbench"
      :sending="sending"
      :can-send="canSend"
      :session-ready="sessionReady"
      :blocked-hint="blockedHint"
      :thread-loading="threadLoading"
      :draft-key="draftKey"
      :entry-composer="entryComposer"
      :vision-enabled="visionEnabled"
      :asr-enabled="asrEnabled"
      :agent-id="agentId"
      :can-pick-route="canPickRoute"
      :route-candidates="routeCandidates"
      :selected-route="selectedRoute"
      :can-stop="canStop"
      :resuming="resuming"
      :card-running="cardRunning"
      :waiting-reply="waitingReply"
      :custom-answer="customAnswerActive"
      :auto-speak="autoSpeak"
      :speaker-hint="speakerHint"
      @send="onComposerSend"
      @stop="onComposerStop"
      @pick-route="onComposerPickRoute"
      @clear-route="emit('clear-route')"
      @toggle-auto-speak="toggleAutoSpeak"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 运行 / 工作台对话区。所属域：runtime。只展示传入的时间线，不自己请求接口。
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
import { computed, nextTick, onErrorCaptured, onMounted, onUpdated, reactive, ref, watch } from 'vue'
import type {
  AssistantBlock,
  ChatTurn,
  RouterTraceView
} from '@/types/runtime'
import type { WorkbenchRouteOption } from '@/types/workbench'
import { ElMessage } from 'element-plus'
import { useSpeechPlayer } from '@/composables/speechPlayer'
import {
  entryRouteTextsOf,
  hasRouterTrace,
  finalAnswerIndex,
  foldInterimAnswers,
  isPlanFlow,
  lanesForDebugSkin,
  planStateText,
  processLanes,
  splitPreprocessItems,
  stepStateText,
  turnSections
} from '@/components/runtime/trialMessages'
import {
  attachmentsOfTurn,
  type OutgoingFile,
  type UserAttachmentView
} from '@/components/runtime/userMessageImages'
import PreprocessResultBox from '@/components/runtime/PreprocessResultBox.vue'
import type { TurnSections } from '@/components/runtime/trialMessages'
import ProcessTimeline from '@/components/runtime/ProcessTimeline.vue'
import { buildParticipantFeed, feedWaitsForEntry, isFinishedStatusShell, showLooseAssistant, visitParticipantCards } from '@/components/runtime/workbench/workbenchParticipantFeed'
import { ParticipantFeedCache } from '@/components/runtime/workbench/workbenchFeedCache'
import type {
  ParticipantCard,
  ParticipantChoiceOption
} from '@/components/runtime/workbench/workbenchParticipantFeed'
import { schemeChildrenSettled } from '@/components/runtime/workbench/workflowOutputView'
import {
  answerClampOf,
  foldOpenOf,
  nextFoldValue
} from '@/components/runtime/workbench/workbenchContentBox'
import RouterTimeline from '@/components/runtime/RouterTimeline.vue'
import FileCard from '@/components/runtime/FileCard.vue'
import WorkbenchActorTree from '@/components/runtime/workbench/WorkbenchActorTree.vue'
import WorkbenchActorCard from '@/components/runtime/workbench/chat/WorkbenchActorCard.vue'
import UserTurnRow from '@/components/runtime/workbench/chat/UserTurnRow.vue'
import SpeakActions from '@/components/runtime/workbench/chat/SpeakActions.vue'
import ChatComposer from '@/components/runtime/workbench/chat/ChatComposer.vue'
import { useChatFollow } from '@/components/runtime/workbench/chat/useChatFollow'
import { loadToolTitles } from '@/components/runtime/workbench/chat/toolTitles'
import { createActorIdentity, routeAgentName } from '@/components/runtime/workbench/chat/actorIdentity'
import { displayAnswerText, displayErrorTextOf, markdownText, thinkTextOf } from '@/components/runtime/workbench/chat/chatText'
import ElapsedSeconds from '@/components/runtime/ElapsedSeconds.vue'
import AgentLookArt from '@/components/agent/AgentLookArt.vue'
import { isModelConfigActionable, isRouteConfigActionable } from '@/utils/workbenchFriendlyError'

const props = defineProps<{
  turns: ChatTurn[]
  sending: boolean
  canSend: boolean
  sessionReady: boolean
  /** 不能发送时的说明。结案和未选会话用不同文案 */
  blockedHint?: string
  /**
   * 正文是否仍在加载（首屏拉历史 / 打开某条会话取事件期间）。
   *
   * <p>为 true 时不出空态、也不显示「请先新建会话或从左侧选择」：
   * 状态还没落定，先闪一句空话再换成消息正是要消掉的那一下。
   * 缺省 false：管理端运行与 MA 运行保持原样。
   */
  threadLoading?: boolean
  /**
   * 工作台输入草稿键：按会话隔离未发送正文与附件。
   *
   * <p>真实会话用主键、临时草稿用新建代次（见外层 WorkbenchChat）；缺省不启用，
   * 管理端运行 / MA 运行仍是单一输入，行为与改前一致。
   */
  draftKey?: string
  /**
   * 是否已与后端断开、只能等落库。
   * 整页刷新后前端不再持有 SSE，增量拿不到；此时状态条如实标注，不假装还在思考。
   */
  detached?: boolean
  /** 是否有批准计划权限。无权限时计划卡只读展示 */
  canApprove?: boolean
  /**
   * 本会话是否有工作流停在「等待用户回复」。
   *
   * <p>此时这一轮已不在流式中（请求线程早结束），但挂起的运行还要能停：
   * 传 true 时停止按钮与发送按钮同时出现，发送即把那句回复送回同一条运行。
   */
  waitingReply?: boolean
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
  /**
   * 入口 Router 输入条（首页综合对话 / MA 运行入口）。
   * 为 true 时始终展示附件和麦克风；录音作为本轮附件发出，识别发生在预处理步骤。
   * 管理端已选专家对话不要开这个开关。
   */
  entryComposer?: boolean
  /**
   * 只保留输入条。MA 运行页中栏卡片自己画，复用这一份待发列表，不另起第三套。
   */
  composerOnly?: boolean
  /** 工作台空状态快捷提问；点卡片即填入并发送 */
  quickPrompts?: string[]
  /** @ 面板候选（本会话快照方案启用项）；只展示名，不暴露 route_key */
  routeCandidates?: WorkbenchRouteOption[]
  /** 已选定的下一轮强制线路（提及片） */
  selectedRoute?: WorkbenchRouteOption | null
  /** 是否允许打开 @ 面板 */
  canPickRoute?: boolean
  /**
   * 工作台运行中是否允许停止。
   * 缺省（管理端运行）视为可用，保持既有「停止」行为不变；
   * 工作台会话尚未创建完成时由外层传 false，按钮置灰而不是假装已停。
   */
  canStop?: boolean
  /**
   * 是否正在续订直播（刷新后重新接上这一轮，含工作流点选续跑这一拍）。
   *
   * <p>这一拍没有在途流，但运行还在进行；输入条据此显示停止而不是发送。
   * 缺省 false：管理端运行不传，行为与拆前一致。
   */
  resuming?: boolean
  /**
   * 最后一张工作流卡看起来还在跑（刷新丢了接管但卡片仍在转圈）。
   *
   * <p>与 {@code resuming} 并列：那一项是「本页重新接上了这一轮」，这一项是
   * 「卡片自己说还在跑」。刷新后接管没命中时只有后者为真，输入区仍要显示停止。
   * MA / 单 Agent 轮里没有工作流块，恒为 false。
   */
  cardRunning?: boolean
  /** 是否有模型配置权限：无权限时不渲染「去绑定模型」深链 */
  canConfigModel?: boolean
  /** 是否有对话路由配置权限：无权限时不渲染「去配置对话路由」深链 */
  canConfigReception?: boolean
  /**
   * 「去配置对话路由」深链地址。由工作台按当前入口方案拼 `?edit=<id>&tab=model`；
   * 缺省退回多智能体列表，管理端运行不受影响。
   */
  receptionConfigTo?: string
}>()

const emit = defineEmits<{
  /**
   * 发消息：正文 / 图片 / 文件 / 图文顺序，与拆前一致。
   *
   * <p>第五位 {@code customAnswer=true} 表示这句是从「其它 / 自填」输入条发出来的答复，
   * 外层据此决定走 {@code /choice} 的 text 续作；其余入口不传，协议不变。
   */
  send: [
    content: string,
    images?: string[],
    files?: OutgoingFile[],
    order?: Array<'image' | 'file'>,
    customAnswer?: boolean
  ]
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
  /** 运行中点停止。管理端运行与工作台共用；工作台外层接 cancelWorkbenchSession */
  stop: []
}>()

/**
 * 输入条句柄：空态快捷卡只通过 {@link ChatComposer.sendPrompt} 走同一条发送逻辑，
 * 壳层不再另存一份正文（第二个真源会让草稿与附件状态对不上）；
 * 「其它 / 自填」也用它把焦点交回输入框。
 */
const composerRef = ref<InstanceType<typeof ChatComposer>>()

/**
 * 是否处于「其它 / 自填」答复态。
 *
 * <p>点平台追加的「其它，我自己说」只置这一位并聚焦输入框，<b>不写回执、不发请求</b>；
 * 用户自己写好并回车 / 点发送后，才由输入条按答复校验并交给外层：
 * 单 Agent 选项仍开着时走 {@code /choice} 的 text 续作（回同一位专家、不重新判路由），
 * 工作流 / 方案等待仍走普通发送续跑。
 * 发送 / 点普通选项 / 停止 / 切会话任一发生即退出，避免下一条普通提问被当成答复去校验。
 */
const customAnswerActive = ref(false)

/**
 * 转发输入条的发消息事件：正文 / 图片 / 文件 / 图文顺序原样交给外层，协议不变。
 *
 * <p>自填是一次性的：这里先把状态位取下来（下一条普通提问不再按答复口径校验），
 * 再把「这句是不是自填答复」作为第五位参数交给外层；外层只在单 Agent 选项仍开着时
 * 才改走 {@code /choice} 的 text 通道，工作流 / 方案等待仍按原发送续跑。
 *
 * @param content 正文（只发附件时为空串）
 * @param images  图片 dataUrl 列表
 * @param files   文件列表（含 dataUrl）
 * @param order   附件在待发条里的原始顺序
 */
function onComposerSend(
  content: string,
  images?: string[],
  files?: OutgoingFile[],
  order?: Array<'image' | 'file'>
) {
  // 自填是一次性的：发出即退出，下一条普通提问不再按答复长度 / 控制字符校验
  const customAnswer = customAnswerActive.value
  customAnswerActive.value = false
  emit('send', content, images, files, order, customAnswer)
}

/** 转发停止：自填态随这一轮一起结束，避免停掉之后下一条仍被当成答复 */
function onComposerStop() {
  customAnswerActive.value = false
  emit('stop')
}

/**
 * 转发输入条的 @ 选线路：候选只在输入条里展示，壳层只把 routeKey 交给外层。
 *
 * @param routeKey 选中的线路键
 */
function onComposerPickRoute(routeKey: string) {
  emit('pick-route', routeKey)
}

/** 是否工作台沉浸皮肤 */
const isWorkbench = computed(() => props.skin === 'workbench')
/** 工作台思考过程默认收起；调试仍默认展开 */
const thinkDefaultOpen = computed(() => !isWorkbench.value)

/**
 * 参与者身份解析（形象 / 展示名 / 简介）。
 *
 * <p>行首 AI 头像、投影回调（`lookOf` / `descriptionOf`）与参与者卡共用同一份实现；
 * 入参一律用 getter，候选池异步到达、@ 选中线路与 agentId 变化都会立刻反映。
 */
const actorIdentity = createActorIdentity({
  routeCandidates: () => props.routeCandidates || [],
  selectedRoute: () => props.selectedRoute,
  agentId: () => props.agentId,
  routeHint: () => props.routeHint,
  // 泳道解析在下方声明（函数声明提升），只读本轮块，不参与循环依赖
  lanesOf
})
const { lookOf, descriptionOf } = actorIdentity
/** 行首 AI 头像形象 */
const assistantLook = actorIdentity.assistantLook
/** 行首 AI 头像内联样式 */
const assistantAvatarStyle = actorIdentity.avatarStyle
/** 工作台 AI 名称行文案 */
const assistantDisplayName = actorIdentity.displayName

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

/** 非计划流程里「思考过程」的折叠键后缀。 */
const THINK_KEY = 'think'
/** 计划阶段思考的折叠键后缀。 */
const PHASE_KEY = 'plan-phase'

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

const scroller = ref<HTMLElement>()
/**
 * 跟滚组合式：挂在现有 `.messages` 滚动容器上，生命周期与帧清理由组合式自己收口。
 * 发问 / 切会话强制回底；用户上翻后不拽回。模板只需绑定 onScroll，onUpdated 调 scheduleFollowBottom。
 */
const { onScroll, scheduleFollowBottom } = useChatFollow(
  scroller,
  () => props.turns,
  () => props.sending
)
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
    // 参与者卡自己的四行实测 / 思考节拍缓存随卡片实例走，这里只清投影
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
      block?.kind === 'preprocess' ||
      block?.kind === 'reasoning' ||
      block?.kind === 'tool' ||
      block?.kind === 'assistant' ||
      block?.kind === 'file' ||
      block?.kind === 'error'
  )
}

/**
 * 整轮结束后，把仍标着 running 的工具步落到终态。
 *
 * <p>工具调用行以 SSE 的 tool_result 收口；连接中断、手动停止或历史里缺结果帧时，
 * 这一行会永远停在「正在调用」并转圈。轮次已非 streaming 就说明它不会再等来结果，
 * 此时按本轮终态收口：整轮失败 / 中断 → error，正常完成 → done。
 *
 * <p>递归到方案框内的子卡与汇总卡，避免只收口最外层。
 *
 * @param cards    本轮参与者卡片
 * @param terminal 终态；只允许 done / error，与工具胶囊三态一致
 */
function settleRunningToolSteps(cards: ParticipantCard[], terminal: 'done' | 'error') {
  for (const card of visitParticipantCards(cards)) {
    for (const step of card.steps) {
      if (step.type === 'tool_call' && step.state === 'running') {
        step.state = terminal
      }
    }
  }
}

/**
 * 用户停止一轮后，把仍停在「运行中」的工作流卡落到中断终态。
 *
 * <p>停止时后端会取消工作流实例，但这一轮被中断后最终快照不一定还能送到页面：
 * 卡片会保留在最后一帧「运行中」，节点仍按旧状态转圈。这里在展示层补
 * {@code closed='aborted'}，只影响工作台投影出来的卡片，不改块里的原始快照；
 * 后续真快照到达会重建卡片并覆盖它。普通断流不调用本函数——那时后端工作流
 * 可能仍在执行，不能谎报成已取消。
 *
 * <p>只在工作流自身状态仍是非终态时补：SUCCESS / FAILED / CANCELLED 以快照为准，
 * 不把真实失败改写成中断。
 *
 * @param cards 本轮参与者卡片（含方案框内的子卡）
 */
function settleRunningWorkflowCards(cards: ParticipantCard[]) {
  for (const card of visitParticipantCards(cards)) {
    if (!card.workflow || card.closed) {
      continue
    }
    const status = (card.workflow.status || '').toUpperCase()
    // 终态快照已经说清楚了，不再叠一个展示态
    if (status === 'SUCCESS' || status === 'FAILED' || status === 'CANCELLED') {
      continue
    }
    card.closed = 'aborted'
  }
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
        // 续作轮排除 participant begin 占位：它不是入口判定，不能拿来合成网关卡
        routeTexts: entryRouteTextsOf(turn.blocks, turn.continuation === true),
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
      // 本轮已收口：没有等到 tool_result 的调用行必须落到终态，否则会一直「正在调用」转圈
      if (turn.status !== 'streaming') {
        settleRunningToolSteps(cards, turn.status === 'error' ? 'error' : 'done')
        /*
         * 工作流卡只在「用户停止」这一种收口上补中断态：
         * 停止后最后一张快照可能仍是「运行中」，节点会继续转圈；
         * 而普通断流不代表后端工作流已停，不能把它谎报成已取消。
         */
        if (turn.blocks.some((block) => block.kind === 'error' && /已中断|已停止|已取消/.test(block.text))) {
          settleRunningWorkflowCards(cards)
        }
      }
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

/** 一轮可展示的路由链：入口 + 方案 + 专家，名字全部来自已有投影，不另算判定 */
interface WorkbenchRouteChain {
  /** 有入口卡时写「入口」，续作轮没有入口时写「交给」 */
  label: '入口' | '交给'
  /** 依次是入口、方案（可能多层）、真正执行的专家；数据不足时为空 */
  names: string[]
}

/**
 * 从现网参与者投影里取「这轮交给谁」，不重新发明路由判定。
 *
 * <p>口径：
 * <ul>
 *   <li>入口卡（kind=gateway）的标题就是配置里的入口名（如 Router）；</li>
 *   <li>方案卡按 begin 收在 children 里，先方案后其子专家；</li>
 *   <li>方案汇总（kind=conclusion / summary）不是路由节点，跳过；</li>
 *   <li>名字只取投影已有的 title，数据不足时返回空数组，绝不编造专家名。</li>
 * </ul>
 *
 * @param turn 本轮助手消息
 * @return 路由链；不是工作台或还没有参与者投影时 names 为空
 */
function routeChainOf(turn: ChatTurn): WorkbenchRouteChain {
  const empty: WorkbenchRouteChain = { label: '交给', names: [] }
  if (!isWorkbench.value) {
    return empty
  }
  /*
   * 续跑轮本身没有入口判定，也不是「新交给谁」：等待回复 / 选项续作把内容送回原运行，
   * 同一张执行卡继续往下走。这里再画一条「交给 X」等于把同一条运行伪装成一次新分发。
   * 只有带工作流卡的续跑轮这样收口；单专家选项续作没有工作流卡，保持原有展示。
   */
  const resumedWorkflow = turn.continuation === true
    && (Array.isArray(turn.blocks) ? turn.blocks : [])
      .some((block) => (block?.kind === 'workflow' && !!block.workflow)
        // 工作流身份 begin 先到、卡片快照后到：这一小段也要算续跑，避免闪一下「交给 X」
        || (block?.participantKind || '').trim() === 'workflow')
  if (resumedWorkflow) {
    return empty
  }
  const cards = participantFeedOf(turn)
  if (!cards.length) {
    return empty
  }
  const gateway = cards.find((card) => card.kind === 'gateway')
  const names: string[] = []
  const push = (title: string) => {
    const name = (title || '').trim()
    // 同名相邻只留一次：单 Agent 时入口与执行者同名，不能画成 Router → Router
    if (name && names[names.length - 1] !== name) {
      names.push(name)
    }
  }
  if (gateway) {
    push(gateway.title)
  }
  const walk = (list: ParticipantCard[]) => {
    for (const card of list) {
      if (card.kind === 'gateway') {
        // 入口自身已入链；它的子卡（若有）仍是真正的下游
        if (card.children?.length) {
          walk(card.children)
        }
        continue
      }
      // 方案汇总不是「交给谁」，否则会出现「方案 → 方案结论」这种假链路
      if (card.kind === 'conclusion') {
        continue
      }
      push(card.title)
      if (card.children?.length) {
        walk(card.children)
      }
    }
  }
  walk(cards)
  return { label: gateway ? '入口' : '交给', names }
}

/** 「去配置对话路由」的实际地址：工作台传具体方案的抽屉，其它入口退回列表 */
const receptionConfigHref = computed(
  () => (props.receptionConfigTo || '').trim() || '/orchestration/multi-agent'
)

/**
 * 该条错误是否该给「去配置对话路由」。
 *
 * <p>只认分类为 route 的可行动错误；手动停止 / 中断留下的「判路由失败：已中断」
 * 不应再引导去改配置——那是本轮被打断，不是配置缺失。判定收在
 * {@link isRouteConfigActionable}，参与者卡共用同一口径。
 *
 * @param text 原始错误文案
 * @return true 表示渲染路由配置深链
 */
function showReceptionConfigLink(text: string): boolean {
  if (!isWorkbench.value || !props.canConfigReception) {
    return false
  }
  return isRouteConfigActionable(text)
}

/**
 * 该条错误是否该给「去绑定模型」。
 *
 * <p>与路由同一口径：中断不是模型没配，保留原话即可，不再挂深链。判定收在
 * {@link isModelConfigActionable}，参与者卡共用同一口径。
 *
 * @param text 原始错误文案
 * @return true 表示渲染模型中心深链
 */
function showModelConfigLink(text: string): boolean {
  if (!isWorkbench.value || !props.canConfigModel) {
    return false
  }
  return isModelConfigActionable(text)
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
    routeTexts: entryRouteTextsOf(turn.blocks, turn.continuation === true),
    entryParticipantId: turn.entryParticipantId,
    expectEntry: turn.continuation !== true
  })
}

/**
 * 拉一次工具项目录，只用已有的 title 字段。
 *
 * <p>失败（无权限、网络）只留空表，不在前端写死英文名对照。
 * 与工作流卡共用同一份缓存，避免两边各请求一次目录。
 */
async function loadToolFaceTitles() {
  await loadToolTitles(isWorkbench.value)
}

/**
 * 所属方案的子块是否还没全部结束。为 true 时工作流正文先按四行展示，不整屏。
 *
 * <p>这是壳层对「这条运行用到谁、老板块是否收口」的判定，不是工作流卡内部逻辑；
 * 工作流卡只接收结果，不自己遍历方案树。
 *
 * @param turn 本轮助手消息
 * @param card 参与者卡片
 * @return true 表示先按四行展示
 */
function actorHoldOf(turn: ChatTurn, card: ParticipantCard): boolean {
  return !schemeChildrenSettled(participantFeedOf(turn), card)
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
  // 点了普通项就退出自填态：接下来不会再按答复口径校验下一句
  customAnswerActive.value = false
  emit('choice-selected', option.id, option.label)
}

/**
 * 点平台追加的「其它，我自己说」：只进入输入，不提交任何答复。
 *
 * <p>刻意不在这里写回执 / 清选项 / 发请求：那样刷新后会留下「已经答复」的假状态；
 * 真正的答复要等用户输入后走输入条的发送（工作流 / MA 由外层 send 当答复续跑）。
 * 必须和普通选项同一道可点闸：历史轮、正在发送时点了也不该把焦点抢走。
 *
 * @param turn 选项所在那轮助手消息（仅用于可点性复核）
 */
function onChoiceOther(turn: ChatTurn) {
  if (!choiceClickable(turn)) {
    return
  }
  customAnswerActive.value = true
  // 选项块在消息流里，输入条在底部：下一拍把焦点交给输入框，用户可直接开始写
  void nextTick(() => composerRef.value?.focus())
}

/**
 * 切会话即退出自填态：上一条会话的「答复」语义不能带到另一条会话的普通提问里。
 * 停止 / 发送 / 点普通选项的退出点分别在各自回调里，这里只兜会话切换。
 */
watch(
  () => props.draftKey,
  () => {
    customAnswerActive.value = false
  }
)

/**
 * 首次挂载时加载工具展示名。
 * 思考面板的展开 / 收起只认用户点击，不再在挂载时批量写入收起状态。
 */
onMounted(() => {
  void loadToolFaceTitles()
})

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

// 切 Agent 必须停掉正在进行的朗读：上一条回答的声音不能串到新 Agent 的会话里。
// 录音的取消随输入条一起走（ChatComposer 监听同一个 agentId），壳层只留播放器单例。
watch(
  () => props.agentId,
  () => {
    stopSpeech()
  }
)

/** 用户点标题后的折叠覆盖；未写入时用调用方给的默认态。键为「轮 key + 区段后缀」。 */
const foldOverride = reactive<Record<string, boolean>>({})
/** 长工具结果是否展开。 */
const resultOpen = reactive<Record<string, boolean>>({})

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

// 每次组件更新（流式增量 / 新轮次 / 排版变化）后安排一次跟滚；不再深监听整棵 turns。
// 参与者卡内部的四行实测与中间框跟滚随卡片走（`WorkbenchActorCard` 自己的 onUpdated）。
onUpdated(() => {
  scheduleFollowBottom()
})

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

/** 状态码换成商城同款短句。未知阶段不显示空标签。 */
function phaseLabel(phase?: string) {
  if (phase === 'planning') {
    return '任务规划中'
  }
  if (phase === 'preprocess') {
    return '正在识别附件'
  }
  if (phase === 'speech') {
    return '正在转写语音'
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
 * 管理端图片文字预览。按图序拆开，默认由识别框折叠。入口页不走这里。
 *
 * @param text 视觉事件正文
 * @return 识别框条目
 */
function visionItems(text?: string) {
  return splitPreprocessItems(text || '')
}

/**
 * 这条助手轮前面最近的用户附件。识别框用它排顺序，不另存一份。
 *
 * @param turn 助手轮
 * @return 用户附件
 */
function attachmentsBefore(turn: ChatTurn): UserAttachmentView[] {
  const list = rows.value
  const index = list.findIndex((row) => row.turn.key === turn.key)
  for (let i = index - 1; i >= 0; i -= 1) {
    if (list[i].turn.role === 'user') {
      return attachmentsOfTurn(list[i].turn)
    }
  }
  return []
}

/**
 * 非网关卡共用的空附件表。
 *
 * <p>只有入口卡要用本轮用户附件排识别顺序；专家 / 方案 / 工作流传同一份空数组，
 * 避免每张卡每次渲染都新建一个 `[]`（参与者卡按 props 浅比较时也不会白更新）。
 * 这一份只读使用，任何地方都不得 push / splice。
 */
const NO_ATTACHMENTS: UserAttachmentView[] = []

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
  return lanesForDebugSkin(foldInterimAnswers(item.blocks))
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
 * 思考与终答共用轻量 Markdown。流式走安全前缀+表渐进，结束后完整终渲。
 * 原文标签不会执行。
 *
 * <p>实现收在 {@link markdownText}，参与者卡共用同一份缓存口径；
 * 壳层只剩这一层薄包装，管理端调试皮肤也走这里。
 *
 * @param slot      渲染位置键（轮次 + 块 + 区段），同一位置必须稳定不变
 * @param value     Markdown 原文
 * @param streaming 是否流式渲染（与终态分开缓存，避免脏 HTML）
 * @return 可直接 v-html 的 HTML
 */
function markdown(slot: string, value: string, streaming = false) {
  return markdownText(slot, value, streaming)
}

/**
 * 工作台思考区文本：抹掉 route_key 等内部键；调试形态原样保留排障信息。
 * 只影响渲染，不改思考过程的数据源。
 *
 * @param raw 思考正文 / 工具行 / 路由决策 JSON
 * @return 可展示文本
 */
function thinkText(raw: string) {
  return thinkTextOf(raw, isWorkbench.value)
}

/**
 * 工作台正文展示：剥文首人设/调试小节标题；调试形态原样。
 * 直播终态和回放共用这一条，只影响渲染，不改 turns 落库内容。
 *
 * @param raw 结果框原文（助手正文或工作流终答）
 * @return 交给 Markdown 的可见正文
 */
function displayAnswer(raw: string) {
  return displayAnswerText(raw, isWorkbench.value)
}

/**
 * 工作台错误展示：硬核不可用 → 友好灰字；其它原样。调试形态原样红字。
 */
function displayErrorText(raw: string) {
  return displayErrorTextOf(raw, isWorkbench.value)
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
 * 空状态快捷卡片：交给输入条填入并立刻发送（与点发送等价）。
 *
 * <p>正文、附件与发送前校验都在 ChatComposer 里，壳层不另存一份 `text`；
 * 这里只通过 expose 的 sendPrompt 走同一条 submit。
 *
 * @param prompt 卡片文案
 */
function sendQuick(prompt: string) {
  composerRef.value?.sendPrompt(prompt)
}

</script>

<style scoped src="../../styles/chat-markdown.css"></style>
<style scoped src="../../styles/workbench-chat.css"></style>

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

/* MA 运行页只复用输入条：不占满中栏高度，待发列表仍是这一份 */
.chat.is-composer-only {
  flex: none;
  height: auto;
  align-items: stretch;
}

.chat.is-composer-only .composer,
.chat.is-workbench.is-composer-only .composer {
  max-width: none;
  margin-top: 0;
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

/* 附件在上、文字气泡在下。没有文字时只留附件条，不套空气泡 */
.user-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  max-width: 78%;
}

.speak-row.is-assistant {
  justify-content: flex-start;
}

.speak-row.is-assistant .card {
  flex: 1;
  min-width: 0;
}

/* 头像基类（.msg-avatar / img）与文件行、结果框外壳、错误深链已收进
   `styles/workbench-chat.css`，这里只留工作台行首「方座」形态的差异 */

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

/* 用户行已下沉为子组件：作用域样式用 :deep 命中其内部气泡，工作台皮肤与原观感一致 */
.chat.is-workbench :deep(.bubble) {
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

/* 结果框外壳、四行截断、文件行与终态说明已随参与者卡下沉；
   共享部分（.answer-block / .wb-file-row / .wb-error-action）见 styles/workbench-chat.css */

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

/* 消息流：网关 / 方案 / 专家同一种聊天块，一律左对齐头像 + 名称 + 一块内容框。
   思考默认收起（约 200px 内滚动），本步结果写在内容框里，不再有居中判定条 / 路由条 */
.wb-actors {
  display: flex;
  flex-direction: column;
  gap: 18px;
  margin-top: 4px;
}

/*
 * 本轮路由行：常驻在助手块顶部，默认可见。
 * 名字全部来自参与者投影（入口 / 方案 / 专家），数据不足时外层不渲染。
 */
.chat.is-workbench .wb-route-chain {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.6;
}

.wb-route-chain-label {
  flex: none;
  padding: 1px 8px;
  border-radius: 999px;
  background: #f4f4ff;
  color: var(--ah-primary, #635bff);
  font-weight: 600;
}

.wb-route-chain-node {
  color: #374151;
  font-weight: 600;
}

.wb-route-chain-node.is-entry {
  color: var(--ah-primary, #635bff);
}

.wb-route-chain-arrow {
  color: #9ca3af;
}

/*
 * 单卡（顶行头像 + 名称 + 角标、内容框、思考折叠、工具胶囊、工作流卡、引用与选项）
 * 已整体下沉到 `workbench/chat/WorkbenchActorCard.vue`；壳层只保留上方路由行与下方容器。
 * 过程块的降档底色、思考框形态由参与者卡与 FoldSection 自己承担。
 */

/* 有思考面板时行首不再有大头像，卡片直接占满宽度 */
.chat.is-workbench .speak-row.is-assistant > .card {
  width: 100%;
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

.error {
  margin: 8px 0 0;
  color: var(--el-color-danger);
}


</style>
