<template>
  <el-dropdown
    trigger="click"
    placement="bottom-end"
    @visible-change="onVisibleChange"
    @command="onCommand"
  >
    <el-button :loading="loading" :disabled="!workflowId">版本切换</el-button>
    <template #dropdown>
      <el-dropdown-menu class="wf-release-menu">
        <!-- 列表失败时给一行可读提示，不弹全局错误，避免打开菜单就打扰 -->
        <el-dropdown-item v-if="loadError" disabled>{{ loadError }}</el-dropdown-item>
        <el-dropdown-item v-else-if="!loading && !releases.length" disabled>
          还没有发布版本
        </el-dropdown-item>
        <el-dropdown-item
          v-for="item in releases"
          :key="item.id"
          :command="item.id"
          :disabled="activating"
        >
          <span style="font-weight: 600; margin-right: 8px">v{{ item.version }}</span>
          <span
            :title="item.publishNote || ''"
            style="
              max-width: 220px;
              overflow: hidden;
              color: var(--el-text-color-secondary);
              text-overflow: ellipsis;
              white-space: nowrap;
              margin-right: 8px;
            "
          >
            {{ item.publishNote || '无发布说明' }}
          </span>
          <el-tag v-if="item.id === currentReleaseId" size="small" effect="plain">当前</el-tag>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
/**
 * 画布顶栏的版本切换入口。所属域：workflow 的前端。
 * <p>职责：列出已发布版本并切换线上默认；入口从调试框挪到「发布」旁。
 * <p>约束：切换规则、409 确认与「不改就生效、改完再发布才是新版」的语义保持原样，
 * 本组件只负责调用现有接口，并把后端返回的图交给宿主页恢复画布。
 */
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { activateWorkflowRelease, listWorkflowReleases } from '@/api/workflow'
import type { WorkflowReleaseActivateResult, WorkflowReleaseRow } from '@/types/workflow'

const props = defineProps<{
  /** 当前工作流定义 id；0 表示尚未就绪 */
  workflowId: number
  /** 当前默认发布版本 id，用于在菜单里标「当前」 */
  currentReleaseId?: number | null
  /** 画布相对已保存草稿还有改动；切换前先提醒会覆盖 */
  canvasDirty?: boolean
}>()

const emit = defineEmits<{
  /** 切换成功；宿主页据返回的 graph 恢复画布 */
  (event: 'activated', result: WorkflowReleaseActivateResult): void
}>()

/** 已发布版本，按版本号倒序 */
const releases = ref<WorkflowReleaseRow[]>([])
/** 版本列表加载中 */
const loading = ref(false)
/** 切换请求在飞：防止连点重复提交 */
const activating = ref(false)
/** 版本列表失败的可读提示，直接显示在菜单里 */
const loadError = ref('')

/**
 * 每次打开菜单都重新拉版本：发布后菜单里的版本列表要能立刻看到新版本。
 *
 * @param visible 菜单是否展开
 */
function onVisibleChange(visible: boolean) {
  if (visible) {
    void loadReleases()
  }
}

/**
 * 读取已发布版本。请求返回时若已切换工作流，丢弃这份列表。
 */
async function loadReleases() {
  const id = props.workflowId
  if (!id || loading.value) {
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await listWorkflowReleases(id)
    if (props.workflowId === id) {
      releases.value = data.data || []
    }
  } catch {
    if (props.workflowId === id) {
      releases.value = []
      loadError.value = '版本列表加载失败'
    }
  } finally {
    if (props.workflowId === id) {
      loading.value = false
    }
  }
}

/**
 * 选中某个版本：先按画布是否有未保存改动做本地确认，再调切换接口。
 *
 * @param releaseId 目标发布版本 id
 */
async function onCommand(releaseId: number) {
  if (!props.workflowId || activating.value) {
    return
  }
  let overwrite = false
  if (props.canvasDirty) {
    try {
      await ElMessageBox.confirm(
        '画布上还有未发布的改动，切换会覆盖当前画布，并把线上默认切到所选版本。',
        '切换版本',
        { confirmButtonText: '覆盖并切换', cancelButtonText: '取消', type: 'warning' }
      )
    } catch {
      return
    }
    overwrite = true
  }
  await submitActivate(releaseId, overwrite)
}

/**
 * 调用切换接口。409 表示草稿和当前发布不一致，确认后带 overwrite 重试。
 *
 * @param releaseId 目标发布版本 id
 * @param overwrite 是否已经确认覆盖未发布改动
 */
async function submitActivate(releaseId: number, overwrite: boolean) {
  activating.value = true
  try {
    const { data } = await activateWorkflowRelease(props.workflowId, releaseId, overwrite, true)
    if (!data.data) {
      return
    }
    emit('activated', data.data)
    ElMessage.success(`已切换到 v${data.data.version}，工作台运行将使用这一版`)
  } catch (error) {
    if (!overwrite && isConflict(error)) {
      activating.value = false
      try {
        await ElMessageBox.confirm(
          conflictMessage(error) || '画布上还有未发布的改动，确认后将覆盖为所选版本。',
          '切换版本',
          { confirmButtonText: '覆盖并切换', cancelButtonText: '取消', type: 'warning' }
        )
      } catch {
        return
      }
      await submitActivate(releaseId, true)
      return
    }
    const message = conflictMessage(error)
    if (message) {
      ElMessage.error(message)
    }
  } finally {
    activating.value = false
  }
}

/**
 * 409 表示草稿和当前发布不一致，需要用户确认后才能覆盖。
 *
 * @param error 请求失败对象
 * @return 是冲突时为 true
 */
function isConflict(error: unknown): boolean {
  const status = (error as { response?: { status?: number } })?.response?.status
  if (status === 409) {
    return true
  }
  return (error as { code?: number })?.code === 40901
}

/**
 * 从 409 响应或业务体里取出给确认框的文案。
 *
 * @param error 请求失败对象
 * @return 中文说明；没有时为空串
 */
function conflictMessage(error: unknown): string {
  const fromHttp = (error as { response?: { data?: { msg?: string } } })?.response?.data?.msg
  if (fromHttp) {
    return fromHttp
  }
  const msg = (error as { msg?: string })?.msg
  return msg || ''
}

/** 切到另一条工作流时清空旧列表，避免菜单里短暂显示上一条的版本 */
watch(
  () => props.workflowId,
  () => {
    releases.value = []
    loadError.value = ''
  }
)
</script>
