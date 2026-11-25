/**
 * Skill 触发关键词前端归一验证（node:test，无浏览器）。所属域：skill 前端工具。
 *
 * 覆盖本刀的核心交互口径：
 * 1) 一次粘贴/输入多词，按中英文逗号与换行切割；
 * 2) 去空白、去空段、忽略大小写去重；
 * 3) 单项 ≤32、最多 20 个、合计 ≤255 字符，超限有中文提示而不是静默截断；
 * 4) 数组 → 库内逗号串的 join 口径与后端一致。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node --experimental-strip-types --test tests/skillKeywords.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  KEYWORD_COUNT_MAX,
  KEYWORD_MAX,
  KEYWORDS_COLUMN_MAX,
  addKeywords,
  dedupeKeywords,
  joinKeywords,
  splitKeywordText
} from '../src/utils/skillKeywords.ts'

/* ============ 一、切割 ============ */

test('切割：中英文逗号与换行都当分隔符', () => {
  assert.deepEqual(splitKeywordText('退款,发票，对账\n复核'), ['退款', '发票', '对账', '复核'])
})

test('切割：去空白、去空段，空串返回空数组', () => {
  assert.deepEqual(splitKeywordText('  退款 , , 发票  '), ['退款', '发票'])
  assert.deepEqual(splitKeywordText(''), [])
  assert.deepEqual(splitKeywordText(' , ， '), [])
})

/* ============ 二、去重与 join ============ */

test('去重：忽略大小写，保留首次出现的写法', () => {
  assert.deepEqual(dedupeKeywords(['OCR', 'ocr', ' OCR ', 'PDF']), ['OCR', 'PDF'])
})

test('join：库内英文逗号串；空列表得空串', () => {
  assert.equal(joinKeywords(['退款', '发票', '对账']), '退款,发票,对账')
  assert.equal(joinKeywords([]), '')
})

/* ============ 三、粘贴合并 ============ */

test('粘贴：一次 a,b，c 得到三个 Tag，顺序保持', () => {
  const result = addKeywords([], 'a,b，c')
  assert.deepEqual(result.tags, ['a', 'b', 'c'])
  assert.equal(result.error, '')
})

test('粘贴：重复词忽略（含大小写差异），不产生脏 Tag', () => {
  const result = addKeywords(['OCR'], 'ocr,PDF,ocr')
  assert.deepEqual(result.tags, ['OCR', 'PDF'])
  assert.equal(result.error, '')
})

test('粘贴：空白串不改变列表', () => {
  const result = addKeywords(['退款'], '  , ，')
  assert.deepEqual(result.tags, ['退款'])
  assert.equal(result.error, '')
})

/* ============ 四、超限提示 ============ */

test('超限：单个关键词超过 32 字符被跳过并提示，其余合法词仍入库', () => {
  const tooLong = 'a'.repeat(KEYWORD_MAX + 1)
  const result = addKeywords([], `${tooLong},退款`)
  assert.deepEqual(result.tags, ['退款'])
  assert.match(result.error, /单个触发关键词最长 32/)
})

test('超限：最多 20 个，超出部分不再接收', () => {
  const raw = Array.from({ length: KEYWORD_COUNT_MAX + 3 }, (_, i) => `k${i}`).join(',')
  const result = addKeywords([], raw)
  assert.equal(result.tags.length, KEYWORD_COUNT_MAX)
  assert.match(result.error, /最多 20 个/)
})

test('超限：合计超过 255 字符时停止追加并提示', () => {
  // 20 个 13 字符的关键词：单项合法，但 join 后超过列宽
  const raw = Array.from({ length: KEYWORD_COUNT_MAX }, (_, i) =>
    `k${String(i).padStart(2, '0')}${'a'.repeat(13)}`
  ).join(',')
  const result = addKeywords([], raw)
  assert.match(result.error, /合计最长 255/)
  assert.ok(joinKeywords(result.tags).length <= KEYWORDS_COLUMN_MAX)
})

test('新增不清空已有 Tag：在现有列表后追加', () => {
  const result = addKeywords(['退款'], '发票')
  assert.deepEqual(result.tags, ['退款', '发票'])
})
