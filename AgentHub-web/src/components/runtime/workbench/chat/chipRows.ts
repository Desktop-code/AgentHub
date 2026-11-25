/**
 * 工作台可点选项的展示行。所属域：runtime（前端展示）。
 *
 * <p>职责：把后端给的 {@code {id,label}} 选项整理成渲染行（字母序号 + 去标记文案 + 推荐标记），
 * 并在模型选项之后固定追加平台自己的「其它 / 自填」行。
 * 只做展示层归一，不改写 label；点击普通项仍回传原始 option，续作入参与改前一致。
 *
 * <p>为什么单独一个纯模块：单专家卡、工作流询问节点、工作流旧卡底都画同一套选项，
 * 收在一处后共用组件能直接复用，也避免三处各写一遍字母 / 推荐标记 / 其它行规则。
 */
import type { ParticipantChoiceOption } from '@/components/runtime/workbench/workbenchParticipantFeed'
import { WORKBENCH_OTHER_CHOICE_LABEL, isOtherChoiceLabel } from './workbenchAnswerText.ts'

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
 * 一行渲染结果：模型选项行，或平台固定追加的「其它 / 自填」行。
 *
 * <p>`other=true` 的行没有 {@link option}，因此点击路径不可能把它当成答复提交；
 * 普通行保留原始 option，点击仍按改前口径回传。
 */
export interface ChoiceRenderRow {
  /** 渲染 key：模型项按 id，平台自填行固定一个常量 */
  key: string
  /** 字母序号 */
  letter: string
  /** 展示文案（普通行已摘掉推荐标记） */
  text: string
  /** 文案是否自带显式推荐标记 */
  recommended: boolean
  /** 是否是平台追加的「其它 / 自填」行 */
  other: boolean
  /** 模型给的原始选项；平台自填行没有 */
  option?: ParticipantChoiceOption
}

/**
 * 选项展示行：字母序号 + 展示文案 + 推荐标记，末尾按需追加平台自填行。
 *
 * <p>规则：
 * <ul>
 *   <li>模型自带的「其它 / 其他 / 自定义」等同义项会被过滤掉，保证页面上只有一行「其它」；</li>
 *   <li>只要模型给了选项，就在末尾追加一行平台自填行；没有选项（纯回执 / 空列表）时不追加；</li>
 *   <li>平台行不写进 {@code choice.options}，也没有 option 可回传，点击只能发 `other` 事件。</li>
 * </ul>
 *
 * @param options 后端给出的选项列表
 * @return 渲染用的行视图；平台自填行恒在最后
 */
export function choiceRowsOf(options: ParticipantChoiceOption[]): ChoiceRenderRow[] {
  const source = Array.isArray(options) ? options : []
  // 先按展示口径去重：模型若仍自带其它项，不能与平台行同时出现两行
  const kept = source.filter((option) => !!option && !isOtherChoiceLabel(option.label))
  const rows: ChoiceRenderRow[] = kept.map((option, index) => ({
    key: `option:${option.id || index}`,
    letter: choiceLetter(index),
    ...choiceLabelOf(option.label),
    other: false,
    option
  }))
  if (!source.length) {
    return rows
  }
  // 平台自填行：只进入输入，不提交任何文案；字母接在模型选项之后
  rows.push({
    key: 'platform:other',
    letter: choiceLetter(rows.length),
    text: WORKBENCH_OTHER_CHOICE_LABEL,
    recommended: false,
    other: true
  })
  return rows
}
