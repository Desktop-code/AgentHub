/**
 * 执行明细悬浮框的顶栏按钮、开关与锚点规则（node:test，无浏览器）。所属域：前端 runtime。
 *
 * 覆盖验收点：
 * 1) 浮层形态：顶栏用 X 收口，不再出现「刷新」，两者不并排；
 * 2) 内嵌形态（试跑面板 / 详细甘特图抽屉）：仍是「刷新」，没有 X；
 * 3) X / Esc / 切会话 都收敛到「关」，点按钮收敛到「开」；
 * 4) 锚点：浮层贴着按钮向左展开（右边缘对齐、顶部接按钮下沿），宽度明显收窄；
 * 5) 浮层定位样式**不含**铺满主区的取值（inset / left / width:100%），防止退回整页遮罩。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/inspect-overlay.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  INSPECT_ANCHOR_RIGHT,
  INSPECT_ANCHOR_TOP,
  INSPECT_BUTTON_HEIGHT,
  INSPECT_COMPACT_MAX_WIDTH,
  INSPECT_DONE_HOLD_MS,
  INSPECT_EXIT_MS,
  INSPECT_MODE_COMPACT,
  INSPECT_MODE_FULL,
  INSPECT_POPOVER_GAP,
  INSPECT_POPOVER_MAX_WIDTH,
  INSPECT_STATE_DONE,
  INSPECT_STATE_FAILED,
  INSPECT_STATE_FLOWING,
  INSPECT_STATE_GENERATING,
  INSPECT_STATE_RETRIEVING,
  inspectButtonStyle,
  inspectCardMode,
  inspectCompactMeta,
  inspectCompactTitle,
  inspectHeadActions,
  inspectLiveTasks,
  inspectParticipantsOf,
  inspectPopoverStyle,
  inspectPopoverWidth,
  inspectRunningStateOf,
  nextInspectOpen,
  shouldCloseAfterSettle,
  shouldDropDoneTask
} from '../src/components/runtime/workbench/inspectOverlay.ts'

test('浮层形态：顶栏出 X，不出刷新；完整链路按 traceId 决定', () => {
  const actions = inspectHeadActions({ closable: true, hasTraceId: true })

  assert.equal(actions.showClose, true)
  assert.equal(actions.showRefresh, false, '浮层里刷新让位给 X，不允许并排')
  assert.equal(actions.showTraceLink, true)
})

test('浮层形态且没有 traceId：X 仍在，完整链路不出现', () => {
  const actions = inspectHeadActions({ closable: true, hasTraceId: false })

  assert.equal(actions.showClose, true)
  assert.equal(actions.showRefresh, false)
  assert.equal(actions.showTraceLink, false)
})

test('内嵌形态：仍是刷新，没有 X（试跑面板与详细甘特图抽屉口径不变）', () => {
  const withTrace = inspectHeadActions({ closable: false, hasTraceId: true })
  assert.equal(withTrace.showRefresh, true)
  assert.equal(withTrace.showClose, false)
  assert.equal(withTrace.showTraceLink, true)

  const noTrace = inspectHeadActions({ closable: false, hasTraceId: false })
  assert.equal(noTrace.showRefresh, true)
  assert.equal(noTrace.showClose, false)
  assert.equal(noTrace.showTraceLink, false)
})

test('开关：点按钮打开，X / Esc 关闭，切会话回落到关闭', () => {
  // 点右上角「执行明细」
  assert.equal(nextInspectOpen(false, 'open'), true)
  // 已打开时再点一次仍是开（幂等，不会因为重复点击反而收起来）
  assert.equal(nextInspectOpen(true, 'open'), true)

  // 两条关闭入口结果必须一致
  assert.equal(nextInspectOpen(true, 'close'), false)
  assert.equal(nextInspectOpen(false, 'close'), false)
  // 切会话：无论当前开没开，都收敛到关闭
  assert.equal(nextInspectOpen(true, 'reset'), false)
  assert.equal(nextInspectOpen(false, 'reset'), false)
})

test('关闭后回到「执行明细」按钮：关闭态就是未打开时的样子', () => {
  const opened = nextInspectOpen(false, 'open')
  const closed = nextInspectOpen(opened, 'close')
  assert.equal(closed, false)
  // 再次点按钮能重新打开，不需要额外的中间态
  assert.equal(nextInspectOpen(closed, 'open'), true)
})

test('宽度：明显窄于旧抽屉（620 / 680），且不窄到读不出泳道', () => {
  assert.ok(INSPECT_POPOVER_MAX_WIDTH <= 420, `上限应 ≤ 420，实际 ${INSPECT_POPOVER_MAX_WIDTH}`)
  assert.ok(INSPECT_POPOVER_MAX_WIDTH >= 360, `再窄会让行名与时间轨都读不出，实际 ${INSPECT_POPOVER_MAX_WIDTH}`)
  // 窄视口由 calc 兜住，不出现横向溢出
  const width = inspectPopoverWidth()
  assert.ok(width.includes(`${INSPECT_POPOVER_MAX_WIDTH}px`), width)
  assert.ok(width.includes('calc(100% -'), width)
})

test('锚点：浮层右边缘与按钮对齐（向左展开），顶部接按钮下沿', () => {
  const button = inspectButtonStyle()
  const popover = inspectPopoverStyle()

  // 同一套右对齐距离：右边缘对齐，浮层自然向左长出去
  assert.equal(button.right, `${INSPECT_ANCHOR_RIGHT}px`)
  assert.equal(popover.right, `${INSPECT_ANCHOR_RIGHT}px`)

  // 顶部 = 按钮顶部 + 按钮高度 + 间隙：贴着按钮下方，不重叠也不飘走
  const expectedTop = INSPECT_ANCHOR_TOP + INSPECT_BUTTON_HEIGHT + INSPECT_POPOVER_GAP
  assert.equal(popover.top, `${expectedTop}px`)
  assert.equal(button.top, `${INSPECT_ANCHOR_TOP}px`)
})

test('浮层定位不含铺满主区的取值：没有遮罩、没有居中、没有全宽', () => {
  const popover = inspectPopoverStyle()
  const keys = Object.keys(popover)

  // 只有这四项定位；出现 inset / left 就是想把浮层退化成覆盖层
  assert.deepEqual(keys.sort(), ['maxHeight', 'right', 'top', 'width'])
  assert.equal(popover.inset, undefined)
  assert.equal(popover.left, undefined)
  // 宽度必须带上限：全宽（100% / 100vw）会让浮层重新铺满主区
  const width = String(popover.width)
  assert.ok(!/^\s*100(%|vw)\s*$/.test(width), `宽度不得等于容器全宽：${width}`)
  assert.ok(width.includes(`${INSPECT_POPOVER_MAX_WIDTH}px`), `宽度必须带上限：${width}`)
  // maxHeight 留出底部边距，内容多时卡片自身滚动而不是撑破主区
  assert.ok(String(popover.maxHeight).startsWith('calc(100% - '), popover.maxHeight)
})

/* ===== 运行中紧凑卡：形态 / 标题 / 状态推导 ===== */

test('形态：运行中给紧凑卡，结束才给完整泳道', () => {
  assert.equal(inspectCardMode(true), INSPECT_MODE_COMPACT)
  assert.equal(inspectCardMode(false), INSPECT_MODE_FULL)
})

test('紧凑卡比完整泳道窄，且两者都不铺满容器', () => {
  assert.ok(
    INSPECT_COMPACT_MAX_WIDTH < INSPECT_POPOVER_MAX_WIDTH,
    `紧凑卡应更窄：${INSPECT_COMPACT_MAX_WIDTH} vs ${INSPECT_POPOVER_MAX_WIDTH}`
  )
  const compactStyle = inspectPopoverStyle(INSPECT_MODE_COMPACT)
  const fullStyle = inspectPopoverStyle(INSPECT_MODE_FULL)
  // 定位口径一致（贴同一按钮向左展开），只有宽度不同
  assert.equal(compactStyle.top, fullStyle.top)
  assert.equal(compactStyle.right, fullStyle.right)
  assert.ok(String(compactStyle.width).includes(`${INSPECT_COMPACT_MAX_WIDTH}px`), compactStyle.width)
})

test('紧凑卡标题：并行多任务写「并行执行中（N 个任务）」，结束不再占用运行中文案', () => {
  assert.equal(inspectCompactTitle(true, 3, true), '并行执行中（3 个任务）')
  assert.equal(inspectCompactTitle(true, 1, true), '执行中')
  assert.equal(inspectCompactTitle(true, 2, false), '执行中（2 个任务）')
  assert.equal(inspectCompactTitle(false, 3, true), '执行完成（3 个任务）')
  assert.equal(inspectCompactTitle(false, 0, true), '执行完成')
})

test('紧凑卡耗时：运行中说「已 x」，结束说「耗时 x」', () => {
  assert.equal(inspectCompactMeta(true, 12300), '已 12.3s')
  assert.equal(inspectCompactMeta(false, 12300), '耗时 12.3s')
  assert.equal(inspectCompactMeta(true, 0), '')
})

/** 造一个紧凑卡推导用的步骤 */
function spanLike(patch: Record<string, unknown>) {
  return {
    id: 1,
    parentId: null,
    spanType: 'AGENT',
    name: '专家',
    agentName: '专家',
    status: 'RUNNING',
    latencyMs: null,
    toolName: null,
    ...patch
  } as {
    id: number
    parentId: number | null
    spanType: string | null
    name: string | null
    agentName: string | null
    status: string | null
    latencyMs: number | null
    toolName?: string | null
  }
}

test('运行中状态：检索类工具在跑说「检索中」，模型在跑说「生成中」', () => {
  const agent = spanLike({ id: 1, agentName: '制度知识专家' })
  const llm = spanLike({ id: 2, parentId: 1, spanType: 'LLM', agentName: null })
  const tool = spanLike({
    id: 3,
    parentId: 2,
    spanType: 'TOOL',
    name: 'searchKnowledgeBase',
    toolName: 'searchKnowledgeBase',
    agentName: null
  })

  // 只有 LLM 在跑：生成中
  const generating = inspectRunningStateOf(agent, [agent, llm])
  assert.equal(generating.state, INSPECT_STATE_GENERATING)
  assert.equal(generating.done, false)

  // 检索类工具在跑：检索中（优先于「生成中」）
  const retrieving = inspectRunningStateOf(agent, [agent, llm, tool])
  assert.equal(retrieving.state, INSPECT_STATE_RETRIEVING)

  // 名字里没有检索线索的工具：流转中，不冒充「检索中」
  const plainTool = spanLike({ id: 4, parentId: 2, spanType: 'TOOL', name: 'writeFile', toolName: 'writeFile' })
  const flowing = inspectRunningStateOf(agent, [agent, llm, plainTool])
  assert.equal(flowing.state, INSPECT_STATE_FLOWING)
})

test('运行中状态：子树还没开出活跃步说「流转中」，结束时按终态收口', () => {
  const agent = spanLike({ id: 1, agentName: '报告撰写专家' })
  // 刚 begin、子树为空：不猜「思考中」，说流转中
  assert.equal(inspectRunningStateOf(agent, [agent]).state, INSPECT_STATE_FLOWING)

  // 已结束：done=true，状态改为已完成 / 失败，不再写运行中文案
  const ok = inspectRunningStateOf(spanLike({ id: 2, status: 'OK', agentName: '专家' }), [])
  assert.equal(ok.done, true)
  assert.equal(ok.state, INSPECT_STATE_DONE)

  const failed = inspectRunningStateOf(spanLike({ id: 3, status: 'ERROR', agentName: '专家' }), [])
  assert.equal(failed.done, true)
  assert.equal(failed.state, INSPECT_STATE_FAILED)
})

test('紧凑卡任务行：按参与者平铺，标题复用参与者名', () => {
  const agentA = spanLike({ id: 1, agentName: '制度知识专家' })
  const agentB = spanLike({ id: 2, agentName: '报告撰写专家' })
  const llmB = spanLike({ id: 3, parentId: 2, spanType: 'LLM', agentName: null })
  const tasks = inspectLiveTasks([agentA, agentB, llmB])

  assert.equal(tasks.length, 2)
  assert.equal(tasks[0].title, '制度知识专家')
  assert.equal(tasks[0].state, INSPECT_STATE_FLOWING)
  assert.equal(tasks[1].title, '报告撰写专家')
  assert.equal(tasks[1].state, INSPECT_STATE_GENERATING)
})

/* ===== 并行：容器不再遮蔽正在跑的专家（手点「只有一行智能网关」的回归） ===== */

/**
 * 造现网真实形状：入口方案根（智能网关，parent 为空）→ judge → LLM → 各专家。
 *
 * @param experts 专家名 + 该专家的活跃步类型
 * @return 步骤数组
 */
function gatewaySpans(experts: { name: string; active: 'retrieve' | 'llm' | 'none' | 'done' }[]) {
  const spans = [
    spanLike({ id: 1, agentName: '智能网关', seq: 1 }),
    spanLike({ id: 2, parentId: 1, spanType: 'ROUTE', name: 'judge', agentName: null, seq: 2 }),
    spanLike({ id: 3, parentId: 2, spanType: 'LLM', name: 'router-model', agentName: null, seq: 3 })
  ]
  let next = 4
  experts.forEach((expert) => {
    const agentId = next
    spans.push(spanLike({
      id: agentId,
      parentId: 3,
      agentName: expert.name,
      seq: agentId,
      status: expert.active === 'done' ? 'OK' : 'RUNNING'
    }))
    next += 1
    if (expert.active === 'retrieve') {
      spans.push(spanLike({
        id: next,
        parentId: agentId,
        spanType: 'TOOL',
        name: 'searchKnowledgeBase',
        toolName: 'searchKnowledgeBase',
        agentName: null,
        seq: next
      }))
      next += 1
    } else if (expert.active === 'llm') {
      spans.push(spanLike({ id: next, parentId: agentId, spanType: 'LLM', name: 'm', agentName: null, seq: next }))
      next += 1
    }
  })
  return spans
}

test('并行：入口网关不算参与者，两位在跑的专家同时列出（不再只有一行）', () => {
  const spans = gatewaySpans([
    { name: '制度知识专家', active: 'retrieve' },
    { name: '报告撰写专家', active: 'llm' }
  ])
  const tasks = inspectLiveTasks(spans)

  // 关键回归：以前这里只有「智能网关」一行
  assert.equal(tasks.length, 2, `实际 ${JSON.stringify(tasks.map((t) => t.title))}`)
  assert.deepEqual(tasks.map((task) => task.title), ['制度知识专家', '报告撰写专家'])
  assert.ok(!tasks.some((task) => task.title === '智能网关'), '网关是容器，不该占一行')
  // 状态各自推导，不共用容器子树的结果
  assert.equal(tasks[0].state, INSPECT_STATE_RETRIEVING)
  assert.equal(tasks[1].state, INSPECT_STATE_GENERATING)
})

test('并行：第三位刚开跑、还没开出活跃步时也立刻出现（流转中）', () => {
  const spans = gatewaySpans([
    { name: '制度知识专家', active: 'retrieve' },
    { name: '报告撰写专家', active: 'llm' },
    { name: '工作流 WF', active: 'none' }
  ])
  const tasks = inspectLiveTasks(spans)

  assert.equal(tasks.length, 3)
  assert.equal(tasks[2].title, '工作流 WF')
  assert.equal(tasks[2].state, INSPECT_STATE_FLOWING)
})

test('并行：已结束的专家留在列表并标已完成，在跑的不受影响', () => {
  const spans = gatewaySpans([
    { name: '制度知识专家', active: 'done' },
    { name: '报告撰写专家', active: 'llm' }
  ])
  const tasks = inspectLiveTasks(spans)

  assert.equal(tasks.length, 2)
  assert.equal(tasks[0].state, INSPECT_STATE_DONE)
  assert.equal(tasks[0].done, true)
  assert.equal(tasks[1].state, INSPECT_STATE_GENERATING)
  assert.equal(tasks[1].done, false)
})

test('并行：只有网关在跑、专家还没开时，保留网关一行占位（不空卡）', () => {
  const gateway = spanLike({ id: 1, agentName: '智能网关', seq: 1 })
  const judge = spanLike({ id: 2, parentId: 1, spanType: 'ROUTE', name: 'judge', agentName: null, seq: 2 })
  const tasks = inspectLiveTasks([gateway, judge])

  assert.equal(tasks.length, 1)
  assert.equal(tasks[0].title, '智能网关')
  assert.equal(tasks[0].done, false)
})

test('并行：容器内部已有专家在跑时，容器本身不再重复占一行', () => {
  const spans = gatewaySpans([{ name: '制度知识专家', active: 'llm' }])
  const tasks = inspectLiveTasks(spans)

  assert.equal(tasks.length, 1)
  assert.equal(tasks[0].title, '制度知识专家')
  assert.ok(!tasks.some((task) => task.title === '智能网关'))
})

/* ===== 收尾节奏：完成留观 2s 再滑出；整轮完成留观 2s 再关闭 ===== */

test('完成行：带结束时刻，运行中的行没有', () => {
  const running = inspectRunningStateOf(spanLike({ id: 1, agentName: '专家' }), [])
  assert.equal(running.doneAtMs, null, '在跑的行不该有结束时刻')

  const finished = inspectRunningStateOf(
    spanLike({ id: 2, agentName: '专家', status: 'OK', endTime: '2026-11-16 16:30:00' }),
    []
  )
  assert.equal(finished.done, true)
  assert.equal(finished.doneAtMs, Date.parse('2026-11-16T16:30:00'))
})

test('留观：完成行在 2s 内仍留在列表，期满才滑出', () => {
  const doneAt = Date.parse('2026-11-16T16:30:00')

  // 刚完成：留着（这一拍正是「绿底」可见的窗口）
  assert.equal(shouldDropDoneTask(doneAt, doneAt), false)
  // 1.9s：仍在留观期
  assert.equal(shouldDropDoneTask(doneAt, doneAt + 1900), false)
  // 满 2s：可以滑出
  assert.equal(shouldDropDoneTask(doneAt, doneAt + INSPECT_DONE_HOLD_MS), true)
  // 没有结束时刻（span 未带 end_time）：先不移除，交给调用方按首次看到 done 兜底
  assert.equal(shouldDropDoneTask(null, doneAt + 99999), false)
})

test('整轮收尾：留观 2s 后才关闭浮层，运行中永不自动关', () => {
  const settledAt = Date.parse('2026-11-16T16:30:00')

  // 仍在跑：无论过多久都不自动关
  assert.equal(shouldCloseAfterSettle(true, null, settledAt + 99999), false)
  // 刚收尾：留着让人看见最后的绿底
  assert.equal(shouldCloseAfterSettle(false, settledAt, settledAt), false)
  assert.equal(shouldCloseAfterSettle(false, settledAt, settledAt + 1900), false)
  // 满 2s：关闭
  assert.equal(shouldCloseAfterSettle(false, settledAt, settledAt + INSPECT_DONE_HOLD_MS), true)
  // 没有收尾时刻：不关（宁可留着，也不误关一张正在看的卡）
  assert.equal(shouldCloseAfterSettle(false, null, settledAt + 99999), false)
})

test('留观常量与滑出时长：2s 停留、0.18s 滑出', () => {
  assert.equal(INSPECT_DONE_HOLD_MS, 2000)
  assert.equal(INSPECT_EXIT_MS, 180)
  // 滑出必须短于留观，否则「绿底」会被动画吃掉一半
  assert.ok(INSPECT_EXIT_MS < INSPECT_DONE_HOLD_MS)
})
