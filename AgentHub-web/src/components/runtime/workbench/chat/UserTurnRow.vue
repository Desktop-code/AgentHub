<template>
  <!--
    用户气泡行：附件条 + 纯文本气泡 + 登录用户头像。所属域：runtime（前端展示）。
    纯展示，不发事件；附件顺序由调用方传入的列表决定，组件内不重排。
  -->
  <div class="speak-row is-user">
    <div class="user-stack">
      <!-- 附件在文字气泡外面。没打字时不画气泡，避免空的紫色框。 -->
      <UserAttachmentStrip v-if="attachments.length" :items="attachments" />
      <div v-if="turn.text" class="bubble">
        <div class="bubble-text">{{ turn.text }}</div>
      </div>
    </div>
    <div
      class="msg-avatar is-user"
      :class="{ 'has-photo': !!avatarSrc }"
      :style="avatarStyle"
      :title="displayName"
    >
      <img v-if="avatarSrc" :src="avatarSrc" alt="" />
      <span v-else>{{ initial }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 用户气泡行。所属域：runtime（前端展示）。
 *
 * <p>职责：只画「这一条用户消息」的附件、正文与头像；不碰投影、流式与跟滚。
 * 头像与昵称读登录用户 store，与拆前一致。无 emits。
 */
import { computed } from 'vue'
import UserAttachmentStrip from '@/components/runtime/UserAttachmentStrip.vue'
import type { UserAttachmentView } from '@/components/runtime/userMessageImages'
import type { ChatTurn } from '@/types/runtime'
import { USER_AVATAR_FALLBACK_COLOR, initialOf, resolveAvatarUrl } from '@/constants/agentLooks'
import { useUserStore } from '@/stores/user'

defineProps<{
  /** 这一条用户消息（只读 text / 其他字段不在这里消费） */
  turn: ChatTurn
  /** 按添加顺序的附件；由壳层用 attachmentsOfTurn 还原后传入 */
  attachments: UserAttachmentView[]
}>()

const userStore = useUserStore()

/** 当前登录用户展示名；缺资料时退回「用户」 */
const displayName = computed(
  () => userStore.profile?.nickname || userStore.profile?.username || '用户'
)
/** 无头像时的首字兜底 */
const initial = computed(() => initialOf(displayName.value))
/** 头像地址；为空时走首字方座 */
const avatarSrc = computed(() => resolveAvatarUrl(userStore.profile?.avatarUrl))
/** 首字兜底底色；有照片时不覆盖图片 */
const avatarStyle = computed(() =>
  avatarSrc.value
    ? undefined
    : { background: USER_AVATAR_FALLBACK_COLOR, color: '#fff' }
)
</script>

<style scoped src="../../../../styles/workbench-chat.css"></style>

<style scoped>
/* 用户行布局：与壳层助手行同形，内容靠右。scoped 样式不会继承到子组件，这里保留一份。 */
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

.bubble {
  /* 宽度跟文字走，上限是外层 user-stack 的 78%。再套一层百分比会把「差旅」收成一个字宽。 */
  width: fit-content;
  max-width: 100%;
  padding: 10px 12px;
  border-radius: 12px;
  background: #efeefe;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: normal;
  overflow-wrap: break-word;
}

/* 头像基类（圆形 / 36px / 照片铺满）由 `styles/workbench-chat.css` 提供；
   用户头像与原壳层同形（不再额外圆角 / 描边），照片与首字兜底都沿用基类，无需再补规则 */
</style>
