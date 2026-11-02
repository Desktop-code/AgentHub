/**
 * 产物下载卡片。所属域：runtime（试跑对话区）。
 *
 * 只负责展示与触发下载，不自己请求接口：下载动作由 api/runtime 的
 * downloadWorkspaceFile 完成（必须带 Bearer 头，window.open 会被 Sa-Token 拦成 401）。
 *
 * 为什么单独成组件：计划执行模式与 REACT / ROUTER 两套模板都要渲染产物，
 * 复用一份才不会出现「一边能下载、另一边样式不一致」。
 */
<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { downloadWorkspaceFile } from '@/api/runtime'
import { fileNameOf, formatFileSize } from '@/components/runtime/trialMessages'
import type { TrialFileView } from '@/types/runtime'

const props = defineProps<{
  /** 产物视图：fileName / size / downloadUrl */
  file: TrialFileView
}>()

/** 单个卡片的下载中状态：避免连点触发多次请求 */
const downloading = ref(false)

/**
 * 触发下载。
 *
 * 用 fetch + blob 而不是 window.open：后端只认 Authorization 头，
 * 新开标签的导航带不上它。失败时把服务端 msg 抛给调用方展示。
 */
async function onDownload() {
  if (downloading.value || !props.file.downloadUrl) {
    return
  }
  downloading.value = true
  try {
    await downloadWorkspaceFile(props.file.downloadUrl, fileNameOf(props.file.fileName))
  } catch (error) {
    // 这里只做提示，不改变卡片状态：下载失败往往是临时的（文件被清理等）
    ElMessage.error(error instanceof Error ? error.message : '下载失败')
  } finally {
    downloading.value = false
  }
}
</script>

<template>
  <div class="file-card">
    <div class="file-icon" aria-hidden="true">📄</div>
    <div class="file-meta">
      <!-- 标题只显示末段文件名；完整相对路径放 title，含子目录时可悬停查看 -->
      <div class="file-name" :title="file.fileName">{{ fileNameOf(file.fileName) }}</div>
      <div class="file-size">{{ formatFileSize(file.size) }}</div>
    </div>
    <button
      type="button"
      class="file-download"
      :disabled="downloading || !file.downloadUrl"
      :aria-label="`下载 ${fileNameOf(file.fileName)}`"
      @click.stop.prevent="onDownload"
    >
      {{ downloading ? '下载中' : '下载' }}
    </button>
  </div>
</template>

<style scoped>
/* 白底细框卡片：与助手卡片区分开，读起来像「附件」而不是又一段正文 */
.file-card {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid #e6e8ef;
  border-radius: 10px;
  background: #fafbff;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.file-card:hover {
  border-color: #cdc6ff;
  box-shadow: 0 1px 6px rgba(99, 91, 255, 0.08);
}

.file-icon {
  flex: none;
  font-size: 22px;
  line-height: 1;
}

.file-meta {
  flex: 1;
  min-width: 0;
}

.file-name {
  overflow: hidden;
  color: var(--ah-text, #1f2430);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  margin-top: 2px;
  color: var(--ah-text-secondary, #8b90a0);
  font-size: 12px;
}

/* 蓝色下载按钮：与主色紫区分，强调「这是个动作」，不是普通文字链 */
.file-download {
  flex: none;
  padding: 5px 14px;
  border: 1px solid #409eff;
  border-radius: 6px;
  background: #fff;
  color: #409eff;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.file-download:hover:not(:disabled) {
  background: #409eff;
  color: #fff;
}

.file-download:disabled {
  border-color: #c8d9f0;
  color: #a8bdd8;
  cursor: not-allowed;
}
</style>
