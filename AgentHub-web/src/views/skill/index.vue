<template>
  <div class="browse-page" :class="{ 'is-flip-busy': flipBusy }">
    <!-- 页头只承载标题/说明 + 全局「新建 Skill」；筛选条内不再重复放新建 -->
    <PageHeader title="Skill 管理" description="维护可复用的 Skill 正文（Markdown）；供后续 Agent 绑定使用。">
      <template #extra>
        <!-- 飞卡过程中禁用新建，避免弹窗与视图切换动效抢焦点 -->
        <el-button v-if="user.hasPerm('skill:add')" type="primary" :disabled="flipBusy" @click="createOpen = true">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建 Skill
        </el-button>
      </template>
    </PageHeader>

    <!-- 网格工具栏：名称/标题模糊 + 状态；布局对齐用户管理；仅一页时隐藏分页 -->
    <div v-show="viewMode === 'grid'" class="browse-filters">
      <ViewModeToggle
        mode="grid"
        :disabled="flipBusy"
        :can-enter-master="!!rows.length || total > 0"
        @change="onViewModeChange"
      />
      <el-form class="filter-form" inline @submit.prevent="searchGrid">
        <el-form-item label="名称">
          <el-input
            v-model="query.keyword"
            class="filter-input"
            clearable
            placeholder="名称或标题"
            :disabled="flipBusy"
            @keyup.enter="searchGrid"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            class="filter-select"
            clearable
            placeholder="全部"
            :disabled="flipBusy"
          >
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button class="btn-query" :disabled="flipBusy" @click="searchGrid">
            <el-icon class="btn-icon"><Search /></el-icon>
            查询
          </el-button>
          <el-button class="btn-reset" :disabled="flipBusy" @click="resetGridFilter">
            <el-icon class="btn-icon"><RefreshRight /></el-icon>
            重置
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <el-alert v-if="listError" :title="listError" type="error" show-icon :closable="false" class="block-alert" />

    <!--
      双层同页：飞卡过程中两层都用 v-show 留在 DOM。
      Skill 无路由 id，仅本地 viewMode + activeId。
    -->
    <div class="browse-workspace">
      <div
        v-show="showGridLayer"
        class="layer layer-grid"
        :class="{ 'is-active': viewMode === 'grid' }"
      >
        <div ref="gridWrapRef" v-loading="listLoading && viewMode === 'grid'" class="grid-wrap">
          <el-empty v-if="!listLoading && !gridRows.length" description="还没有 Skill" />
          <div v-else-if="gridRows.length" class="grid">
            <ConfigCard
              v-for="row in gridRows"
              :key="row.id"
              class="card"
              size="normal"
              :class="{ 'is-flying': flyingId === row.id }"
              :data-browse-id="row.id"
              tabindex="0"
              @click="onGridCardClick(row, $event)"
              @keyup.enter="onGridCardClick(row, $event)"
            >
              <template #header>
                <!-- 右上灰色 ×；stop 防止进详情；二次确认后删除 -->
                <el-popconfirm
                  v-if="user.hasPerm('skill:remove')"
                  width="280"
                  :title="`删除 Skill「${row.name}」？此操作不可恢复。`"
                  confirm-button-text="删除"
                  cancel-button-text="取消"
                  confirm-button-type="danger"
                  @confirm="removeCard(row)"
                >
                  <template #reference>
                    <button
                      type="button"
                      class="card-del"
                      :aria-label="`删除 ${row.name}`"
                      :disabled="removingId === row.id"
                      @click.stop
                    >
                      ×
                    </button>
                  </template>
                </el-popconfirm>
                <h3 class="card-name" :title="row.name">{{ row.name }}</h3>
                <p class="card-title" :title="row.title">{{ row.title }}</p>
                <div class="badges">
                  <el-tag size="small" effect="plain" :class="row.status === 1 ? 'badge-on' : 'badge-off'">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </div>
              </template>
              <!-- 卡片简介只占一行，超出省略；完整内容由统一气泡兜底（clamp-tip：宽 320px、限高自滚） -->
              <el-tooltip
                placement="top"
                popper-class="clamp-tip"
                :disabled="!row.description?.trim()"
              >
                <template #content>
                  <div class="clamp-tip__body">{{ row.description?.trim() }}</div>
                </template>
                <span class="card-desc" :class="{ muted: !row.description?.trim() }">
                  {{ row.description?.trim() || '暂无简介' }}
                </span>
              </el-tooltip>
              <template #footer>
                <!-- 卡片只展示生效版本号；没有发布过明确写「未发布」，草稿单独打标 -->
                <span class="version">
                  <template v-if="row.activeVersionNo">v{{ row.activeVersionNo }}</template>
                  <template v-else>未发布</template>
                  <el-tag v-if="row.hasDraft" size="small" effect="plain" type="info" class="draft-tag">
                    草稿
                  </el-tag>
                </span>
              </template>
            </ConfigCard>
          </div>
        </div>
        <!-- 网格卡片墙精简底栏（mode=grid）：总数常显 + 页码仅多页时出现，无「x条/页」「前往x页」 -->
        <TablePager
          v-if="viewMode === 'grid' && total > 0"
          v-model:current="query.current"
          v-model:size="query.size"
          :total="total"
          mode="grid"
          :total-text="`共 ${total} 个 Skill`"
          pager-on-multi-page-only
          @change="loadRows"
        />
      </div>

      <div
        v-show="showMasterLayer"
        class="layer layer-master"
        :class="{ 'is-active': viewMode === 'master' }"
      >
        <div class="master">
          <aside class="rail">
            <div class="rail-toolbar">
              <ViewModeToggle mode="master" :disabled="flipBusy" @change="onViewModeChange" />
              <el-input
                v-model="railKeyword"
                class="rail-search"
                clearable
                placeholder="搜索名称或标题"
                :disabled="flipBusy"
              />
            </div>
            <div v-loading="listLoading && viewMode === 'master'" class="rail-list">
              <el-empty v-if="!listLoading && !railRows.length" description="没有符合筛选的 Skill" />
              <button
                v-for="row in railRows"
                :key="row.id"
                type="button"
                class="rail-card"
                :class="{
                  'is-current': row.id === activeId,
                  'is-flying': flyingId === row.id
                }"
                :data-browse-id="row.id"
                :disabled="flipBusy"
                :aria-current="row.id === activeId ? 'true' : undefined"
                @click="openDetail(row)"
              >
                <span class="rail-name" :title="row.name">{{ row.name }}</span>
                <span class="rail-code" :title="row.title">{{ row.title }}</span>
                <span class="rail-badges">
                  <el-tag size="small" effect="plain" :class="row.status === 1 ? 'badge-on' : 'badge-off'">
                    {{ row.status === 1 ? '启用' : '停用' }}
                  </el-tag>
                </span>
              </button>
            </div>
          </aside>

          <div
            class="detail-pane"
            :class="{
              'is-enter': detailPhase === 'enter',
              'is-leave': detailPhase === 'leave'
            }"
          >
            <div
              v-if="activeId > 0 && (viewMode === 'master' || flipBusy)"
              v-loading="detailLoading"
              class="detail-panel"
            >
              <el-alert
                v-if="detailError"
                :title="detailError"
                type="error"
                show-icon
                :closable="false"
                class="block-alert"
              />
              <!-- 表单体可滚，底栏取消/保存始终贴底可见 -->
              <el-form v-if="form" label-width="96px" class="detail-form">
                <div class="detail-form__body">
                  <!-- 第一行：名称 10 / 标题 8 / 版本 3 / 状态 3；开关即时启停，不计入脏标记 -->
                  <el-row :gutter="12">
                    <el-col :span="10">
                      <el-form-item label="名称" required>
                        <el-input v-model="form.name" maxlength="64" :disabled="!canEdit" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="8">
                      <el-form-item label="标题" required>
                        <el-input v-model="form.title" maxlength="128" :disabled="!canEdit" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="3">
                      <!-- 版本号为只读派生：当前生效已发布版本；升版只能走「发布」，禁止手改 -->
                      <el-form-item label="版本" label-width="42px">
                        <el-input :model-value="activeVersionLabel" disabled />
                      </el-form-item>
                    </el-col>
                    <el-col :span="3">
                      <!-- 状态开关居右，与下两行按钮列对齐 -->
                      <el-form-item label="状态" label-width="42px" class="status-item">
                        <el-switch
                          :model-value="form.status === 1"
                          :disabled="!user.hasPerm('skill:enable') || statusSaving"
                          @change="(on: string | number | boolean) => onStatusSwitch(on === true)"
                        />
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <!--
                    定义层第二、三行：触发关键词、备注各占一行。
                    每行右侧（状态开关正下方）各放一个按钮：关键词行=取消，备注行=保存；
                    定义层只保存名称/标题/触发词/备注，简介与正文由版本编辑器负责。
                  -->
                  <el-row :gutter="12">
                    <el-col :span="21">
                      <el-form-item label="触发关键词">
                        <TagInput v-model="keywordTags" :disabled="!canEdit" />
                      </el-form-item>
                    </el-col>
                    <el-col :span="3">
                      <!-- 无编辑权限整套定义层按钮不出现，避免只读用户看到无效操作 -->
                      <el-form-item v-if="canEdit" label-width="0" class="inline-action">
                        <el-button :disabled="!dirty || saving" @click="cancelDetail">取消</el-button>
                      </el-form-item>
                    </el-col>
                  </el-row>
                  <el-row :gutter="12">
                    <el-col :span="21">
                      <el-form-item label="备注">
                        <el-input
                          v-model="form.remark"
                          maxlength="500"
                          class="remark-input"
                          placeholder="仅内部可见，不注入模型"
                          :disabled="!canEdit"
                        />
                      </el-form-item>
                    </el-col>
                    <el-col :span="3">
                      <el-form-item v-if="canEdit" label-width="0" class="inline-action">
                        <el-button type="primary" :disabled="!dirty" :loading="saving" @click="saveDetail">
                          保存
                        </el-button>
                      </el-form-item>
                    </el-col>
                  </el-row>

                  <!-- 版本工作区：左侧版本列表（号/状态/生效/时间），右侧当前版本编辑器 -->
                  <div class="version-workspace">
                    <aside class="version-list">
                      <div class="version-list__head">
                        <span class="version-list__title">版本</span>
                        <el-button
                          link
                          type="primary"
                          class="version-list__add"
                          :disabled="!canEdit || hasDraft"
                          :title="hasDraft ? '已有草稿，先发布或继续编辑' : '新建草稿'"
                          @click="createDraft()"
                        >
                          新建草稿
                        </el-button>
                      </div>
                      <div v-loading="versionLoading" class="version-list__body">
                        <el-empty
                          v-if="!versionLoading && !versionRows.length"
                          description="尚无版本"
                          :image-size="56"
                        />
                        <!-- 版本项用 div（内含下拉操作，button 不能嵌套 button）；键盘同样可切换 -->
                        <div
                          v-for="row in versionRows"
                          :key="row.id"
                          class="version-item"
                          :class="{ 'is-current': row.id === activeVersionId }"
                          role="button"
                          tabindex="0"
                          @click="selectVersion(row.id)"
                          @keyup.enter="selectVersion(row.id)"
                        >
                          <span class="version-item__no">v{{ row.versionNo }}</span>
                          <el-tag
                            size="small"
                            effect="plain"
                            :type="row.publishStatus === 1 ? 'success' : 'info'"
                          >
                            {{ row.publishStatus === 1 ? '已发布' : '草稿' }}
                          </el-tag>
                          <el-tag v-if="row.isActive === 1" size="small" effect="dark" type="success">
                            生效
                          </el-tag>
                          <span class="version-item__time">{{ row.updateTime || row.createTime || '' }}</span>
                          <!--
                            版本操作收进「⋯」下拉：复制为草稿 / 设置为生效 / 删除。
                            复制为草稿：同 Skill 已有草稿时不可用；设置为生效：仅已发布且未生效；删除：仅草稿。
                          -->
                          <el-dropdown
                            v-if="canEdit"
                            trigger="click"
                            class="version-item__more"
                            :disabled="versionSaving"
                            @command="(cmd: VersionCommand) => onVersionCommand(cmd, row)"
                            @click.stop
                          >
                            <button
                              type="button"
                              class="version-item__more-btn"
                              aria-label="版本操作"
                              title="版本操作"
                              @click.stop
                            >
                              ⋯
                            </button>
                            <template #dropdown>
                              <el-dropdown-menu>
                                <el-dropdown-item command="copy" :disabled="hasDraft">
                                  复制为草稿
                                </el-dropdown-item>
                                <el-dropdown-item
                                  command="activate"
                                  :disabled="row.publishStatus !== 1 || row.isActive === 1"
                                >
                                  设置为生效
                                </el-dropdown-item>
                                <el-dropdown-item command="delete" divided :disabled="row.publishStatus !== 0">
                                  删除
                                </el-dropdown-item>
                              </el-dropdown-menu>
                            </template>
                          </el-dropdown>
                        </div>
                      </div>
                    </aside>

                    <section class="version-editor">
                      <template v-if="versionDetail">
                        <div class="version-editor__head">
                          <span class="version-editor__title">v{{ versionDetail.versionNo }}</span>
                          <el-tag
                            size="small"
                            effect="plain"
                            :type="versionDetail.publishStatus === 1 ? 'success' : 'info'"
                          >
                            {{ versionDetail.publishStatus === 1 ? '已发布' : '草稿' }}
                          </el-tag>
                          <el-tag v-if="versionDetail.isActive === 1" size="small" effect="dark" type="success">
                            生效中
                          </el-tag>
                          <!-- 有无改动改由这里的「未保存」标提示，避免只靠按钮禁用态传达 -->
                          <el-tag v-if="versionDirty" size="small" effect="plain" type="warning">
                            未保存
                          </el-tag>
                          <span class="version-editor__hint">{{ versionHint }}</span>
                          <el-button link type="primary" class="preview-link" @click="previewOpen = true">
                            预览效果
                          </el-button>
                        </div>
                        <!-- 版本编辑器：label 居左，简介与正文左对齐；简介 label 在输入框左侧 -->
                        <el-form class="version-form" label-width="56px" @submit.prevent>
                          <el-form-item label="简介" class="version-field">
                            <el-input
                              v-model="versionDetail.description"
                              type="textarea"
                              maxlength="1000"
                              :rows="2"
                              :disabled="!versionEditable"
                              placeholder="供大模型判断何时加载此技能"
                            />
                          </el-form-item>
                          <el-form-item label="正文" class="field-content">
                            <el-input
                              v-model="versionDetail.contentMd"
                              type="textarea"
                              :rows="1"
                              class="markdown"
                              placeholder="Markdown 正文；保存草稿不会影响线上"
                              :disabled="!versionEditable"
                            />
                          </el-form-item>
                        </el-form>
                      </template>
                      <el-empty v-else description="请选择左侧版本，或新建草稿" />
                    </section>
                  </div>
                </div>
                <!-- 底栏只留草稿的保存/发布；复制、设生效、删除都收进版本行「⋯」 -->
                <div v-if="canEdit && versionEditable" class="detail-actions">
                  <div class="detail-actions__group">
                    <!--
                      保存按钮不跟脏标记联动：复制来的草稿内容与来源版一致，脏标记为 false，
                      若据此禁用会被误解为「按钮坏了」。是否真的有改动在提交时判断。
                    -->
                    <el-button :disabled="versionSaving" :loading="versionSaving" @click="saveVersionDraft">
                      保存草稿
                    </el-button>
                    <el-button type="primary" :loading="versionSaving" @click="publishVersion">
                      发布
                    </el-button>
                  </div>
                </div>
              </el-form>
            </div>
          </div>
        </div>
      </div>
    </div>

    <FlipGhost :snap="ghostSnap" :frame="ghostFrame" :landing="ghostLanding" />
    <SkillFormDialog v-model="createOpen" @submit="saveCreate" />
    <!-- 按 Anthropic SKILL.md 规范拼 frontmatter + 正文后渲染；预览的是当前选中版本 -->
    <SkillPreviewDrawer
      v-model="previewOpen"
      :title="form?.title || form?.name || 'Skill 预览'"
      :name="form?.name || ''"
      :description="versionDetail?.description || ''"
      :trigger-keywords="keywordTags"
      :skill-title="form?.title || ''"
      :content="versionDetail?.contentMd || ''"
    />
  </div>
</template>

<script setup lang="ts">
/** KeepAlive 的 include 比对组件名；必须与 router meta.cacheName 一致，改名即失去缓存。 */
defineOptions({ name: 'SkillManage' })
/**
 * Skill 管理页。所属域：skill。
 * 网格↔主从飞卡复用 viewFlip / FlipGhost / ViewModeToggle / browse-workspace，禁止本页另写动画。
 * 无路由 id：仅本地 viewMode + activeId；保存/取消跟正文脏标记，启停开关即时提交。
 */
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { Plus, RefreshRight, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  activateSkillVersion,
  changeSkillStatus,
  createSkill,
  createSkillVersionDraft,
  deleteSkill,
  deleteSkillVersionDraft,
  getSkill,
  getSkillVersion,
  listSkillVersions,
  pageSkills,
  publishSkillVersion,
  saveSkillVersionDraft,
  updateSkill
} from '@/api/skill'
import ConfigCard from '@/components/ConfigCard.vue'
import FlipGhost from '@/components/browse/FlipGhost.vue'
import PageHeader from '@/components/PageHeader.vue'
import TablePager from '@/components/TablePager.vue'
import SkillFormDialog from '@/components/skill/SkillFormDialog.vue'
import SkillPreviewDrawer from '@/components/skill/SkillPreviewDrawer.vue'
import TagInput from '@/components/skill/TagInput.vue'
import ViewModeToggle from '@/components/browse/ViewModeToggle.vue'
import { useBrowseFlip } from '@/composables/useBrowseFlip'
import type { FlipCardSnap } from '@/composables/viewFlip'
import { CARD_HEIGHT_NORMAL, lastPage, useGridPageSize } from '@/composables/gridPageSize'
import { confirmDiscardUnsaved } from '@/utils/unsavedConfirm'
import { useUserStore } from '@/stores/user'
import type { SkillCard, SkillDetail, SkillVersionDetail, SkillVersionRow } from '@/types/skill'

/**
 * 定义层脏检查快照；不含 status（开关即时提交），也不含简介/正文/版本号
 * —— 它们属于 skill_version，保存走「保存草稿 / 发布」，混进来会变成手改冒充升版。
 */
interface EditSnapshot {
  name: string
  title: string
  triggerKeywords: string[]
  remark: string
}

const RAIL_PAGE_SIZE = 100
const RAIL_MAX_PAGES = 20

const user = useUserStore()

const listLoading = ref(false)
const detailLoading = ref(false)
const saving = ref(false)
const statusSaving = ref(false)
const listError = ref('')
const detailError = ref('')
const createOpen = ref(false)
/** 正文预览抽屉开关；只读，不影响脏标记 */
const previewOpen = ref(false)
const removingId = ref(0)
/** 网格当前页。 */
const rows = ref<SkillCard[]>([])
/** 主从左栏全量（进主从后拉）；飞卡前可用网格页占位。 */
const railSource = ref<SkillCard[]>([])
const total = ref(0)
const form = ref<SkillDetail | null>(null)
/** 当前 Skill 的版本列表（不含正文），版本号倒序 */
const versionRows = ref<SkillVersionRow[]>([])
/** 当前选中版本详情（含正文）；编辑器与预览都读这一份 */
const versionDetail = ref<SkillVersionDetail | null>(null)
const versionLoading = ref(false)
const versionSaving = ref(false)
const activeVersionId = ref(0)
/** 版本编辑器基准：只含可改字段，用于脏检查与取消回退 */
const versionBaseline = ref('')
/**
 * 详情表单里的触发关键词（Tag 数组）。
 * 用 computed 兜住 SkillDetail.triggerKeywords 的可选类型，同时保持与 form 同源：
 * TagInput 回写、脏检查、保存、预览都读这一份。
 */
const keywordTags = computed<string[]>({
  get: () => form.value?.triggerKeywords ?? [],
  set: (tags) => {
    if (form.value) {
      form.value.triggerKeywords = tags
    }
  }
})
/** 载入或保存后的基准；取消时回退到这里 */
const baseline = ref<EditSnapshot | null>(null)
/** 网格分页与筛选；size 由一屏容量实测得出，不再写死 10。 */
const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  status: undefined as number | undefined
})

/** 卡片墙容器（.grid-wrap）：一屏容量按它的宽高实测 */
const gridWrapRef = ref<HTMLElement | null>(null)
/**
 * 一屏容量分页：pageSize = 当前栅格列数 × 行数（上限 100）。
 * Skill 卡为 ConfigCard normal 档 160px；尺寸变化（含侧栏折叠、窗口 resize）防抖重算。
 */
const { pageSize: gridPageSize, whenReady: gridPageSizeReady, remeasure: remeasureGridSize } = useGridPageSize({
  container: () => gridWrapRef.value,
  options: { cardHeight: CARD_HEIGHT_NORMAL }
})

/**
 * 首屏是否已把实测 pageSize 写进分页态。
 * 首次测量由 {@link loadRows} 自己消费（它在请求前就等测量完成），
 * 所以容量 watch 只有在置位后才需要重取，避免首屏连打两次同一个请求。
 */
let gridSizeSynced = false

/**
 * 一屏容量变化 → 写回分页态，并在当前页越界时落到合法末页。
 * 首屏那一次跳过重取（loadRows 已按测量结果取过数）；resize 引起的后续变化才重新取数。
 */
watch(gridPageSize, (next) => {
  if (!next) {
    return
  }
  if (next !== query.size) {
    query.size = next
    query.current = Math.min(query.current, lastPage(total.value, next))
  }
  if (!gridSizeSynced) {
    return
  }
  void loadRows()
})

/**
 * 网格↔主从骨架。Skill 网格是**服务端分页**（`query` + `total` + TablePager），
 * 所以 `takeOverGrid: false`：骨架只管视图态、图层显隐、左栏关键词与飞卡导航；
 * 网格筛选/页码/一屏容量仍由本页 `query` + `useGridPageSize` 负责（行为与拆分前一致）。
 */
const {
  viewMode,
  activeId,
  flipBusy,
  flyingId,
  ghostSnap,
  ghostFrame,
  ghostLanding,
  detailPhase,
  showGridLayer,
  showMasterLayer,
  pagedRows: gridRows,
  railKeyword,
  railRows,
  findRowEl,
  onGridCardClick,
  onViewModeChange
} = useBrowseFlip<SkillCard, Record<string, unknown>>({
  rows,
  idOf: (row) => row.id,
  snapOf: snapFromSkill,
  matchRail: (row, key) =>
    row.name.toLowerCase().includes(key) || (row.title || '').toLowerCase().includes(key),
  railSource,
  // 服务端分页：骨架不接管筛选与切片
  takeOverGrid: false,
  /**
   * 起飞前：左栏先用当前页占位（保证落点节点立刻存在），同时并行拉详情。
   * 与原实现同序——先占位，再拉详情，再飞。
   */
  prepare: (row) => {
    if (!railSource.value.length) {
      railSource.value = rows.value.slice()
    }
    void loadDetail(row.id)
  },
  /**
   * 落地后：飞卡走了动画才延迟 240ms 补全左栏（等落地帧结束）；
   * 无动画直切时立即补全（与原实现一致）。
   */
  landed: (_row, _prepared, meta) => {
    if (meta.animated) {
      window.setTimeout(() => {
        void loadAll(true)
      }, 240)
      return
    }
    void loadAll(true)
  },
  /** 回网格前：定义层或草稿有未保存改动要先确认，取消则保持主从态 */
  beforeLeave: () => {
    if (!dirty.value && !versionDirty.value) {
      return true
    }
    return confirmDiscardUnsaved('当前 Skill')
  },
  /** 回程量落点前：网格页若还没有这张卡（被全量覆盖过），先补取当前页 */
  prepareLeave: async (row) => {
    if (!findRowEl(row.id, 'grid') && rows.value.every((item) => item.id !== row.id)) {
      await loadRows()
      await nextTick()
    }
  },
  /**
   * 回网格收尾：清空右栏详情；无动画直切或飞卡失败时重取当前页。
   * 关键词由骨架清空（与原实现一致）。
   */
  left: (result) => {
    clearDetail()
    if (!result.animated || !result.ok) {
      void loadRows()
    }
  },
  // 回到网格后重申一次一屏容量（主从态下网格层不参与布局，量出来是 0）
  onReturnToGrid: () => {
    remeasureGridSize()
  }
})

const canEdit = computed(() => user.hasPerm('skill:edit'))

/** 相对 baseline 是否有未保存改动（不含开关状态）。 */
const dirty = computed(() => {
  if (!form.value || !baseline.value || !canEdit.value) {
    return false
  }
  return snapshotOf(form.value, keywordTags.value) !== serialize(baseline.value)
})

/** 版本列表里是否存在草稿：同 Skill 至多一条，决定「新建草稿」是否可用 */
const hasDraft = computed(() => versionRows.value.some((row) => row.publishStatus === 0))

/** 生效版本只读展示；没有发布过显示「未发布」 */
const activeVersionLabel = computed(() =>
  form.value?.activeVersionNo ? `v${form.value.activeVersionNo}` : '未发布'
)

/** 草稿才可改正文与简介；已发布版本只读 */
const versionEditable = computed(() => !!versionDetail.value && versionDetail.value.publishStatus === 0)

/** 版本编辑器脏标记：只比简介与正文 */
const versionDirty = computed(() => {
  if (!versionDetail.value || !versionEditable.value) {
    return false
  }
  return versionSignature(versionDetail.value) !== versionBaseline.value
})

/** 编辑器右上提示：已发布说明只读与「⋯」菜单入口，草稿说明保存不影响线上 */
const versionHint = computed(() => {
  if (!versionDetail.value) {
    return ''
  }
  if (versionDetail.value.publishStatus === 1) {
    return versionDetail.value.isActive === 1
      ? '当前生效版本，正文只读'
      : '已发布历史版本，正文只读；可在左侧「⋯」设置为生效回滚'
  }
  return '草稿：保存不影响线上，发布后才生效'
})

/**
 * Skill 行压成飞卡快照：副行用 title；标签仅启停。
 *
 * @param row 列表/左栏行
 */
function snapFromSkill(row: SkillCard): FlipCardSnap {
  return {
    id: row.id,
    name: row.name,
    code: row.title || '',
    tags: [
      {
        label: row.status === 1 ? '启用' : '停用',
        tone: row.status === 1 ? 'on' : 'off'
      }
    ]
  }
}

/**
 * 从详情 + 当前 Tag 列表抽出可编辑快照。
 *
 * @param detail   当前表单
 * @param keywords 当前 Tag 数组
 * @return 快照对象
 */
function toSnapshot(detail: SkillDetail, keywords: string[]): EditSnapshot {
  return {
    name: detail.name || '',
    title: detail.title || '',
    triggerKeywords: [...keywords],
    remark: detail.remark || ''
  }
}

/** 序列化后比较，避免对象引用误判。 */
function serialize(snap: EditSnapshot) {
  return JSON.stringify(snap)
}

function snapshotOf(detail: SkillDetail, keywords: string[]) {
  return serialize(toSnapshot(detail, keywords))
}

/**
 * 版本编辑器快照：只含草稿可改的简介与正文，用于脏检查。
 *
 * @param version 版本详情
 * @return 可比较的字符串
 */
function versionSignature(version: SkillVersionDetail) {
  return JSON.stringify({
    description: version.description || '',
    contentMd: version.contentMd || ''
  })
}

/**
 * 把接口详情归一进表单，并刷新 baseline。
 * 关键词数组直接作为 Tag 列表；库内逗号串的拆分已由后端完成。
 * 简介/正文/版本号不再进表单：它们来自版本接口。
 *
 * @param data 接口详情
 */
function applyDetail(data: SkillDetail) {
  form.value = {
    ...data,
    description: data.description || '',
    triggerKeywords: [...(data.triggerKeywords || [])],
    remark: data.remark || ''
  }
  baseline.value = toSnapshot(form.value, form.value.triggerKeywords || [])
}

/**
 * 把版本详情归一进编辑器并刷新版本 baseline。
 *
 * @param data 版本详情
 */
function applyVersionDetail(data: SkillVersionDetail) {
  versionDetail.value = {
    ...data,
    description: data.description || '',
    contentMd: data.contentMd || ''
  }
  versionBaseline.value = versionSignature(versionDetail.value)
}

/** 清空右侧详情态。 */
function clearDetail() {
  form.value = null
  baseline.value = null
  versionRows.value = []
  versionDetail.value = null
  versionBaseline.value = ''
  activeVersionId.value = 0
  detailError.value = ''
  previewOpen.value = false
}

/** 首次测量前不取数，避免先按默认条数拉一次再按实测 pageSize 重拉；序号用于丢弃过期请求 */
let rowsSeq = 0

async function loadRows() {
  const seq = ++rowsSeq
  // 首屏等一屏容量测完再用它作为 size；后续调用时该 Promise 已兑现，等同同步
  const measured = await gridPageSizeReady()
  if (seq !== rowsSeq) {
    return
  }
  if (measured && measured !== query.size) {
    query.size = measured
    query.current = Math.min(query.current, lastPage(total.value, measured))
  }
  // 首屏已按实测 pageSize 取数：此后容量再变才需要 watch 触发重取
  gridSizeSynced = true
  listLoading.value = true
  listError.value = ''
  try {
    const { data } = await pageSkills({
      current: query.current,
      size: query.size,
      keyword: query.keyword.trim() || undefined,
      status: query.status === 0 || query.status === 1 ? query.status : undefined
    })
    // 落地前又被新请求顶掉：保留最新一次的结果，旧响应直接丢弃
    if (seq !== rowsSeq) {
      return
    }
    const page = data.data
    rows.value = page?.records || []
    total.value = page?.total || 0
    /*
     * 删到当前页没有数据（删空了末页）：回落到合法末页再取一次，避免停在空白页。
     * 仅越界时退避；正常的 0 条结果（total=0）不做任何事。
     */
    const fallback = lastPage(total.value, query.size)
    if (!rows.value.length && total.value > 0 && query.current > fallback) {
      query.current = fallback
      void loadRows()
    }
  } catch {
    if (seq !== rowsSeq) {
      return
    }
    listError.value = 'Skill 列表加载失败'
    rows.value = []
    total.value = 0
  } finally {
    if (seq === rowsSeq) {
      listLoading.value = false
    }
  }
}

/** 查询：从第一页按当前条件拉取。 */
function searchGrid() {
  query.current = 1
  void loadRows()
}

/** 重置：清空筛选并重查。 */
function resetGridFilter() {
  query.keyword = ''
  query.status = undefined
  searchGrid()
}

/**
 * 主从左栏拉全量（分页拼）；静默时不挡 loading 条。
 *
 * @param silent 是否静默刷新
 */
async function loadAll(silent = false) {
  if (!silent) {
    listLoading.value = true
  }
  listError.value = ''
  try {
    const all: SkillCard[] = []
    let current = 1
    let totalCount = 0
    do {
      const { data } = await pageSkills({
        current,
        size: RAIL_PAGE_SIZE
      })
      const page = data.data
      const records = page?.records || []
      totalCount = page?.total || 0
      all.push(...records)
      if (records.length < RAIL_PAGE_SIZE) {
        break
      }
      current += 1
    } while (all.length < totalCount && current <= RAIL_MAX_PAGES)
    railSource.value = all
    total.value = totalCount
  } catch {
    listError.value = 'Skill 列表加载失败'
    railSource.value = []
  } finally {
    if (!silent) {
      listLoading.value = false
    }
  }
}

/**
 * 载入右侧详情，并顺带拉版本列表。
 *
 * @param id              Skill id
 * @param preferVersionId 期望选中的版本 id（保存/发布后保住当前版本）；0 表示按默认优先级
 */
async function loadDetail(id: number, preferVersionId = 0) {
  detailLoading.value = true
  detailError.value = ''
  try {
    const { data } = await getSkill(id)
    applyDetail(data.data)
    await loadVersions(id, preferVersionId)
  } catch {
    detailError.value = '详情加载失败'
    clearDetail()
  } finally {
    detailLoading.value = false
  }
}

/**
 * 拉版本列表并选中一个版本。
 * 默认优先级：指定 id → 草稿 → 生效版 → 最新版（列表已按号倒序）。
 *
 * @param skillId         Skill id
 * @param preferVersionId 期望选中的版本 id
 */
async function loadVersions(skillId: number, preferVersionId = 0) {
  versionLoading.value = true
  try {
    const { data } = await listSkillVersions(skillId)
    versionRows.value = data.data || []
    const picked =
      versionRows.value.find((row) => row.id === preferVersionId) ||
      versionRows.value.find((row) => row.publishStatus === 0) ||
      versionRows.value.find((row) => row.isActive === 1) ||
      versionRows.value[0]
    if (picked) {
      await fetchVersion(picked.id)
    } else {
      versionDetail.value = null
      versionBaseline.value = ''
      activeVersionId.value = 0
    }
  } catch {
    versionRows.value = []
    versionDetail.value = null
    versionBaseline.value = ''
    activeVersionId.value = 0
  } finally {
    versionLoading.value = false
  }
}

/**
 * 取单个版本详情并切到该版本。切换前若有未保存草稿先确认。
 *
 * @param versionId 版本 id
 */
async function fetchVersion(versionId: number) {
  if (!form.value) {
    return
  }
  const { data } = await getSkillVersion(form.value.id, versionId)
  activeVersionId.value = versionId
  applyVersionDetail(data.data)
}

/**
 * 点击版本列表切换版本。
 *
 * @param versionId 版本 id
 */
async function selectVersion(versionId: number) {
  if (versionId === activeVersionId.value) {
    return
  }
  if (versionDirty.value) {
    const keep = await confirmDiscardUnsaved('当前草稿')
    if (!keep) {
      return
    }
  }
  try {
    await fetchVersion(versionId)
  } catch {
    ElMessage.error('版本加载失败')
  }
}

/**
 * 新建草稿。不传 fromVersionId 为空白草稿；传则复制该已发布版本正文。
 * 同 Skill 至多一条草稿，已有草稿时按钮禁用。
 *
 * @param fromVersionId 复制来源的已发布版本 id
 */
async function createDraft(fromVersionId?: number) {
  if (!form.value || versionSaving.value) {
    return
  }
  versionSaving.value = true
  try {
    const { data } = await createSkillVersionDraft(
      form.value.id,
      fromVersionId ? { fromVersionId } : {}
    )
    await loadVersions(form.value.id, data.data.id)
    onCatalogChanged()
    ElMessage.success('已新建草稿')
  } catch {
    // 后端已给出中文业务错误（已有草稿 / 来源非法），这里不重复弹
  } finally {
    versionSaving.value = false
  }
}

/**
 * 删除草稿。只有草稿可删；已发布版本是历史快照，后端也会拒绝。
 * 删的若正是当前选中版本，则重新按默认优先级选一个。
 *
 * @param row 版本列表行
 */
async function removeDraft(row: SkillVersionRow) {
  if (!form.value || versionSaving.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除草稿 v${row.versionNo}？草稿正文将不可恢复，线上不受影响。`,
      '删除草稿',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const skillId = form.value.id
  const wasCurrent = row.id === activeVersionId.value
  versionSaving.value = true
  try {
    await deleteSkillVersionDraft(skillId, row.id)
    // 删的是当前版本时让 loadVersions 自己按 草稿→生效→最新 重新挑一个
    await loadVersions(skillId, wasCurrent ? 0 : activeVersionId.value)
    onCatalogChanged()
    ElMessage.success('草稿已删除')
  } finally {
    versionSaving.value = false
  }
}

/**
 * 保存草稿：只写版本行，不影响线上注入。
 * <p>
 * 不做脏校验拦截：复制来的草稿与来源版内容相同，脏标记天然为 false，
 * 若因此让按钮或提交静默失败，用户会以为保存坏了。真无改动时给一条明确提示即可。
 */
async function saveVersionDraft() {
  if (!form.value || !versionDetail.value || versionSaving.value) {
    return
  }
  // 复制来的草稿内容与来源版一致，此时保存是无副作用的空操作，直接提示而不是白跑一次接口
  if (!versionDirty.value) {
    ElMessage.info('内容未修改，无需保存')
    return
  }
  const skillId = form.value.id
  const versionId = versionDetail.value.id
  versionSaving.value = true
  try {
    const { data } = await saveSkillVersionDraft(skillId, versionId, {
      description: versionDetail.value.description || '',
      contentMd: versionDetail.value.contentMd || ''
    })
    applyVersionDetail(data.data)
    await loadVersions(skillId, versionId)
    onCatalogChanged()
    ElMessage.success('草稿已保存')
  } finally {
    versionSaving.value = false
  }
}

/** 发布当前草稿：成为唯一生效版本，线上注入立即改读该版正文。 */
async function publishVersion() {
  if (!form.value || !versionDetail.value || versionSaving.value) {
    return
  }
  const version = versionDetail.value
  try {
    await ElMessageBox.confirm(
      `发布 v${version.versionNo} 后立即成为唯一生效版本，线上 Skill 注入将改为该版正文。`,
      '发布 Skill 版本',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const skillId = form.value.id
  versionSaving.value = true
  try {
    await publishSkillVersion(skillId, version.id)
    // 不回读详情：避免把定义层未保存的修改冲掉；生效号本地同步即可
    if (form.value) {
      form.value.activeVersionNo = version.versionNo
      form.value.version = String(version.versionNo)
    }
    await loadVersions(skillId, version.id)
    onCatalogChanged()
    ElMessage.success(`已发布 v${version.versionNo}`)
  } finally {
    versionSaving.value = false
  }
}

/** 把指定已发布版本设回生效（正文回滚）。版本行「⋯」菜单调用，不再依赖当前选中版本。 */
async function activateVersion(row: SkillVersionRow) {
  if (!form.value || versionSaving.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `将 v${row.versionNo} 设置为生效后，线上 Skill 注入回到该版正文。`,
      '设置生效版本',
      { type: 'warning', confirmButtonText: '设置为生效', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const skillId = form.value.id
  versionSaving.value = true
  try {
    await activateSkillVersion(skillId, row.id)
    // 不回读详情：保留定义层未保存修改，仅同步生效号
    if (form.value) {
      form.value.activeVersionNo = row.versionNo
      form.value.version = String(row.versionNo)
    }
    await loadVersions(skillId, row.id)
    onCatalogChanged()
    ElMessage.success(`v${row.versionNo} 已生效`)
  } finally {
    versionSaving.value = false
  }
}

/** 版本行「⋯」下拉命令：复制为草稿 / 设置为生效 / 删除。 */
type VersionCommand = 'copy' | 'activate' | 'delete'

/**
 * 分发版本行下拉命令。菜单项的可用性已由 disabled 控制，这里再兜一次状态判断。
 *
 * @param command 命令名
 * @param row     目标版本行
 */
function onVersionCommand(command: VersionCommand, row: SkillVersionRow) {
  if (command === 'copy') {
    if (!hasDraft.value) {
      void createDraft(row.id)
    }
    return
  }
  if (command === 'activate') {
    if (row.publishStatus === 1 && row.isActive !== 1) {
      void activateVersion(row)
    }
    return
  }
  if (command === 'delete') {
    if (row.publishStatus === 0) {
      void removeDraft(row)
    }
  }
}

/** 列表变更后按当前视图刷新网格或左栏。 */
function onCatalogChanged() {
  if (viewMode.value === 'master') {
    void loadAll(true)
    return
  }
  void loadRows()
}

/** 主从内切 Skill：只改选中并拉详情，无飞卡。切走前若有未保存草稿先确认。 */
async function openDetail(row: SkillCard) {
  if (flipBusy.value) {
    return
  }
  if (row.id === activeId.value) {
    return
  }
  if (dirty.value || versionDirty.value) {
    const keep = await confirmDiscardUnsaved('当前 Skill')
    if (!keep) {
      return
    }
  }
  activeId.value = row.id
  void loadDetail(row.id)
}

/**
 * 网格卡删除；二次确认后调用。
 *
 * @param row 待删行
 */
async function removeCard(row: SkillCard) {
  if (removingId.value || flipBusy.value) {
    return
  }
  removingId.value = row.id
  try {
    await deleteSkill(row.id)
    ElMessage.success('已删除')
    await loadRows()
  } finally {
    removingId.value = 0
  }
}

/**
 * 新建成功后直接进主从选中该 id（无飞卡，对齐 Agent create）。
 *
 * @param payload 新建表单
 * @param done 关闭弹窗 loading
 */
async function saveCreate(payload: Record<string, unknown>, done: () => void) {
  try {
    const { data } = await createSkill(payload)
    createOpen.value = false
    const id = data.data?.id
    if (id) {
      activeId.value = id
      viewMode.value = 'master'
      railSource.value = rows.value.slice()
      void loadDetail(id)
      void loadAll(true)
      return
    }
    query.current = 1
    await loadRows()
  } finally {
    done()
  }
}

/** 保存定义层字段（名称/标题/触发词/备注）；简介、正文与版本号走版本接口。 */
async function saveDetail() {
  if (!form.value || !activeId.value || !dirty.value) {
    return
  }
  if (!form.value.name.trim() || !form.value.title.trim()) {
    ElMessage.warning('请填写名称和标题')
    return
  }
  saving.value = true
  try {
    const { data } = await updateSkill(activeId.value, {
      name: form.value.name.trim(),
      title: form.value.title.trim(),
      triggerKeywords: [...keywordTags.value],
      remark: form.value.remark?.trim() || ''
    })
    // 保留当前开关态（可能刚改过），再叠回接口字段
    const status = form.value.status
    applyDetail({ ...data.data, status })
    onCatalogChanged()
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

/** 取消：回到载入/上次保存的定义层字段，不改开关，也不动版本编辑器。 */
function cancelDetail() {
  if (!form.value || !baseline.value) {
    return
  }
  const snap = baseline.value
  form.value = {
    ...form.value,
    name: snap.name,
    title: snap.title,
    // 同步数组字段，避免脏检查与下次保存口径不一致
    triggerKeywords: [...snap.triggerKeywords],
    remark: snap.remark
  }
}

/**
 * 启停开关即时提交；同步 baseline 无关，不点亮保存/取消。
 *
 * @param enabled 目标是否启用
 */
async function onStatusSwitch(enabled: boolean) {
  if (!form.value || !activeId.value) {
    return
  }
  const next = enabled ? 1 : 0
  if (form.value.status === next) {
    return
  }
  statusSaving.value = true
  try {
    const { data } = await changeSkillStatus(activeId.value, next)
    form.value = {
      ...form.value,
      status: data.data.status,
      updateTime: data.data.updateTime
    }
    onCatalogChanged()
  } finally {
    statusSaving.value = false
  }
}

onMounted(loadRows)
</script>

<style scoped>
/* 壳层靠 browse-workspace；左栏走公共样式，本页仅详情表单与底栏 */

.block-alert {
  margin: 12px 16px 0;
}

.card-del {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #9ca3af;
  font-size: 20px;
  font-weight: 400;
  line-height: 1;
  cursor: pointer;
}

.card-del:hover:not(:disabled) {
  color: #ef4444;
  background: #fef2f2;
}

.card-del:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.card-name {
  margin: 0;
  padding-right: 32px;
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-title {
  margin: 0;
  overflow: hidden;
  color: var(--ah-text-secondary);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.badge-on {
  --el-tag-text-color: #10b981;
  --el-tag-border-color: #a7f3d0;
  --el-tag-bg-color: #ecfdf5;
}

.badge-off {
  --el-tag-text-color: #6b7280;
  --el-tag-border-color: #e5e7eb;
  --el-tag-bg-color: #f3f4f6;
}

.muted {
  color: var(--ah-text-muted);
}

/* 卡片简介单行截断：长文只显示一行，完整内容由 tooltip 兜底 */
.card-desc {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * ConfigCard 默认简介区是两行 -webkit-box；Skill 卡片按本页口径收紧为单行。
 * 用 block 覆盖 -webkit-box，避免 line-clamp 与 nowrap 叠加出现半行。
 */
.card :deep(.config-card__body-inner) {
  display: block;
  max-height: none;
  overflow: hidden;
}

.version {
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
}

/* 主从区拉满到视口底部，详情卡下边框贴底（与左栏 max-height 口径一致） */
.master {
  align-items: stretch;
  height: calc(100vh - 220px);
}

.detail-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.detail-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--ah-surface);
  border: 1px solid var(--ah-border);
  border-radius: var(--ah-radius-lg, 12px);
  box-shadow: var(--ah-shadow);
}

.detail-form {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.detail-form__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  /* 正文用 flex 吃满剩余高度，外层不滚，避免首屏内部滚动条 */
  overflow: hidden;
  padding: 14px 16px 4px;
}

/* 正文：Label 左侧顶对齐；整项 flex 吃满剩余空间 */
.field-content {
  display: flex;
  flex: 1;
  align-items: stretch;
  margin-bottom: 0;
  min-height: 0;
}

.field-content :deep(.el-form-item__label) {
  padding-top: 4px;
  line-height: 1.4;
}

.field-content :deep(.el-form-item__content) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.preview-link {
  height: auto;
  padding: 0;
  font-size: 12px;
  font-weight: 400;
}

/* Label 一律不换行；「触发关键词」五字靠 96px label-width 撑开 */
.detail-form :deep(.el-form-item__label) {
  white-space: nowrap;
}

/* 备注浅灰底，与会进 Prompt 的简介在视觉上分开 */
.remark-input :deep(textarea) {
  color: var(--ah-text-secondary, #6b7280);
  font-size: 12px;
  background-color: #f5f7fa;
}

.remark-input :deep(.el-textarea__inner) {
  background-color: #f5f7fa;
}

/* 底栏固定在详情卡底部，长正文滚动时始终可见 */
.detail-actions {
  display: flex;
  flex: none;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 0;
  padding: 12px 16px;
  border-top: 1px solid var(--ah-border);
  background: var(--ah-surface);
}

/* 正文框撑满版本编辑器剩余高度；字多时只在框内滚 */
.markdown {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.markdown :deep(.el-textarea__inner) {
  flex: 1;
  height: 100% !important;
  min-height: 0;
  resize: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* ===== 版本工作区：左侧版本列表 + 右侧编辑器 ===== */
.version-workspace {
  display: flex;
  flex: 1;
  gap: 12px;
  min-height: 0;
  margin-top: 2px;
}

.version-list {
  display: flex;
  flex: none;
  flex-direction: column;
  width: 200px;
  min-height: 0;
  border-right: 1px solid var(--ah-border);
  padding-right: 10px;
}

.version-list__head {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 6px;
}

.version-list__title {
  color: var(--ah-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.version-list__add {
  height: auto;
  padding: 0;
  font-size: 12px;
  font-weight: 400;
}

.version-list__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  min-height: 0;
  overflow-y: auto;
  padding-right: 2px;
}

/* 定义层右侧按钮列：与状态开关同列，按钮贴右 */
.inline-action {
  margin-bottom: 18px;
}

.inline-action :deep(.el-form-item__content) {
  justify-content: flex-end;
}

/* 状态整组（含「状态」两字）一起贴右：el-form-item 本身是 flex，直接右对齐即可 */
.status-item {
  justify-content: flex-end;
}

/* 右对齐后 label 与开关连成一组，label 内部再右对齐文字，避免 42px 内出现空隙 */
.status-item :deep(.el-form-item__label) {
  justify-content: flex-end;
}

.status-item :deep(.el-form-item__content) {
  flex: none;
  justify-content: flex-end;
}

.version-item {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  width: 100%;
  padding: 8px 26px 8px 10px;
  border: 1px solid var(--ah-border);
  border-radius: 8px;
  background: var(--ah-surface);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}

/* 版本行「⋯」下拉：绝对定位右上角，不挤压版本号与状态标 */
.version-item__more {
  position: absolute;
  top: 4px;
  right: 4px;
}

.version-item__more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #9ca3af;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.version-item__more-btn:hover {
  color: var(--ah-primary, #635bff);
  background: #f1f0ff;
}

.version-item:hover {
  border-color: var(--ah-primary, #635bff);
}

.version-item.is-current {
  border-color: var(--ah-primary, #635bff);
  background: #f5f3ff;
}

.version-item__no {
  font-size: 13px;
  font-weight: 650;
}

.version-item__time {
  flex-basis: 100%;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 11px;
}

.version-editor {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

/* 版本编辑器内嵌表单：label 置顶，简介与正文都从左边界起排 */
.version-form {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

/* 版本编辑器标题行：v3 版本号用黑框高亮，与简介区隔开 */
.version-editor__head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding-bottom: 6px;
}

/* v3 版本号：黑框高亮，明确当前正在编辑哪一版 */
.version-editor__title {
  padding: 0 7px;
  border: 1px solid #1f2937;
  border-radius: 4px;
  color: #1f2937;
  font-size: 13px;
  font-weight: 650;
  line-height: 20px;
}

.version-editor__hint {
  flex: 1;
  overflow: hidden;
  color: var(--ah-text-muted, #9ca3af);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 版本字段间距收紧，正文仍吃掉剩余高度 */
.version-field {
  margin-bottom: 8px;
}

.detail-actions__group {
  display: flex;
  gap: 8px;
}

.draft-tag {
  margin-left: 6px;
  transform: scale(0.9);
}
</style>
