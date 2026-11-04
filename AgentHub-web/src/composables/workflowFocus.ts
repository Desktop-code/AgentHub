/**
 * 工作流画布「专注模式」的跨组件开关。所属域：前端壳（composables 公共层）。
 * <p>调用方：工作流编辑页进入 / 退出专注；AdminLayout 监听并隐藏左侧主导航与页签条。
 * <p>约束：全局单例（模块级 ref），同一时刻只允许一个编辑页处于专注；
 * 编辑页被 KeepAlive 切走或销毁时必须 clear，避免其它页面残留「无侧栏」状态。
 */
import { readonly, ref } from 'vue'

/** 是否有工作流编辑页处于专注模式 */
const focusActive = ref(false)

/**
 * 获取专注模式状态与写入方法。
 *
 * @return focusActive 只读状态（壳层模板使用）；setFocus 进入 / 退出；clearFocus 离开页面时兜底复位
 */
export function useWorkflowFocus() {
  return {
    focusActive: readonly(focusActive),
    /** 进入（true）或退出（false）专注模式 */
    setFocus(value: boolean) {
      focusActive.value = value
    },
    /** 编辑页销毁 / 失活时强制复位，避免污染其它页面布局 */
    clearFocus() {
      focusActive.value = false
    }
  }
}
