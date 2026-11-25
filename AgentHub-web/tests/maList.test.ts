/**
 * 多智能体列表展示口径测试（node:test，无浏览器）。所属域：前端 agent。
 *
 * 覆盖：协作模式只剩两种；历史模板展示时收口；入口仍叫对话路由且不可新建。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/maList.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MA_ENTRY_ROUTER_DESCRIPTION,
  MA_ENTRY_ROUTER_NAME,
  MA_LIST_FILTER_TEMPLATES,
  MA_LIST_TEMPLATE_COMPETE,
  MA_LIST_TEMPLATE_CRITIC_LOOP,
  MA_LIST_TEMPLATE_ENTRY_ROUTER,
  MA_LIST_TEMPLATE_FIXED_PARALLEL,
  MA_LIST_TEMPLATE_FIXED_SEQUENCE,
  MA_LIST_TEMPLATE_ROUTER_PARALLEL,
  MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE,
  MA_LIST_TEMPLATES,
  MA_SERIAL_WORKFLOW_HINT,
  canonicalMaTemplate,
  isEntryRouterTemplate,
  maTemplateHint,
  maTemplateLabel,
  maTemplateTagType,
  normalizeMaListTemplate,
  routesByJudgeTemplate
} from '../src/utils/maList.ts'

/** 新建下拉只有两种协作模式，中文名不能再是旧的六种 */
test('writable templates are dispatch or serial', () => {
  assert.deepEqual([...MA_LIST_TEMPLATES], [
    MA_LIST_TEMPLATE_ROUTER_PARALLEL,
    MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE
  ])
  assert.equal(maTemplateLabel(MA_LIST_TEMPLATE_ROUTER_PARALLEL), '意图路由分发')
  assert.equal(maTemplateLabel(MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE), '路由后串行')
  const labels = MA_LIST_TEMPLATES.map((item) => maTemplateLabel(item))
  assert.equal(new Set(labels).size, MA_LIST_TEMPLATES.length)
})

/** 历史模板读出来收成两种模式，不丢成空白标签 */
test('legacy templates collapse onto the two modes', () => {
  assert.equal(canonicalMaTemplate(MA_LIST_TEMPLATE_FIXED_PARALLEL), MA_LIST_TEMPLATE_ROUTER_PARALLEL)
  assert.equal(canonicalMaTemplate(MA_LIST_TEMPLATE_COMPETE), MA_LIST_TEMPLATE_ROUTER_PARALLEL)
  assert.equal(canonicalMaTemplate(MA_LIST_TEMPLATE_CRITIC_LOOP), MA_LIST_TEMPLATE_ROUTER_PARALLEL)
  assert.equal(canonicalMaTemplate(MA_LIST_TEMPLATE_FIXED_SEQUENCE), MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE)
  assert.equal(maTemplateLabel(MA_LIST_TEMPLATE_FIXED_SEQUENCE), '路由后串行')
  assert.equal(maTemplateLabel(MA_LIST_TEMPLATE_FIXED_PARALLEL), '意图路由分发')
  assert.equal(maTemplateTagType(MA_LIST_TEMPLATE_FIXED_SEQUENCE), 'success')
  assert.equal(maTemplateTagType(MA_LIST_TEMPLATE_ROUTER_PARALLEL), 'primary')
})

/** 空值与非法值都收敛到意图路由分发 */
test('blank and unknown templates fall back to intent dispatch', () => {
  for (const raw of [undefined, null, '', 'SHARED_SCRATCH', 'CRITIC']) {
    assert.equal(normalizeMaListTemplate(raw), MA_LIST_TEMPLATE_ROUTER_PARALLEL)
    assert.equal(maTemplateLabel(raw), '意图路由分发')
    assert.equal(maTemplateTagType(raw), 'primary')
    assert.match(maTemplateHint(raw), /并行/)
  }
  assert.equal(normalizeMaListTemplate('ROUTER_THEN_SEQUENCE'), MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE)
  assert.equal(normalizeMaListTemplate('FIXED_SEQUENCE'), MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE)
})

/** 路由后串行的说明要提到先选出、再按顺序接力，并指向工作流 */
test('serial hint explains handoff and points complex flows to workflow', () => {
  const hint = maTemplateHint(MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE)
  assert.match(hint, /执行顺序/)
  assert.match(hint, /成文结论/)
  assert.match(MA_SERIAL_WORKFLOW_HINT, /工作流/)
  assert.doesNotMatch(hint, /跳过判路由/)
})

/** 入口类型：不在新建白名单里，列表可见，名称仍是 Router */
test('entry router is visible and readable but not selectable', () => {
  assert.ok(!(MA_LIST_TEMPLATES as readonly string[]).includes(MA_LIST_TEMPLATE_ENTRY_ROUTER))
  assert.ok((MA_LIST_FILTER_TEMPLATES as readonly string[]).includes(MA_LIST_TEMPLATE_ENTRY_ROUTER))
  assert.equal(MA_LIST_FILTER_TEMPLATES.length, MA_LIST_TEMPLATES.length + 1)
  assert.equal(isEntryRouterTemplate(MA_LIST_TEMPLATE_ENTRY_ROUTER), true)
  assert.equal(isEntryRouterTemplate(MA_LIST_TEMPLATE_ROUTER_PARALLEL), false)
  assert.equal(maTemplateLabel(MA_LIST_TEMPLATE_ENTRY_ROUTER), '对话路由')
  assert.equal(canonicalMaTemplate(MA_LIST_TEMPLATE_ENTRY_ROUTER), MA_LIST_TEMPLATE_ENTRY_ROUTER)
  assert.equal(MA_ENTRY_ROUTER_NAME, 'Router')
  assert.match(MA_ENTRY_ROUTER_DESCRIPTION, /对话入口/)
  assert.match(MA_ENTRY_ROUTER_DESCRIPTION, /不可删除/)
  assert.equal(normalizeMaListTemplate(MA_LIST_TEMPLATE_ENTRY_ROUTER), MA_LIST_TEMPLATE_ROUTER_PARALLEL)
  assert.equal(routesByJudgeTemplate(MA_LIST_TEMPLATE_ENTRY_ROUTER), true)
  assert.equal(routesByJudgeTemplate(MA_LIST_TEMPLATE_ROUTER_PARALLEL), true)
  assert.equal(routesByJudgeTemplate(MA_LIST_TEMPLATE_ROUTER_THEN_SEQUENCE), true)
  assert.equal(routesByJudgeTemplate(MA_LIST_TEMPLATE_FIXED_SEQUENCE), true)
})
