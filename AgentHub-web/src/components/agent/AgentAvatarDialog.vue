<template>
  <el-dialog
    v-model="visible"
    title="修改形象与颜色"
    width="568px"
    append-to-body
    destroy-on-close
    class="agent-avatar-dialog"
    @closed="onClosed"
  >
    <!-- 实时预览：直接用 AgentAvatar，和详情行 / 工作台气泡同一套渲染口径 -->
    <section class="preview-card">
      <AgentAvatar :name="name" :config="previewConfig" :size="64" />
      <div class="preview-meta">
        <strong>{{ (name || '').trim() || '未命名 Agent' }}</strong>
        <span class="preview-hint">
          {{ draft.type === 'custom' ? '当前使用自定义上传图' : '当前使用预设形象 + 色板' }}
        </span>
      </div>
      <el-button link type="primary" :disabled="uploading" @click="resetAll">恢复默认</el-button>
    </section>

    <!-- 预设形象：8 个平台插画，点选切换；再点一次取消图标只留底色 -->
    <section class="block">
      <h4>选择预设形象</h4>
      <div class="look-grid">
        <button
          v-for="item in AGENT_LOOK_OPTIONS"
          :key="item.key"
          type="button"
          class="look-cell"
          :class="{ active: draft.type === 'preset' && draft.icon === item.key }"
          :disabled="uploading"
          @click="pickIcon(item.key)"
        >
          <!-- 缩略图直接用 AgentAvatar：与列表 / 详情同一套渲染口径（白底方座 + 线稿） -->
          <span class="look-chip">
            <AgentAvatar :name="item.label" :config="{ type: 'preset', icon: item.key, color: item.color }" :size="32" />
          </span>
          <span>{{ item.label }}</span>
        </button>
      </div>
    </section>

    <!-- 固定色板：不允许任意色值，选色会切回预设模式 -->
    <section class="block">
      <h4>选择形象颜色</h4>
      <div class="color-row">
        <button
          v-for="color in AGENT_COLOR_OPTIONS"
          :key="color"
          type="button"
          class="color-dot"
          :class="{ active: draft.type === 'preset' && (draft.color || '').toUpperCase() === color.toUpperCase() }"
          :style="{ background: color }"
          :title="color"
          :disabled="uploading"
          @click="pickColor(color)"
        />
      </div>
      <p class="block-hint">固定色板，不支持自定义色值；未选时用主题紫。</p>
    </section>

    <!-- 自定义上传：与用户头像分开存储，URL 前缀 /uploads/agent-avatars/ -->
    <section class="block">
      <h4>自定义上传</h4>
      <div class="upload-row">
        <el-upload
          :auto-upload="false"
          :show-file-list="false"
          accept="image/jpeg,image/png,image/webp"
          :on-change="onFileChange"
          :disabled="uploading || !canEdit"
        >
          <el-button :loading="uploading" :disabled="!canEdit">选择图片</el-button>
        </el-upload>
        <span class="block-hint">jpg / png / webp，≤ 2MB；上传后点「确定」才生效。</span>
      </div>
    </section>

    <template #footer>
      <el-button :disabled="uploading" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="uploading" :disabled="!canEdit" @click="confirm">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
/**
 * Agent「配置形象」弹窗。所属域：agent（前端）。
 * <p>
 * 弹窗内改动只落在本地草稿，点「确定」才 emit 回主表单（由主表单即时落库）；
 * 「取消」直接丢弃，不污染 isDirty。实时预览与最终展示共用 AgentAvatar。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import AgentAvatar from '@/components/agent/AgentAvatar.vue'
import {
  AGENT_COLOR_OPTIONS,
  AGENT_LOOK_OPTIONS
} from '@/constants/agentLooks'
import { uploadAgentAvatar } from '@/api/agent'
import type { AvatarConfig } from '@/types/agent'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 预览用的 Agent 名称；首字兜底 */
    name?: string
    /** 当前配置；null=未配置 */
    config?: AvatarConfig | null
    /** 无编辑权限时隐藏上传与确定 */
    canEdit?: boolean
  }>(),
  { name: '', config: null, canEdit: true }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 确定回写；null 表示恢复默认（未配置） */
  (e: 'confirm', value: AvatarConfig | null): void
}>()

/** 弹窗显隐：对外用 v-model，内部改完再同步出去 */
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

/** 本地草稿；type 为 custom 时只看 url，为 preset 时看 icon/color */
const draft = reactive({ type: 'preset', icon: '', color: '', url: '' })
/** 上传中：禁用确定，避免把空 URL 写进配置 */
const uploading = ref(false)

/** 打开时按入参重置草稿：parent 的脏值不串到下一次编辑 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      return
    }
    const cfg = props.config
    if (cfg && cfg.type === 'custom' && cfg.url) {
      draft.type = 'custom'
      draft.url = cfg.url
      draft.icon = ''
      draft.color = ''
      return
    }
    draft.type = 'preset'
    draft.url = ''
    draft.icon = cfg?.icon || ''
    draft.color = cfg?.color || ''
  }
)

/** 预览配置：custom 且有 url 才算自定义；preset 无图无色返回 null（首字 + 默认紫） */
const previewConfig = computed<AvatarConfig | null>(() => {
  if (draft.type === 'custom' && draft.url) {
    return { type: 'custom', url: draft.url }
  }
  if (!draft.icon && !draft.color) {
    return null
  }
  return { type: 'preset', icon: draft.icon || null, color: draft.color || null }
})

/**
 * 点选预设形象：同键再点取消图标（保留底色）；从自定义切换回预设时丢弃上传图。
 *
 * @param key 预置键
 */
function pickIcon(key: string) {
  draft.type = 'preset'
  draft.url = ''
  draft.icon = draft.icon === key ? '' : key
}

/**
 * 点选色板颜色。
 *
 * @param color 白名单色值
 */
function pickColor(color: string) {
  draft.type = 'preset'
  draft.url = ''
  draft.color = draft.color.toUpperCase() === color.toUpperCase() ? '' : color
}

/**
 * 选图 → 立即上传拿 URL（尚未落库）。失败保留原草稿。
 *
 * @param file Element Plus 的 UploadFile
 */
async function onFileChange(file: { raw?: File }) {
  const raw = file?.raw
  if (!raw) {
    return
  }
  if (raw.size > 2 * 1024 * 1024) {
    ElMessage.error('形象图片不能超过 2MB')
    return
  }
  uploading.value = true
  try {
    const res = await uploadAgentAvatar(raw)
    draft.type = 'custom'
    draft.url = res.data.data?.url || ''
    draft.icon = ''
    draft.color = ''
    if (!draft.url) {
      ElMessage.error('上传失败，请重试')
    }
  } catch {
    ElMessage.error('上传失败，请检查图片类型与大小')
  } finally {
    uploading.value = false
  }
}

/** 恢复默认：清空所有选择，确定后回到「首字 + 默认紫」 */
function resetAll() {
  draft.type = 'preset'
  draft.icon = ''
  draft.color = ''
  draft.url = ''
}

/** 确定：把草稿收敛成接口结构回写主表单，由主表单统一保存 */
function confirm() {
  if (draft.type === 'custom') {
    if (!draft.url) {
      ElMessage.error('请先上传图片')
      return
    }
    emit('confirm', { type: 'custom', url: draft.url })
    visible.value = false
    return
  }
  if (!draft.icon && !draft.color) {
    emit('confirm', null)
    visible.value = false
    return
  }
  emit('confirm', {
    type: 'preset',
    icon: draft.icon || null,
    color: draft.color || null
  })
  visible.value = false
}

/** 关闭后清掉上传态，避免下次打开残留 loading */
function onClosed() {
  uploading.value = false
}
</script>

<style scoped>
.preview-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid #ebe9f5;
  border-radius: 12px;
  background: #faf9ff;
}

.preview-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

.preview-meta strong {
  font-size: 15px;
  color: #1f1b33;
}

.preview-hint {
  font-size: 12px;
  color: #8a86a3;
}

.block {
  margin-top: 18px;
}

.block h4 {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: #3d3759;
}

.block-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #8a86a3;
}

.look-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 8px;
}

.look-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px 2px 6px;
  border: 1px solid #eceaf5;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

/* 缩略图外包一层：真实头像自带白底与描边，这里只负责定位 */
.look-chip {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
}

.look-cell span {
  font-size: 11px;
  color: #6b6783;
}

.look-cell:hover {
  border-color: #c9c2f5;
}

.look-cell.active {
  border-color: #635bff;
  box-shadow: 0 0 0 2px rgba(99, 91, 255, 0.12);
}

.color-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.color-dot {
  width: 24px;
  height: 24px;
  border: 2px solid #fff;
  border-radius: 50%;
  cursor: pointer;
  box-shadow: 0 0 0 1px #e2dff0;
  transition: transform 0.12s, box-shadow 0.12s;
}

.color-dot:hover {
  transform: scale(1.08);
}

.color-dot.active {
  box-shadow: 0 0 0 2px #635bff;
}

.upload-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.upload-row .block-hint {
  margin: 0;
}
</style>
