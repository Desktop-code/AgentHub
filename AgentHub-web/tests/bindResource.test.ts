/**
 * 绑定选择器数据映射验证（node:test，无浏览器）。所属域：前端通用。
 *
 * 覆盖三条容易回归的口径：
 * 1) 工具主标题必须优先中文展示名 title，name 只作英文码；
 * 2) 停用项保留在候选里但置 disabled（可见不可选）；
 * 3) 已绑定的项必须被剔除，否则提交时会撞后端唯一约束；
 * 4) 空分组整体丢弃，避免界面出现「0 条」的标题。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/bindResource.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  TOOL_GROUP_BUILTIN,
  TOOL_GROUP_MCP,
  buildSkillGroups,
  buildToolGroups,
  filterResourceGroups,
  toSkillOption,
  toToolOption
} from '../src/utils/bindResource.ts'
import type { ResourceGroup } from '../src/types/resource.ts'
import type { SkillCard } from '../src/types/skill.ts'
import type { ToolItemRow } from '../src/types/tool.ts'

/**
 * 造一条工具项。
 *
 * @param patch 需要覆盖的字段
 * @return 工具项
 */
function tool(patch: Partial<ToolItemRow> = {}): ToolItemRow {
  return {
    id: 1,
    sourceId: 1,
    name: 'web_search',
    title: '联网搜索',
    description: '按关键词检索网页',
    status: 1,
    sourceType: 'BUILTIN',
    sourceName: '内置工具集',
    ...patch
  }
}

/**
 * 造一张 Skill 卡片。
 *
 * @param patch 需要覆盖的字段
 * @return Skill 卡片
 */
function skill(patch: Partial<SkillCard> = {}): SkillCard {
  return {
    id: 10,
    name: 'pdf_extract',
    title: 'PDF 文本抽取',
    description: '从 PDF 中抽取正文',
    triggerKeywords: ['pdf', '抽取'],
    version: '1',
    activeVersionNo: 1,
    status: 1,
    ...patch
  }
}

/* ============ 一、工具：字段口径与置灰 ============ */

test('工具选项：主标题取中文 title，英文 name 落到 code', () => {
  const option = toToolOption(tool())
  assert.equal(option.name, '联网搜索', '主标题必须是中文展示名')
  assert.equal(option.code, 'web_search', '英文调用码必须落到 code')
  assert.equal(option.extra, '内置工具集', '来源名进 extra 供标签展示')
})

test('工具选项：没有 title 时回落到 name，不出现空白标题', () => {
  const option = toToolOption(tool({ title: undefined }))
  assert.equal(option.name, 'web_search')
})

test('工具选项：停用项可见但 disabled', () => {
  assert.equal(toToolOption(tool({ status: 1 })).disabled, false)
  assert.equal(toToolOption(tool({ status: 0 })).disabled, true, '停用工具必须置灰不可新勾选')
})

/* ============ 二、工具：分组与剔除 ============ */

test('工具分组：BUILTIN 进内置组，MCP 进 MCP 组', () => {
  const groups = buildToolGroups(
    [
      tool({ id: 1, sourceType: 'BUILTIN' }),
      tool({ id: 2, sourceType: 'MCP', sourceName: '高德 MCP' })
    ],
    []
  )
  assert.equal(groups.length, 2)
  assert.equal(groups[0].groupName, TOOL_GROUP_BUILTIN)
  assert.deepEqual(groups[0].items.map((item) => item.id), [1])
  assert.equal(groups[1].groupName, TOOL_GROUP_MCP)
  assert.deepEqual(groups[1].items.map((item) => item.id), [2])
})

test('工具分组：空分组整段丢弃，不渲染 0 条标题', () => {
  const groups = buildToolGroups([tool({ sourceType: 'BUILTIN' })], [])
  assert.equal(groups.length, 1, 'MCP 组为 0 条时不应出现')
  assert.equal(groups[0].groupName, TOOL_GROUP_BUILTIN)
})

test('工具分组：已绑定的工具被剔除', () => {
  const groups = buildToolGroups([tool({ id: 1 }), tool({ id: 2 })], [1])
  const ids = groups.flatMap((group) => group.items.map((item) => item.id))
  assert.deepEqual(ids, [2], '已绑定项不能再出现在候选里')
})

test('工具分组：全部已绑定时返回空数组', () => {
  assert.deepEqual(buildToolGroups([tool({ id: 1 })], [1]), [])
})

/* ============ 三、Skill：单组与置灰 ============ */

test('技能选项：标题优先，关键词进 tags，生效版本进 extra', () => {
  const option = toSkillOption(skill({ activeVersionNo: 3 }))
  assert.equal(option.name, 'PDF 文本抽取')
  assert.equal(option.code, 'pdf_extract')
  assert.deepEqual(option.tags, ['pdf', '抽取'])
  assert.equal(option.extra, 'v3', '卡片右上角角标展示生效版本')
})

test('技能选项：未发布过时角标显示「未发布」', () => {
  assert.equal(toSkillOption(skill({ activeVersionNo: null })).extra, '未发布')
})

test('技能选项：没有标题时回落编码', () => {
  assert.equal(toSkillOption(skill({ title: '' })).name, 'pdf_extract')
})

test('技能分组：统一归入单一「技能库」组', () => {
  const groups = buildSkillGroups([skill({ id: 10 }), skill({ id: 11 })], [])
  assert.equal(groups.length, 1)
  assert.equal(groups[0].groupName, '技能库')
  assert.equal(groups[0].items.length, 2)
})

test('技能分组：已绑定剔除，停用项保留并置灰，全绑完返回空数组', () => {
  const groups = buildSkillGroups([skill({ id: 10, status: 0 }), skill({ id: 11 })], [11])
  assert.equal(groups[0].items.length, 1)
  assert.equal(groups[0].items[0].id, 10)
  assert.equal(groups[0].items[0].disabled, true)
  assert.deepEqual(buildSkillGroups([skill({ id: 10 })], [10]), [])
})

/* ============ 四、分组筛选（可选资源 / 工作流浮层共用） ============ */

/**
 * 造一份双组候选，覆盖中文名、英文码、说明、标签命中路径。
 *
 * @return 分组数据源
 */
function sampleGroups(): ResourceGroup[] {
  return [
    {
      groupName: TOOL_GROUP_BUILTIN,
      items: [
        {
          id: 1,
          name: '知识库检索',
          code: 'kb_search',
          description: '按问题检索知识库片段',
          extra: '内置工具集',
          tags: ['rag']
        },
        {
          id: 2,
          name: '联网搜索',
          code: 'web_search',
          description: '按关键词检索网页',
          extra: '内置工具集'
        }
      ]
    },
    {
      groupName: TOOL_GROUP_MCP,
      items: [
        {
          id: 3,
          name: '高德地理编码',
          code: 'amap_geo',
          description: '地址转坐标',
          extra: '高德 MCP',
          tags: ['地图']
        }
      ]
    }
  ]
}

test('筛选：空关键字不过滤，分组原样返回', () => {
  const groups = sampleGroups()
  assert.equal(filterResourceGroups(groups, '').length, 2)
  assert.equal(filterResourceGroups(groups, '   ').length, 2)
  assert.deepEqual(filterResourceGroups(groups, ''), groups)
})

test('筛选：按中文展示名包含匹配（大小写不敏感）', () => {
  const filtered = filterResourceGroups(sampleGroups(), '知识库')
  assert.equal(filtered.length, 1)
  assert.equal(filtered[0].groupName, TOOL_GROUP_BUILTIN)
  assert.deepEqual(filtered[0].items.map((item) => item.id), [1])
})

test('筛选：按英文代码包含匹配', () => {
  const filtered = filterResourceGroups(sampleGroups(), 'WEB_SEARCH')
  assert.equal(filtered.length, 1)
  assert.deepEqual(filtered[0].items.map((item) => item.id), [2])
})

test('筛选：按说明包含匹配', () => {
  const filtered = filterResourceGroups(sampleGroups(), '转坐标')
  assert.equal(filtered.length, 1)
  assert.equal(filtered[0].groupName, TOOL_GROUP_MCP)
  assert.deepEqual(filtered[0].items.map((item) => item.id), [3])
})

test('筛选：命中为空的分组不出现', () => {
  const filtered = filterResourceGroups(sampleGroups(), 'amap')
  assert.equal(filtered.length, 1, '内置组无命中时整组丢弃')
  assert.equal(filtered[0].groupName, TOOL_GROUP_MCP)
})

test('筛选：无任何命中时返回空数组', () => {
  assert.deepEqual(filterResourceGroups(sampleGroups(), '不存在的关键字xyz'), [])
})
