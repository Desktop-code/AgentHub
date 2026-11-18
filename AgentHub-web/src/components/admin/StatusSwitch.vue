<template>
  <!-- 列表内启停。失败把开关拨回去；错误文案由请求拦截器弹出，这里不重复提示 -->
  <el-switch
    :model-value="shown"
    :active-value="1"
    :inactive-value="0"
    :disabled="disabled || busy"
    :loading="busy"
    @change="onChange"
  />
</template>

<script setup lang="ts">
/**
 * 系统管理列表启停开关。所属域：admin 前端共用。
 * 启用 = 1，停用 = 0。提交失败时回到点击前的值，刷新后以服务器返回为准。
 * 无编辑权限时由调用方 disabled，不在这里放宽权限。
 */
import { ref, watch } from 'vue'

const props = defineProps<{
  /** 当前状态，1 启用 / 正常，其它视为停用 */
  status: number
  /** 无权限或外部忙时禁用 */
  disabled?: boolean
  /**
   * 把新状态提交到服务器。失败必须 reject，成功后把行上的 status 写成服务器值。
   *
   * @param status 1 或 0
   */
  commit: (status: number) => Promise<void>
}>()

/** 开关上看到的值。请求未完成前可以先拨过去，失败再拨回 */
const shown = ref(props.status === 1 ? 1 : 0)
const busy = ref(false)

watch(
  () => props.status,
  (value) => {
    if (!busy.value) {
      shown.value = value === 1 ? 1 : 0
    }
  }
)

/**
 * 拨动开关。先显示目标值，提交失败则回到原值。
 *
 * @param value el-switch 的 change 值
 */
async function onChange(value: string | number | boolean) {
  const next = Number(value) === 1 ? 1 : 0
  const prev = shown.value
  if (next === prev || busy.value) {
    return
  }
  shown.value = next
  busy.value = true
  try {
    await props.commit(next)
    // commit 成功时应已把行 status 写成服务器值
    shown.value = props.status === 1 ? 1 : 0
  } catch {
    shown.value = prev
  } finally {
    busy.value = false
  }
}
</script>
