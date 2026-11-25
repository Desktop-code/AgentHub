/**
 * 试跑时间线的块级稳定 id 与快照浅签名单测。所属域：runtime（前端）。
 *
 * <p>这两件事直接决定「流式插块会不会整表重挂」与「快照写盘是否会漏内容」，
 * 都是纯数据口径，不需要 DOM / Vue 响应式即可覆盖。
 *
 * 运行：node --experimental-strip-types --test tests/trialTimeline.test.ts
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  ensureBlockIds,
  nextBlockId,
  snapshotSignature
} from '../src/components/runtime/trialTimeline.ts'

/** 造一条只有块的最小助手轮；key 用回放口径，方便断言 id 形式。 */
function turn(key: string, blocks: { id?: string; kind: string; text: string }[] = []) {
  return {
    key,
    role: 'assistant' as const,
    text: '',
    blocks,
    phase: 'planning',
    status: 'streaming' as const,
    startTime: 0
  }
}

test('块 id 按轮自增，格式为「轮 key#b序号」', () => {
  const item = turn('e-7')
  assert.equal(nextBlockId(item), 'e-7#b1')
  assert.equal(nextBlockId(item), 'e-7#b2')
  assert.equal(item.blockSeq, 2)
})

test('视觉块插到队首不改变已有块 id（这是不整表重挂的前提）', () => {
  const item = turn('e-9', [
    { id: 'e-9#b1', kind: 'assistant', text: '终答' },
    { id: 'e-9#b2', kind: 'file', text: 'a.md' }
  ])
  item.blockSeq = 2
  // 等价于 appendVision：新块取号后 unshift 到队首
  item.blocks.unshift({ id: nextBlockId(item), kind: 'vision', text: '识别结果' })
  assert.deepEqual(
    item.blocks.map((block) => block.id),
    ['e-9#b3', 'e-9#b1', 'e-9#b2']
  )
  // 每个块的 id 仍然唯一：不会出现两块共用一个 key
  assert.equal(new Set(item.blocks.map((block) => block.id)).size, 3)
})

test('ensureBlockIds 给老快照补号：保持原位、幂等、不与他人撞号', () => {
  const old = turn('e-3', [
    { kind: 'reasoning', text: '想一下' },
    { kind: 'assistant', text: '答案' }
  ])
  const turns = [old]
  assert.equal(ensureBlockIds(turns), turns)
  assert.deepEqual(
    old.blocks.map((block) => block.id),
    ['e-3#b1', 'e-3#b2']
  )
  const before = old.blocks.map((block) => block.id)
  // 再跑一次不能重新编号，否则每刷新一次 key 就变一次
  ensureBlockIds(turns)
  assert.deepEqual(old.blocks.map((block) => block.id), before)
  // 补号后继续追加，序号接着往后走
  assert.equal(nextBlockId(old), 'e-3#b3')
})

test('ensureBlockIds 顺带修掉缺失的 blocks 数组', () => {
  const broken = { ...turn('e-4'), blocks: undefined } as unknown as ReturnType<typeof turn>
  ensureBlockIds([broken])
  assert.deepEqual(broken.blocks, [])
})

test('快照签名：内容没变就不变（避免重复 stringify）', () => {
  const item = turn('u-1#tail', [{ id: 'b1', kind: 'assistant', text: '今天' }])
  const turns = [turn('u-1'), item]
  const first = snapshotSignature(turns)
  assert.equal(snapshotSignature(turns), first)
  // 只改一个与签名无关的对象引用，签名必须保持一致
  assert.equal(snapshotSignature([...turns]), first)
})

test('快照签名：末尾轮增长 / 新增块 / 追加轮次都会失效', () => {
  const tail = turn('u-1#tail', [{ id: 'b1', kind: 'assistant', text: '今天' }])
  const turns = [turn('u-1'), tail]
  const base = snapshotSignature(turns)

  tail.blocks[0].text += '天气'
  const grown = snapshotSignature(turns)
  assert.notEqual(grown, base)

  tail.blocks.push({ id: 'b2', kind: 'tool', text: '调用工具 x' })
  const appended = snapshotSignature(turns)
  assert.notEqual(appended, grown)

  const withNext = [...turns, turn('u-2')]
  assert.notEqual(snapshotSignature(withNext), appended)
})

test('快照签名：空时间线也能给出稳定值', () => {
  assert.equal(snapshotSignature([]), '0|0')
})
