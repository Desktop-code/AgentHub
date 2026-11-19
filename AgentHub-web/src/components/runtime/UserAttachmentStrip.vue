<template>
  <!--
    用户附件条。所属域：runtime（展示）。
    首页、MA 运行、管理端 Agent 对话、待发预览共用这一条：
    图片用缩略图，其它类型用图标加文件名；默认露出 3 个，其余折叠。
    不放进文字气泡。没有文字时调用方不要再画空气泡。
  -->
  <div v-if="items.length" class="att-strip">
    <div class="att-list">
      <template v-for="(item, index) in shown" :key="itemKey(item, index)">
        <div v-if="item.kind === 'image'" class="att-thumb">
          <el-image
            v-if="item.url"
            :src="hrefOf(item)"
            :preview-src-list="imagePreview"
            :initial-index="imageIndex(item)"
            fit="cover"
            preview-teleported
            :alt="item.name || '图片'"
          >
            <template #error>
              <div class="att-fail">图片加载失败</div>
            </template>
          </el-image>
          <div v-else class="att-fail">图片加载失败</div>
          <button v-if="removable" type="button" class="att-remove" :disabled="disabled" @click="emit('remove', realIndex(index))">
            ×
          </button>
        </div>
        <component
          :is="item.url && !removable ? 'a' : 'div'"
          v-else
          class="att-file"
          :href="item.url && !removable ? hrefOf(item) : undefined"
          :target="item.url && !removable ? '_blank' : undefined"
          rel="noopener noreferrer"
          :download="item.name || undefined"
        >
          <span class="att-icon" :class="'is-' + fileKind(item.name)" aria-hidden="true">{{ fileMark(item.name) }}</span>
          <span class="att-name">{{ item.name || '未命名文件' }}</span>
          <button
            v-if="removable"
            type="button"
            class="att-remove"
            :disabled="disabled"
            :aria-label="`移除 ${item.name || '附件'}`"
            @click="emit('remove', realIndex(index))"
          >
            ×
          </button>
        </component>
      </template>
    </div>
    <button v-if="rest > 0" type="button" class="att-more" @click="expanded = true">还有 {{ rest }} 个</button>
    <button v-else-if="expanded && items.length > previewCount" type="button" class="att-more" @click="expanded = false">
      收起
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 用户附件条。所属域：runtime（展示）。
 *
 * <p>待发和已发送、三个对话入口都用这一份。折叠规则在 previewAttachments，不写在某一页的样式里。
 */
import { computed, ref } from 'vue'
import {
  ATTACHMENT_PREVIEW_COUNT,
  previewAttachments,
  resolveUserImageUrl,
  type UserAttachmentView
} from '@/components/runtime/userMessageImages'

const props = defineProps<{
  /** 按发送顺序的附件 */
  items: UserAttachmentView[]
  /** 待发列表可移除；已发送只打开或下载 */
  removable?: boolean
  /** 发送中不可移除 */
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 移除待发附件，下标是完整列表里的位置 */
  remove: [index: number]
}>()

/** 默认折叠，点「还有 N 个」再看完 */
const expanded = ref(false)
const previewCount = ATTACHMENT_PREVIEW_COUNT

const view = computed(() => previewAttachments(props.items || [], expanded.value))
const shown = computed(() => view.value.shown)
const rest = computed(() => view.value.rest)

const imagePreview = computed(() =>
  (props.items || [])
    .filter((item) => item.kind === 'image' && item.url)
    .map((item) => resolveUserImageUrl(item.url))
)

/**
 * 折叠后的下标要映回完整列表，移除才不会删错。
 *
 * @param index 当前露出列表中的位置
 * @return 完整列表中的位置
 */
function realIndex(index: number): number {
  const item = shown.value[index]
  const all = props.items || []
  const found = all.indexOf(item)
  return found >= 0 ? found : index
}

/**
 * @param item 附件
 * @return 可打开的地址
 */
function hrefOf(item: UserAttachmentView): string {
  return resolveUserImageUrl(item.url)
}

/**
 * @param item  附件
 * @return 它在全部图片里的序号，预览从这一张开始
 */
function imageIndex(item: UserAttachmentView): number {
  const images = (props.items || []).filter((one) => one.kind === 'image')
  const found = images.indexOf(item)
  return found >= 0 ? found : 0
}

/**
 * @param item  附件
 * @param index 露出列表中的位置
 * @return 稳定 key
 */
function itemKey(item: UserAttachmentView, index: number): string {
  return `${item.kind}-${item.name || ''}-${item.url || index}`
}

/**
 * @param name 文件名
 * @return 图标类别
 */
function fileKind(name?: string): string {
  const ext = extensionOf(name)
  if (ext === 'pdf') {
    return 'pdf'
  }
  if (ext === 'xls' || ext === 'xlsx' || ext === 'csv') {
    return 'sheet'
  }
  if (ext === 'doc' || ext === 'docx') {
    return 'doc'
  }
  if (ext === 'ppt' || ext === 'pptx') {
    return 'slide'
  }
  if (ext === 'zip') {
    return 'zip'
  }
  if (['webm', 'wav', 'mp3', 'm4a', 'ogg', 'opus'].includes(ext)) {
    return 'audio'
  }
  return 'file'
}

/**
 * @param name 文件名
 * @return 图标上的短标记
 */
function fileMark(name?: string): string {
  const kind = fileKind(name)
  if (kind === 'pdf') {
    return 'PDF'
  }
  if (kind === 'sheet') {
    return '表'
  }
  if (kind === 'doc') {
    return '文'
  }
  if (kind === 'slide') {
    return '幻'
  }
  if (kind === 'zip') {
    return '包'
  }
  if (kind === 'audio') {
    return '音'
  }
  return '件'
}

/**
 * @param name 文件名
 * @return 小写扩展名
 */
function extensionOf(name?: string): string {
  const value = (name || '').trim()
  const dot = value.lastIndexOf('.')
  if (dot < 0 || dot === value.length - 1) {
    return ''
  }
  return value.slice(dot + 1).toLowerCase()
}
</script>

<style scoped>
.att-strip {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.att-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.att-thumb {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 8px;
  overflow: hidden;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
}

.att-thumb :deep(.el-image) {
  width: 100%;
  height: 100%;
}

.att-thumb :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.att-file {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 240px;
  padding: 8px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #1f2937;
  text-decoration: none;
}

.att-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 6px;
  background: #eef2ff;
  color: #4338ca;
  font-size: 11px;
  font-weight: 700;
}

.att-icon.is-pdf {
  background: #fee2e2;
  color: #b91c1c;
}

.att-icon.is-sheet {
  background: #dcfce7;
  color: #15803d;
}

.att-icon.is-audio {
  background: #fef3c7;
  color: #b45309;
}

.att-name {
  overflow: hidden;
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.att-fail {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  padding: 4px;
  color: #b45309;
  font-size: 11px;
  text-align: center;
}

.att-remove {
  margin-left: auto;
  border: none;
  background: transparent;
  color: #6b7280;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}

.att-thumb .att-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgb(255 255 255 / 92%);
}

.att-more {
  border: none;
  background: transparent;
  color: #635bff;
  font-size: 12px;
  cursor: pointer;
}
</style>
