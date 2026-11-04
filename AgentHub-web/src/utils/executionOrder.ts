/**
 * 执行序排序。所属域：obs（trace 步骤）+ workflow（run 节点时间轴）。
 *
 * <p>为什么单独抽出来：库列 `start_time` 是 DATETIME（秒精度），同一秒落库时
 * 「开始 → Agent → 结束」会退化成按主键（= 画布数组顺序）排，END 可能被插到中间。
 * trace 步骤有 `step_no`（引擎按真实执行顺序发的展示号）与 `seq`（写入序），优先用它们；
 * `workflow_node_run` 两者都没有，只能退化为 start_time → end_time → id。
 *
 * <p>硬约束：禁止按 span_type / nodeType / 状态 / 名称排序——这些都不代表执行先后。
 */
import type { ObsSpan } from '@/types/obs'

/** 工作流节点时间轴排序所需的最小字段（与 WorkflowNodeRunRow 结构兼容） */
export interface ExecutionOrderRow {
  id: number
  startTime?: string | null
  endTime?: string | null
}

/**
 * step_no 自然序：按点分层级逐段比较数字，避免字典序把 "10" 排到 "2" 前。
 * 非数字段退回字符串比较；前缀更短的排前（3 < 3.1）。
 *
 * @param a 展示号，如 3 / 3.1 / 3.1.1
 * @param b 展示号
 * @return 负数表示 a 在前
 */
export function compareStepNoNatural(a: string, b: string): number {
  const left = a.split('.')
  const right = b.split('.')
  const len = Math.max(left.length, right.length)
  for (let i = 0; i < len; i += 1) {
    const l = left[i]
    const r = right[i]
    // 前缀更短的排前：父步 3 在子步 3.1 之前
    if (l === undefined) {
      return -1
    }
    if (r === undefined) {
      return 1
    }
    const lNumeric = /^\d+$/.test(l)
    const rNumeric = /^\d+$/.test(r)
    if (lNumeric && rNumeric) {
      const diff = Number(l) - Number(r)
      if (diff !== 0) {
        return diff
      }
      continue
    }
    if (l !== r) {
      return l < r ? -1 : 1
    }
  }
  return 0
}

/**
 * trace 步骤执行序：step_no 升序 → 同号按 seq → id 兜底。
 *
 * <p>step_no 缺失（历史数据）时直接按 seq，仍保持写入序。
 *
 * @param a 步骤
 * @param b 步骤
 * @return 负数表示 a 在前
 */
export function compareSpanExecOrder(
  a: Pick<ObsSpan, 'stepNo' | 'seq' | 'id'>,
  b: Pick<ObsSpan, 'stepNo' | 'seq' | 'id'>
): number {
  const stepA = a.stepNo?.trim() ?? ''
  const stepB = b.stepNo?.trim() ?? ''
  if (stepA && stepB) {
    const byStep = compareStepNoNatural(stepA, stepB)
    if (byStep !== 0) {
      return byStep
    }
  }
  if (a.seq !== b.seq) {
    return a.seq - b.seq
  }
  if (stepA !== stepB) {
    return stepA < stepB ? -1 : 1
  }
  return a.id - b.id
}

/**
 * 工作流节点执行序：start_time 升序 → end_time 升序 → id。
 *
 * <p>为什么要 end_time 次键：同秒开工时，已先结束的步必定在仍运行 / 更晚结束的步之前；
 * 这样「开始」先于同秒开工的「Agent」，而「结束」排在二者之后。
 * 未开工（PENDING / SKIPPED，start_time 为空）统一排最后，并保持 id 相对序。
 *
 * @param a 节点运行行
 * @param b 节点运行行
 * @return 负数表示 a 在前
 */
export function compareNodeRunExecOrder(a: ExecutionOrderRow, b: ExecutionOrderRow): number {
  const startA = parseTime(a.startTime)
  const startB = parseTime(b.startTime)
  if (startA == null && startB == null) {
    return a.id - b.id
  }
  if (startA == null) {
    return 1
  }
  if (startB == null) {
    return -1
  }
  if (startA !== startB) {
    return startA - startB
  }
  const endA = parseTime(a.endTime)
  const endB = parseTime(b.endTime)
  if (endA != null && endB != null && endA !== endB) {
    return endA - endB
  }
  // 同秒开工又同秒结束（快跑的纯 START / END）：只能按主键保持稳定序
  return a.id - b.id
}

/**
 * 解析后端时间串。
 *
 * @param raw yyyy-MM-dd HH:mm:ss[.SSS] 或 ISO
 * @return epoch 毫秒；空值 / 非法值为 null
 */
function parseTime(raw?: string | null): number | null {
  if (!raw) {
    return null
  }
  // 后端多为 yyyy-MM-dd HH:mm:ss[.SSS]，Date.parse 需 T
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T')
  const ms = Date.parse(normalized)
  return Number.isNaN(ms) ? null : ms
}
