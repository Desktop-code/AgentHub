<template>
  <!--
    渲染兜底容器：正常时原样渲染对话区（不额外包 DOM，CSS 布局不受影响）；
    对话区自身渲染抛错时只替换这一块，左栏历史与右上角「执行明细」仍可用。
  -->
  <p v-if="failed" class="chat-render-fallback">
    对话区渲染出错，左侧会话列表已保留。请刷新页面，或切换会话后重试。
  </p>
  <slot v-else />
</template>

<script setup lang="ts">
/**
 * 对话区渲染兜底。所属域：runtime（前端工作台）。
 *
 * <p>为什么单独一层：工作台整页是「左历史 + 右对话」一棵渲染树，对话区一旦在渲染期抛错，
 * 整棵树都会被卸载，用户看到的就是整页空白。历史会话可能存在旧事件形状，
 * 投影层已做兜底；这里再保一层，最差也只是对话区换成提示，仍能切会话或刷新。
 *
 * <p>只吃渲染 / 更新 / 挂载阶段的异常：业务请求失败仍由各自 try/catch 与错误条展示，
 * 不会被这里静默吞掉。
 */
import { onErrorCaptured, ref } from 'vue'

/** 对话区是否已经渲染失败；置 true 后不再渲染插槽，避免同一异常反复触发 */
const failed = ref(false)

/**
 * 捕获子树渲染异常。
 *
 * @param error 捕获到的异常
 * @param info  Vue 给出的异常阶段说明
 * @return false 表示已处理、不再向上冒泡；其它阶段交回上层处理
 */
onErrorCaptured((error, _instance, info) => {
  if (info === 'render function' || info === 'component update' || info === 'mounted hook') {
    // 只记一句可排障的警告，不打正文/密钥
    console.warn('[工作台] 对话区渲染失败，已降级为提示', error)
    failed.value = true
    return false
  }
  return undefined
})
</script>

<style scoped>
/* 兜底提示：白卡内的一行灰字，不抢视觉；不新增页面级布局 */
.chat-render-fallback {
  margin: 24px auto;
  max-width: 460px;
  padding: 16px 18px;
  border: 1px solid #e6e8ef;
  border-radius: 10px;
  background: #fafbff;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.7;
}
</style>
