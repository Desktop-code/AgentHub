/**
 * 工作台可点选项的展示行。所属域：runtime（前端展示）。
 *
 * <p>职责：把后端给的 {@code {id,label}} 选项整理成渲染行（字母序号 + 去标记文案 + 推荐标记）。
 * 只做展示层归一，不改写 label；点击仍回传原始 option，续作入参与改前一致。
 *
 * <p>为什么单独一个纯模块：单专家卡、工作流询问节点、工作流旧卡底都画同一套选项，
 * 收在一处后共用组件能直接复用，也避免三处各写一遍字母 / 推荐标记规则。
 */
import type { ParticipantChoiceOption } from '@/components/runtime/workbench/workbenchParticipantFeed'

/**
 * 选项在列表中的字母序号（0 → A）。
 *
 * @param index 选项位置（0 起）
 * @return 单字母序号
 */
export function choiceLetter(index: number): string {
  return String.fromCharCode(65 + index)
}

/**
 * 文案尾部的推荐标记：只认「括号包裹」或「分隔符引导」的显式写法，
 * 不做关键词猜测——没有这类标记就绝不产出推荐标签。
 *
 * <p>示例（命中）：`方案A（推荐）`、`方案A (推荐)`、`方案A【推荐】`、`方案A - 推荐`。
 * 之所以限定形态：`推荐使用 Vue3` 这类句子里的「推荐」是正文语义，删掉会改变选项含义。
 */
const CHOICE_RECOMMEND_TRAIL =
  /(?:\s*[（(【\[]\s*(?:推荐|建议|首选|recommend(?:ed)?)\s*[）)】\]]|\s*[-·—|｜]\s*(?:推荐|建议|首选|recommend(?:ed)?))\s*$/i

/**
 * 文案开头的推荐标记：括号包裹，或「推荐：」这类冒号前缀。
 *
 * <p>同样只做展示层归一，不改写后端 label，也不影响续作时写进用户气泡的内容。
 */
const CHOICE_RECOMMEND_LEAD =
  /^\s*(?:[（(【\[]\s*(?:推荐|建议|首选|recommend(?:ed)?)\s*[）)】\]]|(?:推荐|建议|首选)\s*[:：])\s*/i

/**
 * 把选项文案拆成「展示文案 + 是否推荐」。
 *
 * <p>只影响渲染：点击仍回传后端给的原始 label（用户气泡与续作入参都保持改前口径）。
 * 文案里没有显式标记时 recommended 为 false；前端不猜哪一项是推荐。
 *
 * @param label 服务端给的选项展示文案
 * @return 去掉标记后的展示文案，以及是否带推荐标记
 */
export function choiceLabelOf(label: string): { text: string; recommended: boolean } {
  const raw = (label || '').trim()
  if (!raw) {
    return { text: '', recommended: false }
  }
  let text = raw
  let recommended = false
  // 先摘尾部（最常见：`方案A（推荐）`），再摘开头（`推荐：方案A`）
  const trailing = text.match(CHOICE_RECOMMEND_TRAIL)
  if (trailing && trailing.index != null) {
    text = text.slice(0, trailing.index).trim()
    recommended = true
  }
  const leading = text.match(CHOICE_RECOMMEND_LEAD)
  if (leading) {
    text = text.slice(leading[0].length).trim()
    recommended = true
  }
  // 标记单独成句（如整条文案只有「（推荐）」）时保留原文，避免出现空白行
  return { text: text || raw, recommended }
}

/**
 * 选项展示行：字母序号 + 展示文案 + 推荐标记。
 *
 * <p>`option` 保留原始对象，点击时原样交给调用方，保证发送内容与改前一致。
 *
 * @param options 后端给出的选项列表
 * @return 渲染用的行视图
 */
export function choiceRowsOf(options: ParticipantChoiceOption[]) {
  return options.map((option, index) => ({
    option,
    letter: choiceLetter(index),
    ...choiceLabelOf(option.label)
  }))
}
