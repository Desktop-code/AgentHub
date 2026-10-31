/**
 * 「配置提示词」入口的可见性判定（纯函数，无 Vue 依赖）。所属域：agent（前端工具）。
 * <p>
 * 抽出来的原因：入口可见性此前散落在模板的多个 v-if 里（canBind / promptSupported / 用途白名单），
 * 任一条件写错或漏传都会表现为「配了模型却看不到入口」，很难从页面反推是哪一条挂了。
 * 现在集中到一处，并可被自测脚本直接覆盖。
 */

/**
 * 允许在「模型槽行」上开提示词入口的用途。
 * <p>
 * 与后端 {@code PromptPurpose} 对齐，但**排除 CHAT**：CHAT 的 L3 挂载点是
 * {@code agent_def}，入口在基本信息「系统提示词」区；模型槽里的 CHAT 行只是对话模型绑定，
 * 在这里再给一个入口会出现两套互相不生效的配置（双入口）。
 * VISION / ASR / TTS / GUARDRAIL 等非生成槽没有模板维度，同样不出现入口。
 */
export const PURPOSES_WITH_PROMPT_ENTRY: readonly string[] = ['ROUTER', 'REWRITE', 'SUMMARY', 'MEMORY']

/**
 * 该用途槽是否应展示「配置提示词」入口。
 * <p>
 * 注意：**不依赖「是否已绑定模型」**——未绑模板时也要能点开弹窗选模板；
 * 也不依赖 bind 权限（无权限时弹窗以只读态打开），避免入口时显时隐。
 *
 * @param purpose         用途码，如 REWRITE / SUMMARY
 * @param promptSupported 父级开关；仅显式传 false 才整体关闭
 * @return true 表示展示入口
 */
export function purposeSupportsPromptEntry(purpose: string, promptSupported?: boolean): boolean {
  if (promptSupported === false) {
    return false
  }
  return PURPOSES_WITH_PROMPT_ENTRY.includes(purpose)
}

/**
 * 槽内哪一行承载 L3 四列。
 * <p>
 * 约定只有主模型（降级链第一条，slotRank/priority 最小的那行）承载提示词，
 * 降级行只做流量兜底、沿用自身配置，因此入口只出现在第 0 行。
 *
 * @param index      槽内排序后的下标（0 = 主模型）
 * @param purpose    用途码
 * @param supported  父级开关
 * @return true 表示该行展示入口
 */
export function shouldShowPromptEntry(index: number, purpose: string, supported?: boolean): boolean {
  return index === 0 && purposeSupportsPromptEntry(purpose, supported)
}
