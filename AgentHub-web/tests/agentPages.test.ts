/**
 * 下拉全量 Agent 的分页累加窄测。所属域：前端 common。
 *
 * <p>覆盖四件事：
 * 1) 每页 size 必须 ≤100（回归：旧实现写 200 会被后端 @Max(100) 驳回）；
 * 2) 满页继续翻、短页立即停；
 * 3) 累计硬顶截断，不死循环；
 * 4) 任一分页失败向上抛，由调用方给中文提示。
 *
 * 运行：node --experimental-strip-types --test tests/agentPages.test.ts
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import {
  AGENT_FETCH_CAP,
  AGENT_PAGE_SIZE,
  collectAgentPages
} from '../src/utils/agentPages.ts'

test('默认页大小不超过后端上限 100', () => {
  assert.equal(AGENT_PAGE_SIZE, 100)
  assert.ok(AGENT_PAGE_SIZE <= 100)
  assert.equal(AGENT_FETCH_CAP, 500)
})

test('满页继续、短页停止：两页半共 240 条，只请求 3 次', async () => {
  const pages = [
    { records: Array.from({ length: 100 }, (_, i) => i + 1), total: 240 },
    { records: Array.from({ length: 100 }, (_, i) => i + 101), total: 240 },
    { records: Array.from({ length: 40 }, (_, i) => i + 201), total: 240 }
  ]
  const asked: Array<[number, number]> = []
  const rows = await collectAgentPages(async (current, size) => {
    asked.push([current, size])
    return pages[current - 1]
  })
  assert.equal(rows.length, 240)
  assert.deepEqual(asked, [
    [1, 100],
    [2, 100],
    [3, 100]
  ])
})

test('已知 total 且正好取满：停在最后一页，不额外打空请求', async () => {
  const asked: number[] = []
  const rows = await collectAgentPages(async (current) => {
    asked.push(current)
    return { records: Array.from({ length: 100 }, (_, i) => i), total: 100 }
  })
  assert.equal(rows.length, 100)
  assert.deepEqual(asked, [1])
})

test('空页立即结束：total 与实际不一致也不多请求', async () => {
  const asked: number[] = []
  const rows = await collectAgentPages(async (current) => {
    asked.push(current)
    return { records: [], total: 999 }
  })
  assert.deepEqual(rows, [])
  assert.deepEqual(asked, [1])
})

test('累计硬顶截断：永不无限翻页', async () => {
  const asked: number[] = []
  const rows = await collectAgentPages(async (current) => {
    asked.push(current)
    // 每页都满且 total 报得很大：只能靠硬顶收手
    return { records: Array.from({ length: 100 }, (_, i) => i), total: 10_000 }
  })
  assert.equal(rows.length, AGENT_FETCH_CAP)
  assert.equal(asked.length, AGENT_FETCH_CAP / AGENT_PAGE_SIZE)
})

test('分页失败向上抛：调用方仍能给出中文提示', async () => {
  await assert.rejects(
    () =>
      collectAgentPages(async () => {
        throw new Error('每页最多 100 条')
      }),
    /每页最多 100 条/
  )
})

test('非法入参收敛：页大小 0 或负数不死循环', async () => {
  const asked: number[] = []
  const rows = await collectAgentPages(
    async (current, size) => {
      asked.push(size)
      return { records: [current], total: 3 }
    },
    0,
    10
  )
  assert.ok(asked.every((size) => size >= 1))
  assert.ok(rows.length <= 10)
})
