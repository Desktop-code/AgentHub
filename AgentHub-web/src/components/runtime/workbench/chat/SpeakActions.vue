<template>
  <!--
    回答朗读按钮组：播放 / 暂停 / 合成中 / 停止。所属域：runtime（前端展示）。
    只画按钮，不持有播放器；点击只把 key 或 stop 抛给壳层。
  -->
  <div class="speak-actions">
    <el-tooltip :content="hint" placement="top">
      <button
        type="button"
        class="speak-icon"
        :class="{ active: speaking }"
        :aria-label="hint"
        @click="emit('toggle', speakKey)"
      >
        <el-icon :class="{ 'is-loading': preparing }">
          <Loading v-if="preparing" />
          <VideoPause v-else-if="playing" />
          <VideoPlay v-else />
        </el-icon>
      </button>
    </el-tooltip>
    <el-tooltip v-if="speaking" content="停止朗读" placement="top">
      <button type="button" class="speak-icon stop" aria-label="停止朗读" @click="emit('stop')">
        <el-icon><Close /></el-icon>
      </button>
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">
/**
 * 朗读按钮组。所属域：runtime（前端展示）。
 *
 * <p>计划终答头与调试松块头共用同一份形态，保证两处永远长得一样。
 * 播放器（{@link useSpeechPlayer}）必须继续由壳层单实例持有；本组件不播放、不缓存音频，
 * 只按传入状态画图标，避免出现多条回答同时出声。
 */
import { Close, Loading, VideoPause, VideoPlay } from '@element-plus/icons-vue'

defineProps<{
  /** 回答键：点击时随 toggle 一起抛出，壳层据此定位要读哪一条 */
  speakKey: string
  /** 这一条是否已由播放器接管（播放 / 暂停 / 合成中） */
  speaking: boolean
  /** 是否还在等模型合成 */
  preparing: boolean
  /** 是否正在出声（暂停不算） */
  playing: boolean
  /** 图标按钮的 tooltip 与 aria-label 文案 */
  hint: string
}>()

const emit = defineEmits<{
  /** 点播放 / 暂停 / 继续；携带回答键 */
  toggle: [key: string]
  /** 点停止 */
  stop: []
}>()
</script>

<style scoped>
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
</style>
