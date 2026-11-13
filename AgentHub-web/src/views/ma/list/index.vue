<template>
  <div class="page">
    <!--
      页头：本页同时承载普通方案与对话入口（对话路由）。
      入口不另钉顶部总控行，就在列表里用「对话入口」角标区分。
    -->
    <PageHeader title="多智能体" description="方案总览：普通方案与对话路由同列。协作模式只有意图路由分发和路由后串行；路由池用列表维护，方案可直接运行。">
      <template #extra>
        <el-button v-if="canEdit" type="primary" @click="openCreate">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建方案
        </el-button>
      </template>
    </PageHeader>

    <!-- 筛选：关键字匹配名称/编码；状态看 ma_def.enabled；模板可筛出对话入口 -->
    <div class="filter-bar">
      <el-form class="filter-form" inline @submit.prevent="search">
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" class="filter-input" clearable placeholder="名称或编码" @keyup.enter="search" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.enabled" class="filter-select" clearable placeholder="全部">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="协作模式">
          <el-select v-model="query.template" class="filter-select" clearable placeholder="全部">
            <!-- 筛选含入口类型：入口与普通方案同列展示，运营要能按类型把它筛出来 -->
            <el-option v-for="item in MA_LIST_FILTER_TEMPLATES" :key="item" :label="maTemplateLabel(item)" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" @click="search">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" @click="resetFilter">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <section class="panel">
      <el-table v-loading="loading" :data="rows" class="ma-table" :row-class-name="() => 'ma-row'" @row-click="onRowClick">
        <!-- 最左序号列：跨页连续，纯展示，不参与接口排序 -->
        <el-table-column v-bind="indexColumnProps" min-width="60"/>
        <!-- 名称/编码：方案主键之外的业务标识，点名称与点整行都进编辑抽屉 -->
        <el-table-column label="名称" min-width="220" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-name-wrap">
              <!-- 方案自己的形象：未配置时按方案名走 8 套机器人兜底，不再借根 Agent -->
              <AgentAvatar :name="row.name" :config="rowAvatarConfig(row)" :size="28" />
              <button type="button" class="cell-name" @click.stop="openEdit(row, 'basic')">{{ row.name }}</button>
            </span>
            <!-- 入口与普通方案同列：用「对话入口」角标点出它的身份，悬浮可读完整备注 -->
            <el-tooltip
              v-if="isEntryRouterTemplate(row.template)"
              :content="row.description || MA_ENTRY_ROUTER_DESCRIPTION"
              placement="top"
            >
              <el-tag size="small" effect="plain" type="primary" class="entry-badge">对话入口</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="编码" min-width="160" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-code">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模板" min-width="120" align="center">
          <template #default="{ row }">
            <el-tooltip :content="maTemplateHint(row.template)" placement="top">
              <el-tag size="small" effect="plain" :type="maTemplateTagType(row.template)">
                {{ maTemplateLabel(row.template) }}
              </el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="80" align="center">
          <template #default="{ row }">
            <!-- 列表直接切换状态：只改 ma_def 自身；入口必须保持启用 -->
            <span @click.stop>
              <!--
                状态统一用开关：对话入口是工作台唯一网关，停用等于关掉首页，
                所以开关照常展示但禁用，只读提示原因。
              -->
              <el-tooltip
                :disabled="!isEntryRouterTemplate(row.template)"
                content="对话入口必须保持启用：停用等于关掉对话首页"
                placement="top"
              >
                <el-switch
                  :model-value="row.enabled"
                  :active-value="1"
                  :inactive-value="0"
                  size="small"
                  :disabled="isEntryRouterTemplate(row.template) || !canEdit"
                  @change="() => toggleEnabled(row)"
                />
              </el-tooltip>
            </span>
          </template>
        </el-table-column>
        <!-- 简介 / 备注：方案的意图说明来源；入口显示平台固定备注（只读） -->
        <el-table-column label="简介" min-width="200" align="center" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.description" class="cell-desc">{{ row.description }}</span>
            <el-tooltip v-else content="没有说明：加进其它路由池时判路由无从判断，建议补上" placement="top">
              <el-tag size="small" type="warning" effect="plain">未填</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="候选" min-width="110" align="center">
          <template #default="{ row }">
            <span class="cell-count">{{ row.candidateEnabledCount }}</span>
            <span class="cell-count-unit">个启用</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="170" align="center">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="180" align="center" fixed="right">
          <template #default="{ row }">
            <!-- 「编辑」是唯一进详情抽屉的入口：默认落「基础信息」，不再单列「打开编排」 -->
            <el-button link type="primary" @click.stop="openEdit(row, 'basic')">编辑</el-button>
            <!-- 方案统一「调试」：普通方案与对话入口都进方案运行页（ma_def 自己为根） -->
            <el-button v-if="canTrial" link type="primary" @click.stop="openTrial(row)">调试</el-button>
            <el-tooltip v-else content="没有运行权限（runtime:trial:run / list）" placement="top">
              <span class="action-disabled">
                <el-button link disabled>调试</el-button>
              </span>
            </el-tooltip>
            <!-- 入口是数据锁定的唯一行，不能删除：后端同样拒绝，这里连按钮都不给 -->
            <el-button
              v-if="canEdit && !isEntryRouterTemplate(row.template)"
              link
              type="danger"
              @click.stop="removeRow(row)"
            >删除</el-button>
          </template>
        </el-table-column>

        <!-- 空态：无筛选时引导新建；有筛选时提示清空条件 -->
        <template #empty>
          <div class="empty-guide">
            <template v-if="hasFilter">
              <p class="empty-guide__title">没有匹配的方案</p>
              <p class="empty-guide__text">试试清空关键字、状态或模板筛选。</p>
              <el-button @click="resetFilter">清空筛选</el-button>
            </template>
            <template v-else>
              <p class="empty-guide__title">还没有多智能体方案</p>
              <p class="empty-guide__text">
                方案自带运行根：保存编排模板与候选池即可直接运行，无需绑定任何 Agent。
              </p>
              <el-button v-if="canEdit" type="primary" @click="openCreate">新建方案</el-button>
            </template>
          </div>
        </template>
      </el-table>
      <TablePager v-model:current="query.current" v-model:size="query.size" :total="total" @change="loadRows" />
    </section>

    <!-- 编辑抽屉：方案 + 配置 + 候选一次提交；不复制执行器逻辑 -->
    <el-drawer
      v-model="drawerVisible"
      :title="isEntryScheme ? MA_ENTRY_ROUTER_NAME : (form.id ? (canEdit ? '编辑方案' : '查看方案') : '新建方案')"
      size="920px"
      destroy-on-close
      class="ma-drawer"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" :disabled="!canEdit" label-width="132px" class="ma-form">
        <!--
          统一编辑壳：对话路由（对话入口）与普通方案共用这四个 Tab，不按类型分两套页面。
          差别只在字段可写性：入口的名称 / 编码 / 备注 / 类型只读、不出现绑根；
          运行配置里与总分流无关的项只读。模型与路由池两边完全一致。
        -->
        <el-tabs :key="form.id ?? 'new'" v-model="drawerTab" class="ma-tabs">
          <!-- Tab 1：基础信息。标题加粗 + 顶部实线，和下方区块拉开层次 -->
          <el-tab-pane label="基础信息" name="basic">
        <el-alert
          v-if="isEntryScheme"
          type="info"
          show-icon
          :closable="false"
          class="block-alert"
          title="这是对话入口：身份不可改"
          description="名称、编码、备注、类型由平台锁定；不可删除、不可加入其它路由池，也不能作为其它方案的子专家。能改的是「模型配置」里的判路由模型与 ROUTER 提示词，以及「路由池」里的候选。"
        />
        <section class="form-block form-block--first">
          <h3 class="form-block__title">基本信息</h3>
          <!--
            方案形象：配在方案自己身上，不再去 Agent 管理改「根」的形象。
            复用现网 8 套机器人预设（同一套 AGENT_LOOK_OPTIONS / AgentAvatar），不新做上传协议；
            未配置时由列表 / 时间线按方案名走 8 套兜底。
          -->
          <div class="avatar-row">
            <AgentAvatar :name="form.name || '方案'" :config="formAvatarPreview" :size="56" />
            <div class="avatar-meta">
              <span class="avatar-title">方案形象</span>
              <span class="avatar-hint">
                {{ form.avatarConfig ? '已配置：列表与时间线的方案块都用它' : '未配置：按方案名自动匹配一套机器人形象' }}
              </span>
            </div>
            <el-button :disabled="!canEdit" @click="avatarOpen = true">选择形象</el-button>
          </div>
          <el-row :gutter="24">
            <el-col :span="12">
              <el-form-item label="方案编码" prop="code">
                <el-input
                  v-model="form.code"
                  maxlength="64"
                  :disabled="isEntryScheme"
                  placeholder="租户内唯一，如 order_triage"
                />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="方案名称" prop="name">
                <el-input
                  v-model="form.name"
                  maxlength="128"
                  :disabled="isEntryScheme"
                  placeholder="给运营看的中文名"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="24">
            <el-col :span="24">
              <el-form-item prop="template">
                <!-- 模板口径收进问号悬浮：与 Agent 管理里「运行模式」一致，说明不占版面 -->
                <template #label>
                  <span class="label-with-tip">
                    协作模式
                    <el-tooltip placement="top" :content="maTemplateHint(form.template)">
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <!-- 入口类型由平台数据锁定：下拉只展示当前值，不可改，避免被改成普通方案或反之 -->
                <el-select
                  v-if="isEntryScheme"
                  v-model="form.template"
                  class="full-width"
                  disabled
                >
                  <el-option
                    :label="maTemplateLabel(MA_LIST_TEMPLATE_ENTRY_ROUTER)"
                    :value="MA_LIST_TEMPLATE_ENTRY_ROUTER"
                  />
                </el-select>
                <el-select v-else v-model="form.template" class="full-width" @change="onTemplateChange">
                  <el-option v-for="item in MA_LIST_TEMPLATES" :key="item" :label="maTemplateLabel(item)" :value="item">
                    <span>{{ maTemplateLabel(item) }}</span>
                    <span class="option-hint">{{ maTemplateHint(item) }}</span>
                  </el-option>
                </el-select>
                <el-alert
                  v-if="isSerialMode"
                  type="info"
                  show-icon
                  :closable="false"
                  class="block-alert"
                  :title="MA_SERIAL_WORKFLOW_HINT"
                />
              </el-form-item>
            </el-col>
          </el-row>
          <!--
            方案说明：被加进别的路由池时就是它的意图说明（唯一真源，不在候选上另编一份）。
            必填但允许先空着保存历史方案：加进池子时后端会拦。
            入口这一行是平台固定备注，只读展示。
          -->
          <el-form-item>
            <template #label>
              <span class="label-with-tip">
                方案说明
                <el-tooltip
                  placement="top"
                  content="方案被加入其它路由池时，这里就是判路由读取的意图说明；可先留空保存，入池时后端会拦。"
                >
                  <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <el-input
              v-model="form.description"
              type="textarea"
              :rows="3"
              :disabled="isEntryScheme"
              maxlength="512"
              show-word-limit
              :placeholder="isEntryScheme
                ? '入口备注由平台固定'
                : '这套方案负责哪类问题；判路由只读这里'"
            />
          </el-form-item>
        </section>
          </el-tab-pane>

          <!--
            Tab 2：模型配置只服务「会判路由」的模板（入口 / 路由并行 / 路由后串行）。
            固定 / 评审 / 竞争模板不调判路由模型，Tab 直接卡住不可点，避免露出空配置。
          -->
          <el-tab-pane name="model" :disabled="!routesByJudge">
            <template #label>
              <span class="label-with-tip">
                模型配置
                <el-tooltip
                  v-if="!routesByJudge"
                  placement="top"
                  :content="`当前模板「${maTemplateLabel(form.template)}」不判路由，没有 Router 模型可配`"
                >
                  <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <!-- 判路由模型与 ROUTER 提示词：与对话路由共用同一个组件，没有第二套协议 -->
            <MaJudgePromptPanel
              v-if="routesByJudge"
              v-model:judge-model-id="form.config.maJudgeModelInstanceId"
              :judge-models="judgeModels"
              :loading="judgeModelsLoading"
              :binding="judgePromptDraft"
              :can-edit="canEdit"
              @edit-prompt="promptOpen = true"
            />
            <section v-else class="form-block form-block--first">
              <h3 class="form-block__title">模型配置</h3>
              <p class="form-block__note">
                当前模板「{{ maTemplateLabel(form.template) }}」不判路由，不调用判路由模型与 ROUTER 提示词。
                子 Agent 各自的模型与提示词请到「Agent 管理」对应专家里配置。
              </p>
            </section>
          </el-tab-pane>

        <!--
          Tab 3：路由池列表。入口和普通方案都能加 Agent / 方案 / 工作流。
          简介只读；执行顺序只在路由后串行可改。
        -->
        <el-tab-pane label="路由池" name="canvas">
          <MaRoutePoolPanel
            :candidates="form.candidates"
            :is-entry="isEntryScheme"
            :can-edit="canEdit"
            :order-editable="isSerialMode"
            :agent-options-of-row="optionsForRow"
            :ma-options-of-row="maOptionsForRow"
            :workflow-options-of-row="workflowOptionsForRow"
            :on-add-agent="addAgentCandidate"
            :on-add-ma="addMaCandidate"
            :on-add-workflow="addWorkflowCandidate"
            :on-remove="removeCandidateAt"
            :on-candidate-agent-change="onCandidateAgentChange"
            :on-candidate-ma-change="onCandidateMaChange"
            :on-candidate-workflow-change="onCandidateWorkflowChange"
          />
        </el-tab-pane>

        <!-- Tab 4：运行配置。入口与普通方案共用一个 Tab；入口只把与总分流无关的项置灰 -->
        <el-tab-pane label="运行配置" name="runtime">
        <!-- 编排参数。字段一行一个，长标签不会因两列并排被挤到换行 -->
        <section class="form-block form-block--first">
          <h3 class="form-block__title">编排参数</h3>
          <!-- 入口只给一句短说明：这些参数与总分流无关，避免用户在灰控件上反复试 -->
          <el-tooltip
            v-if="isEntryScheme"
            placement="top"
            content="对话入口的终答、子模式与多意图由平台口径固定，沿用已保存的运行参数；上下文策略与每轮子数仍可调。"
          >
            <span class="field-hint block-hint">对话入口沿用已保存的运行参数；灰项由平台固定。</span>
          </el-tooltip>
          <!--
            字段一行一个：Label 里带问号图标后，两列并排会把长标签挤到换行，
            纵向排列也更好按「策略 → 输出 → 规模 → 开关 → 模板专属」顺序读。
          -->
          <el-form-item>
            <template #label>
              <span class="label-with-tip">
                上下文策略
                <el-tooltip
                  placement="top"
                  :content="isSerialMode
                    ? '路由后串行已锁定为继承前置结果。' + MA_SERIAL_WORKFLOW_HINT
                    : '意图路由分发默认隔离：并行的子只看自己的子问题，互不传结论。'"
                >
                  <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <el-select v-model="form.config.maContextPolicy" class="full-width" :disabled="isSerialMode">
              <el-option v-if="!isSerialMode" label="隔离（子只看用户原问/子问）" value="ISOLATED" />
              <el-option :label="isSerialMode ? '继承前置结果（锁定）' : '只看前置摘要（仅串行）'" value="PRIOR_SUMMARY_ONLY" />
              <el-option v-if="!isSerialMode" label="共享草稿区（本轮内存）" value="SHARED_SCRATCH" />
            </el-select>
            <el-alert
              v-if="isSerialMode"
              type="info"
              show-icon
              :closable="false"
              class="block-alert"
              :title="MA_SERIAL_WORKFLOW_HINT"
            />
          </el-form-item>
          <el-form-item>
            <template #label>
              <span class="label-with-tip">
                终答模式
                <el-tooltip
                  placement="top"
                  :content="supportsVote
                    ? '直接拼接：按序串起子结果；模型汇总：由模型合并成一份终答；投票选优：模型评选一路终答，失败则拼接。'
                    : '直接拼接：按序串起子结果；模型汇总：由模型合并成一份终答。'"
                >
                  <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <!-- 入口不按方案收终答：子结果由入口统一收口，这一项对入口不生效 -->
            <el-radio-group v-model="form.config.answerMode" :disabled="isEntryScheme">
              <el-radio value="CONCAT">直接拼接</el-radio>
              <el-radio value="SUMMARIZE">模型汇总</el-radio>
              <!-- 投票选优只对「等齐后统一出终答」的模板开放，与后端 supportsVote 同口径 -->
              <el-radio v-if="supportsVote" value="VOTE">投票选优</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item>
            <template #label>
              <span class="label-with-tip">
                每轮最多子数
                <el-tooltip placement="top" content="1～5；超出候选按序跳过，不报错。">
                  <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <el-input-number v-model="form.config.maxRouteChildren" :min="1" :max="5" />
          </el-form-item>
          <el-form-item>
            <template #label>
              <span class="label-with-tip">
                允许子模式
                <el-tooltip placement="top" content="ReAct 恒允许；勾选「计划执行」后，子 Agent 可按 P&E 模式运行。">
                  <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <!-- 入口可路由的子模式由平台锁定（ReAct），不在页面改动，避免与入口总分流口径冲突 -->
            <el-checkbox-group v-model="form.config.maChildRunModesAllowed" :disabled="isEntryScheme">
              <el-checkbox label="REACT" disabled>ReAct（恒允许）</el-checkbox>
              <el-checkbox label="PLAN_AND_EXECUTE">计划执行</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item>
            <template #label>
              <span class="label-with-tip">
                允许多意图
                <el-tooltip placement="top" content="开启后一次提问可同时路由到多个子；入口恒按多意图分发。">
                  <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <!-- 入口恒按多意图分发（可一次命中多个候选），不随方案开关 -->
            <el-switch v-model="form.config.multiIntentEnabled" :disabled="isEntryScheme" />
          </el-form-item>
          <el-form-item>
            <template #label>
              <span class="label-with-tip">
                单意图继承历史
                <el-tooltip placement="top" content="开启：单意图命中子时子可读父会话历史；关闭＝只看用户原问（硬隔离）。">
                  <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </span>
            </template>
            <el-switch v-model="form.config.maAllowParentHistoryWhenSingle" />
          </el-form-item>
        </section>
        </el-tab-pane>
        </el-tabs>
      </el-form>

      <!--
        ROUTER 提示词复用专家同一个 L3 弹窗：同一用途码、同一份绑定结构、同一个预览路径。
        弹窗自身不发请求；保存时把四列并入本抽屉的 config，点抽屉「保存」才落库。
      -->
      <PromptL3Dialog
        v-model="promptOpen"
        purpose="ROUTER"
        :binding="judgePromptDraft"
        :disabled="!canEdit"
        @save="applyJudgePrompt"
      />

      <!--
        方案形象：复用现网 Agent 形象弹窗与 8 套机器人预设，不新做上传协议。
        确认只改本地草稿（进脏检查），点抽屉「保存」才随方案一起落库。
      -->
      <AgentAvatarDialog
        v-model="avatarOpen"
        :name="form.name || '方案'"
        :config="form.avatarConfig"
        :can-edit="canEdit"
        @confirm="applyAvatar"
      />

      <template #footer>
        <!-- 无改动时取消/保存都禁用；有改动才可取消（还原基线）或保存 -->
        <el-button :disabled="!formDirty" @click="cancelEdit">取消</el-button>
        <el-button v-if="canEdit" type="primary" :loading="saving" :disabled="!formDirty" @click="submit">
          保存
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致 */
defineOptions({ name: 'MaList' })

/**
 * 多智能体方案列表页。所属域：agent（前端入口层）。
 *
 * <p>职责：
 * - 列出 ma_def 方案，支持启停、筛选、删除；
 * - 抽屉内编辑方案 + 候选，一次提交（后端候选整份替换）；
 * - 对话路由（对话入口）与普通方案共用同一套四 Tab 详情，只在字段可写性与提示上有差别；
 * - 有绑根时深链到既有编排 Tab / 独立试跑页；虚拟根进方案试跑页（def/:maId/trial）。
 *
 * <p>边界：不新建 Agent、不复制执行器；试跑仍走旧 ROUTER 执行器与旧试跑权限，
 * 只是执行读源改成 ma_def（模板 / 候选 / 判路由模型）。
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, QuestionFilled, RefreshRight, Search } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import AgentAvatar from '@/components/agent/AgentAvatar.vue'
import AgentAvatarDialog from '@/components/agent/AgentAvatarDialog.vue'
import MaJudgePromptPanel from '@/components/agent/MaJudgePromptPanel.vue'
import MaRoutePoolPanel from '@/components/agent/MaRoutePoolPanel.vue'
import PromptL3Dialog from '@/components/agent/PromptL3Dialog.vue'
import { useListIndexColumn } from '@/composables/listIndex'
import {
  changeMaDefEnabled,
  createMaDef,
  deleteMaDef,
  getMaDef,
  listMaJudgeModels,
  pageMaDefs,
  updateMaDef
} from '@/api/ma'
import { pageAgents } from '@/api/agent'
import { listReceptionSchemeOptions, listReceptionWorkflowOptions } from '@/api/maPool'
import { useUserStore } from '@/stores/user'
import { normalizeAgentAvatarConfig, resolveFallbackRobotConfig } from '@/constants/agentLooks'
import type { AgentCard, AvatarConfig, PromptBinding } from '@/types/agent'
import type { ReceptionSchemeOption, ReceptionWorkflowOption } from '@/types/reception'
import type {
  MaCandidate,
  MaCandidateRow,
  MaCandidateSave,
  MaDefDetail,
  MaDefRow,
  MaDefSave,
  MaJudgeModelOption,
  MaRuntimeConfig
} from '@/types/ma'
import {
  MA_ENTRY_ROUTER_DESCRIPTION,
  MA_ENTRY_ROUTER_NAME,
  MA_LIST_FILTER_TEMPLATES,
  MA_LIST_TEMPLATE_ENTRY_ROUTER,
  MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE,
  MA_LIST_TEMPLATES,
  MA_SERIAL_WORKFLOW_HINT,
  canonicalMaTemplate,
  isEntryRouterTemplate,
  maTemplateHint,
  maTemplateLabel,
  maTemplateTagType,
  routesByJudgeTemplate
} from '@/utils/maList'
import {
  normalizeCompeteStrategy,
  supportsVoteTemplate
} from '@/utils/orchestrationValidation'
import { maDefTrialPath } from '@/utils/maTrial'
import { collectAgentPages } from '@/utils/agentPages'

const router = useRouter()
const route = useRoute()
const user = useUserStore()

/** 本刀权限码：与后端 MaPermissions 完全一致 */
const canView = computed(() => user.hasPerm('ma:def:view'))
const canEdit = computed(() => user.hasPerm('ma:def:edit'))
/** 试跑仍走旧 Agent 试跑权；ma:def:run 只是占位码，不参与入口判断 */
const canTrial = computed(() => user.hasPerm('runtime:trial:run') || user.hasPerm('runtime:trial:list'))
/** 当前模板是否支持投票选优（与服务端 AgentRuntimeConfig.supportsVote 同口径） */
const supportsVote = computed(() => supportsVoteTemplate(form.template))
/** 当前方案是否为对话入口（对话路由）：模板 / 绑根 / 编码 / 名称 / 备注锁定，模型与 ROUTER 提示词仍可配 */
const isEntryScheme = computed(() => isEntryRouterTemplate(form.template))
/** 路由后串行：上下文锁定为继承前置结果，执行顺序可编 */
const isSerialMode = computed(
  () => canonicalMaTemplate(form.template) === MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE
)
/** 当前模板是否先判路由：决定是否显示「判路由模型 + ROUTER 提示词」区块 */
const routesByJudge = computed(() => routesByJudgeTemplate(form.template))

const loading = ref(false)
const listError = ref('')
const rows = ref<MaDefRow[]>([])
const total = ref(0)
const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  enabled: undefined as number | undefined,
  template: undefined as string | undefined
})
/** 多智能体方案表最左「序号」列；跨页连续，仅前端展示 */
const { indexColumnProps } = useListIndexColumn(() => query.current, () => query.size)

/** 筛选值归一：clearable 清空后可能是 ''/null；0、false 都不能按 falsy 丢掉 */
function enabledParam(): number | undefined {
  return query.enabled === 0 || query.enabled === 1 ? query.enabled : undefined
}

/** 是否有筛选条件：决定空态是「无数据引导」还是「无匹配」 */
const hasFilter = computed(() =>
  Boolean(query.keyword.trim()) || enabledParam() !== undefined || Boolean(query.template)
)

/** 时间展示：后端给 yyyy-MM-dd HH:mm:ss，直接截断毫秒；空值给占位 */
function formatTime(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

/** 分页加载；失败只出中文错误态，不伪造空数据成功 */
async function loadRows() {
  if (!canView.value) {
    listError.value = '没有查看多智能体方案的权限'
    return
  }
  loading.value = true
  listError.value = ''
  try {
    const { data } = await pageMaDefs({
      current: query.current,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      enabled: enabledParam(),
      template: query.template || undefined
    })
    const page = data.data
    rows.value = page?.records || []
    total.value = page?.total || 0
  } catch {
    listError.value = '多智能体方案加载失败'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 查询回第一页，避免停在越界页 */
function search() {
  query.current = 1
  void loadRows()
}

/** 重置筛选并重查 */
function resetFilter() {
  query.keyword = ''
  query.enabled = undefined
  query.template = undefined
  search()
}

// ---------------------------------------------------------------------------
// 编辑抽屉
// ---------------------------------------------------------------------------

/**
 * 候选行沿用共享类型 {@link MaCandidateRow}（定义在 types/ma）。
 *
 * <p>入口两栏与普通方案 Tab 共用同一个候选表组件，字段定义只留一份，
 * 避免两边各写一套后漂移。
 */
type CandidateRow = MaCandidateRow

/** 抽屉表单模型 */
interface MaFormState {
  id: number | null
  code: string
  name: string
  /**
   * 方案说明：被加进别的路由池时就是它的意图说明（唯一真源）。
   * 允许为空保存（历史方案可先补），但加进路由池时后端要求非空。
   */
  description: string
  template: string
  /**
   * 方案自己的形象：null＝未配置（列表 / 时间线按方案名走 8 套机器人兜底）。
   * 不再回落到「绑定根 Agent」的 avatar_config。
   */
  avatarConfig: AvatarConfig | null
  /**
   * 启用标记。抽屉里不再放开关：启停统一在列表「状态」列切换。
   * 此字段只为提交时原样带回（编辑沿用已存值、新建默认 1），避免保存把状态静默改掉。
   */
  enabled: number
  config: MaRuntimeConfig
  candidates: CandidateRow[]
}

/** 与后端 AgentRuntimeConfig.defaultsFor 同口径的默认配置，避免新建时空字段 */
function defaultConfig(): MaRuntimeConfig {
  return {
    maTemplate: 'ROUTER_PARALLEL',
    maContextPolicy: 'ISOLATED',
    maAllowParentHistoryWhenSingle: true,
    multiIntentEnabled: true,
    answerMode: 'CONCAT',
    maxRouteChildren: 3,
    requirePlanConfirm: false,
    maxPlanSteps: 8,
    maxStepRetries: 0,
    maChildRunModesAllowed: ['REACT'],
    maCriticMaxRounds: 1,
    maCriticAgentId: null,
    // 与后端 AgentRuntimeConfig 同口径：策略缺省 JUDGE_PICK，非 COMPETE 时该值不生效
    competeStrategy: 'JUDGE_PICK',
    // 判路由模型默认不显式绑定：走平台 ROUTER / CHAT 能力槽回落；提示词默认不钉模板
    maJudgeModelInstanceId: null,
    maJudgePromptTemplateId: null,
    maJudgePromptTemplateVersion: null,
    maJudgePromptOverride: {},
    maJudgePromptExtra: ''
  }
}

/** 抽屉四个 Tab 的合法取值；顺序即界面上从左到右的顺序 */
const DRAWER_TABS = ['basic', 'model', 'canvas', 'runtime'] as const
type DrawerTab = (typeof DRAWER_TABS)[number]

/**
 * 把调用方传进来的 Tab 参数收敛成合法 Tab 名。
 *
 * <p>为什么必须收敛：el-table 的 row-click 会带 (row, column, event) 三参，
 * 直接把第二参当 Tab 名会让 v-model 指向一个不存在 pane 的对象/字符串，
 * Element Plus 找不到活动面板时整块内容区就是空白。
 *
 * @param value 期望落到的 Tab；非法或缺失时回落到基础信息
 * @return 一定落在 DRAWER_TABS 内的 Tab 名
 */
function normalizeDrawerTab(value: unknown): DrawerTab {
  return typeof value === 'string' && (DRAWER_TABS as readonly string[]).includes(value)
    ? (value as DrawerTab)
    : 'basic'
}

const drawerVisible = ref(false)
/** 详情 Tab：basic / model / canvas（路由池）/ runtime（运行配置） */
const drawerTab = ref<DrawerTab>('basic')
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<MaFormState>({
  id: null,
  code: '',
  name: '',
  description: '',
  template: 'ROUTER_PARALLEL',
  avatarConfig: null,
  enabled: 1,
  config: defaultConfig(),
  candidates: []
})

/** 形象选择弹窗显隐；确认只改本地草稿，抽屉底部「保存」才落库 */
const avatarOpen = ref(false)

/**
 * 抽屉里的形象预览：已配置用方案自己的；未配置按方案名走 8 套机器人，
 * 避免预览退回「首字方座」而与列表 / 时间线不一致。
 */
const formAvatarPreview = computed<AvatarConfig | null>(() => {
  return normalizeAgentAvatarConfig(form.avatarConfig) || resolveFallbackRobotConfig(form.name)
})

/**
 * 表格行头像：方案自己的 avatar_config；未配置按方案名兜底。
 *
 * @param row 列表行
 * @return 可直接渲染的形象配置（必不为 null）
 */
function rowAvatarConfig(row: MaDefRow): AvatarConfig {
  return normalizeAgentAvatarConfig(row.avatarConfig) || resolveFallbackRobotConfig(row.name)
}

/**
 * 模板切到不判路由时「模型配置」Tab 会被禁用，停在禁用页就是空白，直接回落基础信息。
 *
 * <p>必须放在 {@code form} 声明之后：watch 注册时会立即求值一次 source，
 * 而 {@code routesByJudge} 读的正是 {@code form.template}，提前注册会命中 TDZ 白屏。
 */
watch(routesByJudge, (routing) => {
  if (!routing && drawerTab.value === 'model') {
    drawerTab.value = 'basic'
  }
})

/** 打开抽屉时的完整表单基线（含候选展示字段）；取消时整份还原，脏检查也据此比对 */
const formBaseline = ref<MaFormState | null>(null)

/**
 * ROUTER 提示词弹窗草稿：四列结构与专家配提示词完全一致。
 *
 * <p>弹窗保存只改这份草稿与 form.config，不直接发请求；
 * 抽屉底部「保存」才是唯一落库动作，避免半途偷偷改掉线上判路由行为。
 */
const judgePromptDraft = ref<PromptBinding>({ templateId: null, templateVersion: null, override: {}, promptExtra: '' })
const promptOpen = ref(false)
/** 判路由模型下拉：只含启用中、用途属于对话形态的实例 */
const judgeModels = ref<MaJudgeModelOption[]>([])
const judgeModelsLoading = ref(false)
let judgeModelsLoaded = false
/**
 * 入口方案的跨方案 / 工作流候选下拉。
 *
 * <p>复用现网「可入池目标」接口：后端已剔除自己、入口、停用、自己不判路由、
 * 自己还挂着方案候选的方案；工作流只给有默认发布指针且有说明的定义。
 * 这里只做一次懒加载，失败不阻断抽屉（仍可编辑专家与模型）。
 */
const maOptions = ref<ReceptionSchemeOption[]>([])
const workflowOptions = ref<ReceptionWorkflowOption[]>([])
let poolOptionsLoaded = false

/**
 * 懒加载入口池的跨方案 / 工作流选项。
 *
 * <p>只在入口方案打开时请求；失败时不置位，下次打开可重试。
 */
async function ensurePoolOptions() {
  if (poolOptionsLoaded) return
  const [schemes, workflows] = await Promise.all([
    listReceptionSchemeOptions(),
    listReceptionWorkflowOptions()
  ])
  maOptions.value = schemes.data.data || []
  workflowOptions.value = workflows.data.data || []
  poolOptionsLoaded = true
}

/**
 * 懒加载判路由模型下拉。
 *
 * <p>只在打开抽屉且允许编辑时拉一次；失败不缓存结果，下次打开可重试。
 * 接口按 ma:def:view 鉴权，不要求模型中心权限。
 */
async function loadJudgeModels() {
  if (judgeModelsLoaded || !canView.value) return
  judgeModelsLoading.value = true
  try {
    const { data } = await listMaJudgeModels()
    judgeModels.value = data.data || []
    judgeModelsLoaded = true
  } catch {
    // 拦截器已给出中文错误；下拉留空，仍可直接保存名称 / 候选
  } finally {
    judgeModelsLoading.value = false
  }
}

/**
 * 用详情里的配置回填提示词草稿。
 *
 * <p>override 必须新对象：弹窗内编辑不能直接改到详情响应对象。
 *
 * @param config 方案运行配置
 */
function applyJudgePromptFromConfig(config: MaRuntimeConfig) {
  judgePromptDraft.value = {
    templateId: config.maJudgePromptTemplateId ?? null,
    templateVersion: config.maJudgePromptTemplateVersion ?? null,
    override: { ...(config.maJudgePromptOverride || {}) },
    promptExtra: config.maJudgePromptExtra || ''
  }
}

/**
 * 提示词弹窗保存：把四列写回 form.config，等抽屉保存时统一提交。
 *
 * @param payload 弹窗提交的 L3 四列
 * @param done    弹窗回调；接入成功即可关闭
 */
function applyJudgePrompt(payload: PromptBinding, done: (ok: boolean) => void) {
  judgePromptDraft.value = { ...payload, override: { ...(payload.override || {}) } }
  form.config.maJudgePromptTemplateId = payload.templateId ?? null
  form.config.maJudgePromptTemplateVersion = payload.templateVersion ?? null
  form.config.maJudgePromptOverride = { ...(payload.override || {}) }
  form.config.maJudgePromptExtra = payload.promptExtra || ''
  done(true)
}

/**
 * 形象弹窗确认：只把草稿写回表单，等抽屉「保存」时随方案一起提交。
 *
 * @param value 弹窗回写的形象配置；null＝恢复默认（按方案名兜底）
 */
function applyAvatar(value: AvatarConfig | null) {
  form.avatarConfig = normalizeAgentAvatarConfig(value)
}

/**
 * 深拷贝当前表单，作为基线或还原源。
 *
 * @return 与 {@link MaFormState} 同结构的纯数据副本
 */
function cloneFormState(): MaFormState {
  return {
    id: form.id,
    code: form.code,
    name: form.name,
    description: form.description,
    template: form.template,
    avatarConfig: form.avatarConfig ? { ...form.avatarConfig } : null,
    enabled: form.enabled,
    config: {
      ...form.config,
      maChildRunModesAllowed: [...(form.config.maChildRunModesAllowed || [])],
      // 提示词覆盖是嵌套对象：基线必须深拷贝，否则弹窗里改一处会连基线一起变
      maJudgePromptOverride: { ...(form.config.maJudgePromptOverride || {}) }
    },
    candidates: form.candidates.map((item) => ({ ...item }))
  }
}

/**
 * 把基线/当前表单压成可稳定比对的快照（忽略候选回显名称等非提交字段）。
 *
 * @param state 待比对的表单状态
 * @return 可 JSON 序列化的比对对象
 */
function editableSnapshot(state: MaFormState = form) {
  const modes = [...(state.config.maChildRunModesAllowed || [])].slice().sort()
  return {
    code: state.code,
    name: state.name,
    description: (state.description || '').trim(),
    template: state.template,
    // 形象进脏检查：只改形象也算有改动，取消能还原
    avatarConfig: normalizeAgentAvatarConfig(state.avatarConfig),
    config: {
      maContextPolicy: state.config.maContextPolicy,
      answerMode: state.config.answerMode,
      maxRouteChildren: state.config.maxRouteChildren,
      maChildRunModesAllowed: modes,
      multiIntentEnabled: state.config.multiIntentEnabled,
      maAllowParentHistoryWhenSingle: state.config.maAllowParentHistoryWhenSingle,
      maCriticMaxRounds: state.config.maCriticMaxRounds,
      maCriticAgentId: state.config.maCriticAgentId ?? null,
      competeStrategy: state.config.competeStrategy,
      // 判路由模型与提示词四个字段都要进脏检查：只改模型 / 只换模板也算有改动
      maJudgeModelInstanceId: state.config.maJudgeModelInstanceId ?? null,
      maJudgePromptTemplateId: state.config.maJudgePromptTemplateId ?? null,
      maJudgePromptTemplateVersion: state.config.maJudgePromptTemplateVersion ?? null,
      maJudgePromptOverride: { ...(state.config.maJudgePromptOverride || {}) },
      maJudgePromptExtra: (state.config.maJudgePromptExtra || '').trim()
    },
    candidates: state.candidates.map((item) => ({
      targetType: item.targetType || 'AGENT',
      agentId: item.agentId ?? null,
      maDefId: item.maDefId ?? null,
      workflowDefId: item.workflowDefId ?? null,
      sortNo: item.sortNo ?? null,
      enabled: item.enabled
    }))
  }
}

/** 打开/回填完成后记下基线，保证刚打开时取消与保存都不可点 */
async function markFormBaseline() {
  await nextTick()
  formBaseline.value = cloneFormState()
}

/** 相对打开时是否有改动（基本信息 / 编排参数 / 候选池） */
const formDirty = computed(() => {
  if (!formBaseline.value) return false
  return JSON.stringify(editableSnapshot()) !== JSON.stringify(editableSnapshot(formBaseline.value))
})

/**
 * 取消：把基本信息、编排参数、候选池还原到打开抽屉时的数据，抽屉保持打开。
 * 仅在有改动时可点；还原后取消/保存再次禁用。
 */
function cancelEdit() {
  if (!formDirty.value || !formBaseline.value) return
  const baseline = formBaseline.value
  form.id = baseline.id
  form.code = baseline.code
  form.name = baseline.name
  form.description = baseline.description
  form.template = baseline.template
  form.avatarConfig = baseline.avatarConfig ? { ...baseline.avatarConfig } : null
  form.enabled = baseline.enabled
  form.config = {
    ...baseline.config,
    maChildRunModesAllowed: [...(baseline.config.maChildRunModesAllowed || [])],
    // 覆盖对象同样深拷贝，避免还原后再编辑把基线也改了
    maJudgePromptOverride: { ...(baseline.config.maJudgePromptOverride || {}) }
  }
  applyJudgePromptFromConfig(form.config)
  form.candidates = baseline.candidates.map((item) => ({ ...item }))
  // 清掉校验红字，避免还原后仍残留「必填」提示
  void nextTick(() => formRef.value?.clearValidate())
}

const formRules: FormRules = {
  code: [{ required: true, message: '方案编码不能为空', trigger: 'blur' }],
  name: [{ required: true, message: '方案名称不能为空', trigger: 'blur' }],
  template: [{ required: true, message: '请选择协作模式', trigger: 'change' }]
}

/** 子 Agent 候选：REACT + 计划执行，且当前方案允许 */
const leafAgents = ref<AgentCard[]>([])
let agentsLoaded = false

/**
 * 按运行模式拉「下拉可选」的全部启用 Agent。
 *
 * <p>为什么必须翻页：服务端对 size 的上限是 100，旧实现直接写 {@code size: 200}
 * 会被分页校验驳回，前端只剩「Agent 列表加载失败」。翻页与硬顶规则在
 * {@link collectAgentPages}（纯函数，有窄测），这里只负责把它包成真实请求。
 *
 * @param runMode REACT / PLAN_AND_EXECUTE / ROUTER
 * @return 该模式下启用中的 Agent；任一分页失败直接抛出，由调用方统一提示
 */
async function listAgentsForSelect(runMode: string): Promise<AgentCard[]> {
  return collectAgentPages(async (current, size) => {
    const { data } = await pageAgents({ current, size, runMode, status: 1 })
    return data.data
  })
}

/**
 * 懒加载 Agent 下拉数据：候选只可能是单兵（REACT / PLAN_AND_EXECUTE），
 * 不再拉 ROUTER——方案以自己为根，没有「绑根候选」这回事。
 * 只在首次打开抽屉时请求，避免列表页首屏多打接口；失败时不置位，下次可重试。
 */
async function ensureAgents() {
  if (agentsLoaded) return
  const [react, plan] = await Promise.all([
    listAgentsForSelect('REACT'),
    listAgentsForSelect('PLAN_AND_EXECUTE')
  ])
  leafAgents.value = [...react, ...plan]
  agentsLoaded = true
}

/** 候选下拉：只保留启用、允许被编排、运行模式被本方案允许的单兵 */
const candidateOptions = computed(() => {
  const allowed = form.config.maChildRunModesAllowed || ['REACT']
  const options = leafAgents.value.filter(
    (agent) =>
      agent.allowOrchestrated === true &&
      allowed.includes(agent.runMode)
  )
  // 只读回显：详情里已有但下拉没拉到的 Agent 补一条，避免显示裸 id
  for (const candidate of form.candidates) {
    if (!candidate.agentId || options.some((agent) => agent.id === candidate.agentId)) continue
    options.push({
      id: candidate.agentId,
      name: candidate.agentName || `Agent #${candidate.agentId}`,
      code: candidate.agentCode || '',
      runMode: candidate.agentRunMode || 'REACT',
      status: candidate.agentStatus ?? 1,
      allowOrchestrated: true
    })
  }
  return options
})

/**
 * 切换协作模式。
 *
 * <p>切到路由后串行时立刻锁成「继承前置结果」，不给改回隔离。
 * 切回意图路由分发时，若还停在继承上，回到隔离，避免并行误传结论。
 *
 * @param value 新的模板码
 */
function onTemplateChange(value: string) {
  if (value === MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE) {
    form.config.maContextPolicy = 'PRIOR_SUMMARY_ONLY'
  } else if (form.config.maContextPolicy === 'PRIOR_SUMMARY_ONLY') {
    form.config.maContextPolicy = 'ISOLATED'
  }
  if (form.config.answerMode === 'VOTE' && !supportsVoteTemplate(value)) {
    form.config.answerMode = 'CONCAT'
  }
  form.config.maTemplate = value
}

/** 新建：清空表单并打开抽屉（基础信息 Tab） */
async function openCreate() {
  if (!canEdit.value) return
  try {
    await Promise.all([ensureAgents(), ensurePoolOptions()])
  } catch {
    ElMessage.error('候选列表加载失败，无法选择目标')
    return
  }
  form.id = null
  form.code = ''
  form.name = ''
  form.description = ''
  form.template = 'ROUTER_PARALLEL'
  form.avatarConfig = null
  form.enabled = 1
  form.config = defaultConfig()
  applyJudgePromptFromConfig(form.config)
  form.candidates = []
  drawerTab.value = 'basic'
  drawerVisible.value = true
  // 判路由模型下拉非阻塞加载：失败也不影响填写基本信息
  void loadJudgeModels()
  await markFormBaseline()
}

/**
 * 编辑：按行 id 拉详情后回填；失败不打开抽屉，避免露出半份表单。
 *
 * <p>路由池三类目标都在本抽屉维护，因此打开时同时拉 Agent、方案和工作流下拉。
 *
 * @param row 列表行（el-table row-click 会带 row/column/event 三参，故 Tab 单独用 onRowClick 承接）
 * @param tab 打开后落到的 Tab；默认基础信息，非法值一律回落基础信息
 */
async function openEdit(row: MaDefRow, tab?: unknown) {
  if (!canView.value || row?.id === undefined) return
  const targetTab = normalizeDrawerTab(tab)
  let detail: MaDefDetail | null = null
  try {
    const { data } = await getMaDef(row.id)
    detail = data.data || null
  } catch {
    // 拦截器已给出中文错误
    return
  }
  if (!detail) {
    ElMessage.error('方案不存在或已删除')
    return
  }
  try {
    await Promise.all([ensureAgents(), ensurePoolOptions()])
  } catch {
    // 只读用户可能没有 agent:list；仍允许看方案详情，只是下拉选项不全
    if (canEdit.value) {
      ElMessage.error('候选下拉加载失败，可稍后重试')
      return
    }
  }
  fillForm(detail)
  // 模板不判路由时「模型配置」Tab 是禁用的，落到它上面会显示空白，这里提前回落
  drawerTab.value = targetTab === 'model' && !routesByJudge.value ? 'basic' : targetTab
  drawerVisible.value = true
  // 判路由模型下拉非阻塞加载：只读用户也能看当前绑定值
  void loadJudgeModels()
  await markFormBaseline()
}

/**
 * 表格行点击：只认行数据，忽略 el-table 附带的 column / event。
 *
 * <p>直接绑 {@link openEdit} 会把 column 当成 Tab 名，活动面板匹配不到任何 pane，
 * 抽屉内容区就整块空白；这里固定落「基础信息」，与「编辑」按钮同口径。
 *
 * @param row 被点击的方案行
 */
function onRowClick(row: MaDefRow) {
  void openEdit(row, 'basic')
}

/** 详情 → 表单：config 做一次浅拷贝，避免直接改服务端响应对象 */
function fillForm(detail: MaDefDetail) {
  form.id = detail.id
  form.code = detail.code
  form.name = detail.name
  form.description = detail.description || ''
  form.template = canonicalMaTemplate(detail.template)
  form.avatarConfig = normalizeAgentAvatarConfig(detail.avatarConfig)
  form.enabled = detail.enabled
  form.config = { ...defaultConfig(), ...(detail.config || {}) }
  form.config.maTemplate = form.template
  if (form.template === MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE) {
    form.config.maContextPolicy = 'PRIOR_SUMMARY_ONLY'
  }
  // 旧数据没有 / 存了非法策略：按服务端同口径回落 JUDGE_PICK，避免单选组空白
  form.config.competeStrategy = normalizeCompeteStrategy(form.config.competeStrategy)
  // 历史配置可能存着模板不支持的 VOTE（手改 JSON）：按服务端同口径收敛回拼接
  if (form.config.answerMode === 'VOTE' && !supportsVoteTemplate(detail.template)) {
    form.config.answerMode = 'CONCAT'
  }
  // 判路由模型 / ROUTER 提示词：统一在现网方案页维护（入口与场景 Router 共用）
  applyJudgePromptFromConfig(form.config)
  form.candidates = (detail.candidates || []).map((item: MaCandidate) => ({
    // 三类目标原样回填：目标类型决定保存时提交哪个主键
    targetType: item.targetType === 'MA'
      ? 'MA'
      : item.targetType === 'WORKFLOW'
        ? 'WORKFLOW'
        : 'AGENT',
    agentId: item.agentId ?? null,
    agentName: item.agentName,
    agentCode: item.agentCode,
    agentRunMode: item.agentRunMode,
    agentStatus: item.agentStatus,
    maDefId: item.maDefId ?? null,
    maName: item.maName,
    maCode: item.maCode,
    maTemplate: item.maTemplate,
    maStatus: item.maStatus,
    workflowDefId: item.workflowDefId ?? null,
    workflowName: item.workflowName,
    workflowCode: item.workflowCode,
    // routeKey 不回填：该键由服务端按目标编码派生，不在前端保存/编辑
    // 意图说明只读：服务端回显的已是来源（Agent 简介 / 方案说明 / 工作流说明）的当前值
    intentDesc: item.intentDesc || '',
    intentDescMissing: item.intentDescMissing === true,
    sortNo: item.sortNo,
    enabled: item.enabled
  }))
}

/**
 * 按当前行序重排候选 sortNo（字段 10、20、30…）。
 *
 * <p>排序不再让用户填：表格只显示连续序号 1、2、3…，落库字段仍按 10 递增，
 * 给后续插入留空隙。新增 / 删除 / 提交前都调一次，保证库里顺序与界面一致。
 */
function renumberCandidates() {
  form.candidates.forEach((item, index) => {
    item.sortNo = (index + 1) * 10
  })
}

/**
 * 并行按行序重排；串行保留用户填的执行顺序，避免保存时把接力顺序洗掉。
 */
function syncCandidateOrder() {
  if (isSerialMode.value) return
  renumberCandidates()
}

/**
 * 加一行空候选；排序号由 {@link renumberCandidates} 按行序自动补。
 *
 * <p>三类目标分开加：避免「先加一行再切类型」时把已填字段带错。
 * 意图说明随目标选择从下拉里回填，仅用于即时预览；保存时服务端以目标自身说明为准。
 */
function addAgentCandidate() {
  form.candidates.push({
    targetType: 'AGENT',
    agentId: null,
    intentDesc: '',
    intentDescMissing: false,
    sortNo: undefined,
    enabled: 1
  })
  syncCandidateOrder()
  if (isSerialMode.value) assignSerialOrder(form.candidates[form.candidates.length - 1])
}

/** 加一行方案候选（目标在行内下拉里选） */
function addMaCandidate() {
  form.candidates.push({
    targetType: 'MA',
    agentId: null,
    maDefId: null,
    intentDesc: '',
    intentDescMissing: false,
    sortNo: undefined,
    enabled: 1
  })
  syncCandidateOrder()
  if (isSerialMode.value) assignSerialOrder(form.candidates[form.candidates.length - 1])
}

/** 加一行已发布工作流候选（目标在行内下拉里选） */
function addWorkflowCandidate() {
  form.candidates.push({
    targetType: 'WORKFLOW',
    agentId: null,
    workflowDefId: null,
    intentDesc: '',
    intentDescMissing: false,
    sortNo: undefined,
    enabled: 1
  })
  syncCandidateOrder()
  if (isSerialMode.value) assignSerialOrder(form.candidates[form.candidates.length - 1])
}

/**
 * 串行新增行给一个不与现有行冲突的执行顺序。
 *
 * @param row 刚推进去的那一行
 */
function assignSerialOrder(row: CandidateRow | undefined) {
  if (!row) return
  const used = form.candidates
    .filter((item) => item !== row)
    .map((item) => item.sortNo || 0)
  const next = used.length === 0 ? 10 : Math.max(...used) + 10
  row.sortNo = next
}

/**
 * 选择子 Agent 后同步展示用的意图说明。
 *
 * <p>不提交该字段：真正落库的是服务端按 Agent 简介回填的值，
 * 这里只是让运营在选完的那一刻看到「这条线负责什么」。
 *
 * @param row 候选行
 */
function onCandidateAgentChange(row: CandidateRow) {
  const agent = leafAgents.value.find((item) => item.id === row.agentId)
  row.intentDesc = agent?.description || ''
  row.intentDescMissing = !agent?.description || !agent.description.trim()
}

/**
 * 选择入池方案后同步展示用的意图说明，并清掉旧目标残留字段。
 *
 * @param row 候选行
 */
function onCandidateMaChange(row: CandidateRow) {
  const target = maOptions.value.find((item) => item.id === row.maDefId)
  row.maName = target?.name
  row.maCode = target?.code
  row.maTemplate = target?.template
  row.intentDesc = target?.description || ''
  row.intentDescMissing = !target?.description
}

/**
 * 选择入池工作流后同步展示用的意图说明。
 *
 * @param row 候选行
 */
function onCandidateWorkflowChange(row: CandidateRow) {
  const target = workflowOptions.value.find((item) => item.id === row.workflowDefId)
  row.workflowName = target?.name
  row.workflowCode = target?.code
  row.intentDesc = target?.description || ''
  row.intentDescMissing = !target?.description
}

/** 意图说明缺失时的来源提示：专家 / 方案 / 工作流分别去对应页面补 */
/**
 * 移除第 index 行候选。
 *
 * <p>候选数组由本页维护，表格组件只回调行号，避免组件里再持有写逻辑。
 *
 * @param index 行下标
 */
function removeCandidateAt(index: number) {
  form.candidates.splice(index, 1)
  // 并行按行序重排；串行保留剩余行上用户填过的执行顺序
  syncCandidateOrder()
}

/**
 * 方案候选下拉：后端已排除不可入池的方案；本行当前值若不在列表里补一条，避免显示裸 id。
 *
 * <p>同时排除其它行已选中的方案，避免同一方案重复入池（服务端也会拦重复）。
 *
 * @param row 候选行
 * @return 可选方案列表
 */
function maOptionsForRow(row: CandidateRow) {
  const usedByOthers = new Set(
    form.candidates
      .filter((item) => item !== row && item.targetType === 'MA')
      .map((item) => item.maDefId)
      .filter((id): id is number => !!id)
  )
  const options = maOptions.value.filter(
    (item) => item.id === row.maDefId || !usedByOthers.has(item.id)
  )
  if (row.maDefId && !options.some((item) => item.id === row.maDefId)) {
    options.push({
      id: row.maDefId,
      name: row.maName || `方案 #${row.maDefId}`,
      code: row.maCode || '',
      template: row.maTemplate,
      description: row.intentDesc
    })
  }
  return options
}

/**
 * 工作流候选下拉：同上，保留本行当前值并排除其它行重复。
 *
 * @param row 候选行
 * @return 可选工作流列表
 */
function workflowOptionsForRow(row: CandidateRow) {
  const usedByOthers = new Set(
    form.candidates
      .filter((item) => item !== row && item.targetType === 'WORKFLOW')
      .map((item) => item.workflowDefId)
      .filter((id): id is number => !!id)
  )
  const options = workflowOptions.value.filter(
    (item) => item.id === row.workflowDefId || !usedByOthers.has(item.id)
  )
  if (row.workflowDefId && !options.some((item) => item.id === row.workflowDefId)) {
    options.push({
      id: row.workflowDefId,
      name: row.workflowName || `工作流 #${row.workflowDefId}`,
      code: row.workflowCode || '',
      description: row.intentDesc
    })
  }
  return options
}

/**
 * 单行候选可选的下拉项：排除其它行已选中的 Agent，避免同一方案重复添加同一子 Agent。
 *
 * <p>保留本行当前已选值，否则切换时当前选中项会从选项里消失、下拉显示成裸 id。
 *
 * @param row 候选行
 * @return 过滤后的可选 Agent 列表
 */
function optionsForRow(row: CandidateRow) {
  const usedByOthers = new Set(
    form.candidates
      .filter((item) => item !== row && item.agentId !== null)
      .map((item) => item.agentId as number)
  )
  return candidateOptions.value.filter(
    (agent) => agent.id === row.agentId || !usedByOthers.has(agent.id)
  )
}

/** 提交前把 REACT 恒补回允许集合，避免用户取消勾选造成服务端归一化差异 */
function normalizeConfig(): MaRuntimeConfig {
  const config: MaRuntimeConfig = { ...form.config, maTemplate: form.template }
  const modes = new Set(config.maChildRunModesAllowed || [])
  modes.add('REACT')
  config.maChildRunModesAllowed = Array.from(modes)
  return config
}

/**
 * 候选行 → 保存载荷：三类目标各自只带自己的主键。
 *
 * <p>不传 routeKey 与 intentDesc：路由键与意图说明都由服务端派生
 * （后者取 Agent 简介 / 方案说明 / 工作流说明）。
 *
 * @param item 表单里的候选行
 * @return 提交用候选
 */
function toCandidateSave(item: CandidateRow): MaCandidateSave {
  if (item.targetType === 'MA') {
    return {
      targetType: 'MA',
      maDefId: item.maDefId ?? null,
      sortNo: item.sortNo,
      enabled: item.enabled
    }
  }
  if (item.targetType === 'WORKFLOW') {
    return {
      targetType: 'WORKFLOW',
      workflowDefId: item.workflowDefId ?? null,
      sortNo: item.sortNo,
      enabled: item.enabled
    }
  }
  return {
    targetType: 'AGENT',
    agentId: item.agentId as number,
    sortNo: item.sortNo,
    enabled: item.enabled
  }
}

/** 候选行是否还没选到目标（三类目标分别看自己的主键） */
function candidateTargetMissing(item: CandidateRow): boolean {
  if (item.targetType === 'MA') return !item.maDefId
  if (item.targetType === 'WORKFLOW') return !item.workflowDefId
  return !item.agentId
}

/** 两个候选行是否指向同一目标（用于判断「打开时就已在池里」） */
function sameTarget(left: CandidateRow, right: CandidateRow): boolean {
  if ((left.targetType || 'AGENT') !== (right.targetType || 'AGENT')) return false
  if (left.targetType === 'MA') return left.maDefId === right.maDefId
  if (left.targetType === 'WORKFLOW') return left.workflowDefId === right.workflowDefId
  return left.agentId === right.agentId
}

/** 打开抽屉时该候选是否已在池里：历史空说明目标只提示、不拦保存 */
function wasBoundAtOpen(item: CandidateRow): boolean {
  return (formBaseline.value?.candidates || []).some((base) => sameTarget(base, item))
}

/** 保存：先过前端必填校验，再整份提交方案 + 候选 */
async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  // 三类候选都必须选到目标才算填完，否则保存会被后端拒绝（或静默丢行）
  if (form.candidates.some((item) => candidateTargetMissing(item))) {
    ElMessage.warning('候选池存在未选择目标的行，请先补齐或移除')
    return
  }
  // 意图说明来自来源自身：新加入的空说明目标先拦；打开时就已在池里的历史行只提示不拦
  if (form.candidates.some((item) => item.intentDescMissing && !wasBoundAtOpen(item))) {
    ElMessage.warning('有候选还没填说明，请先到 Agent 管理 / 方案 / 工作流补上再保存')
    return
  }
  // 并行按行序落执行顺序；串行保留用户填写的顺序，空号再补一个
  if (isSerialMode.value) {
    for (const item of form.candidates) {
      if (!item.sortNo) assignSerialOrder(item)
    }
  } else {
    renumberCandidates()
  }
  const payload: MaDefSave = {
    code: form.code.trim(),
    name: form.name.trim(),
    description: form.description.trim(),
    template: form.template,
    config: normalizeConfig(),
    avatarConfig: normalizeAgentAvatarConfig(form.avatarConfig),
    enabled: form.enabled,
    candidates: form.candidates.map(toCandidateSave)
  }
  saving.value = true
  try {
    if (form.id) {
      await updateMaDef(form.id, payload)
      ElMessage.success('方案已更新')
    } else {
      await createMaDef(payload)
      ElMessage.success('方案已创建')
    }
    drawerVisible.value = false
    await loadRows()
  } catch {
    // 拦截器已给出中文错误；保持抽屉打开便于修正
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------------------
// 启停 / 删除 / 深链
// ---------------------------------------------------------------------------

/**
 * 行内启停。乐观更新，失败回滚，避免用户看到错误状态。
 *
 * @param row 列表行
 */
async function toggleEnabled(row: MaDefRow) {
  const next = row.enabled === 1 ? 0 : 1
  const previous = row.enabled
  row.enabled = next
  try {
    await changeMaDefEnabled(row.id, next)
    ElMessage.success(next === 1 ? '方案已启用' : '方案已停用')
    await loadRows()
  } catch {
    row.enabled = previous
  }
}

/** 删除方案：二次确认；候选一并删除，不涉及任何 Agent 根 */
async function removeRow(row: MaDefRow) {
  try {
    await ElMessageBox.confirm(
      `删除方案「${row.name}」？候选会一并删除，方案内部成员（单兵 / 子方案 / 工作流）本身不受影响。`,
      '删除方案',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteMaDef(row.id)
    ElMessage.success('方案已删除')
    await loadRows()
  } catch {
    // 拦截器已给出中文错误
  }
}

/**
 * 打开独立运行页。
 *
 * <p>方案以 {@code ma_def} 自己为根：普通方案与对话入口都进方案运行页（会话挂 maId），
 * 不再按历史 {@code rootAgentId} 分叉到 Agent 运行页。
 *
 * @param row 方案行
 */
function openTrial(row: MaDefRow) {
  void router.push(maDefTrialPath(row.id))
}

/**
 * 深链打开方案编辑：Agent「编排」Tab 的「打开方案编辑」会带 ?edit={id} 进本页。
 * 打开前先清掉 query：一是避免 KeepAlive 复用时同一 id 不再触发 watch，
 * 二是关闭抽屉后刷新页面不会又弹一次。
 */
async function openFromQuery() {
  const raw = route.query.edit
  if (!raw || !canView.value) return
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  if (!Number.isFinite(id) || id <= 0) return
  await router.replace({ path: route.path, query: {} })
  await openEdit({ id } as MaDefRow)
}

onMounted(async () => {
  await loadRows()
  await openFromQuery()
})

// 列表页是 KeepAlive 缓存的（cacheName=MaList）：再次深链只变 query，需要 watch 兜底
watch(
  () => route.query.edit,
  (value) => {
    if (value) void openFromQuery()
  }
)
</script>

<style scoped>
.block-alert {
  margin-bottom: 12px;
}

/* 运行配置顶部的一句短说明：入口专用，紧贴区块标题下方 */
.block-hint {
  display: block;
  margin: -8px 0 14px;
}

.ma-table :deep(.ma-row) {
  cursor: pointer;
}

/* 名称单元格：头像 + 名称按钮同一行，头像不参与行点击冒泡 */
.cell-name-wrap {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  vertical-align: middle;
}

.cell-name {
  padding: 0;
  border: 0;
  background: none;
  color: var(--ah-text-primary, #111827);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.cell-name:hover {
  color: var(--el-color-primary);
}

/* 入口角标：与名称同行，只做身份提示；完整备注走悬浮 */
.entry-badge {
  margin-left: 6px;
  vertical-align: middle;
}

.cell-code {
  color: var(--ah-text-secondary, #6b7280);
  font-family: var(--ah-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 12px;
}

/* 简介 / 备注：一行展示，过长由表格 show-overflow-tooltip 接管 */
.cell-desc {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  color: var(--ah-text-secondary, #6b7280);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-count {
  font-weight: 600;
}

.cell-count-unit {
  margin-left: 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

.action-disabled {
  display: inline-block;
  margin: 0 8px;
}

/* 候选池相关样式已随表格迁到 MaRoutePoolPanel.vue（组件内 scoped 自持），
   这里只留抽屉级与 Tab 级布局 */

/* 抽屉里的 Tab：内容区顶部不再叠一层大留白 */
.ma-tabs :deep(.el-tabs__header) {
  margin: 0 0 12px;
}

.ma-tabs :deep(.el-tabs__content) {
  padding-top: 4px;
}

.empty-guide {
  padding: 18px 12px;
  text-align: center;
}

.empty-guide__title {
  margin: 0 0 6px;
  color: var(--ah-text-primary, #111827);
  font-size: 14px;
  font-weight: 600;
}

.empty-guide__text {
  max-width: 460px;
  margin: 0 auto 14px;
  color: var(--ah-text-secondary, #6b7280);
  font-size: 13px;
  line-height: 1.6;
}

.full-width {
  width: 100%;
}

/* 抽屉三块：实线分隔 + 加粗标题，靠间距和线把「基本信息 / 编排参数 / 候选池」切清楚 */
.form-block {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--ah-border, #e8eaf0);
}

.form-block--first {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}

.form-block__title {
  display: flex;
  align-items: center;
  margin: 0 0 16px;
  color: var(--ah-text-primary, #1f2430);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

/* 左侧短竖条：比纯文字标题更容易扫到分区起点 */
.form-block__title::before {
  content: '';
  width: 3px;
  height: 14px;
  margin-right: 8px;
  border-radius: 2px;
  background: var(--el-color-primary);
}

.form-block__note {
  margin: -4px 0 0;
  /* 与 el-form 的 label-width 对齐（132px），说明文字才对得上控件左边线 */
  padding-left: 132px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.6;
}

/* 方案形象行：与下方表单字段同一起点，头像 + 文案 + 选择按钮一行 */
.avatar-row {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 0 0 18px;
  padding: 12px 16px;
  border: 1px solid #ebe9f5;
  border-radius: 12px;
  background: #faf9ff;
}

.avatar-meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.avatar-title {
  color: #1f1b33;
  font-size: 13px;
  font-weight: 600;
}

.avatar-hint {
  color: #8a86a3;
  font-size: 12px;
}

/* 标签加粗：靠字重区分「字段名 / 输入值」 */
.ma-form :deep(.el-form-item__label) {
  font-weight: 600;
}

/* 抽屉加宽后左右留白同步加大，表格不贴边 */
.ma-drawer :deep(.el-drawer__body) {
  padding: 20px 24px 8px;
}

/* 窄屏兜底：size 是行内宽度，只能靠 !important 收回到视口内，避免右侧被裁掉 */
@media (max-width: 1000px) {
  .ma-drawer {
    width: 94vw !important;
  }
}

/* Label 旁问号：与 Agent 管理里的字段提示图标同一套观感 */
.label-with-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tip-icon {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 14px;
  cursor: help;
}

.field-hint {
  margin-top: 4px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  line-height: 1.5;
}

.option-hint {
  margin-left: 10px;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

/* 抽屉级收尾：Tab 顶部留白已在上方统一声明，这里不再重复 */
</style>
