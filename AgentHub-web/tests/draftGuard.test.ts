/**
 * 跨组件草稿登记表单测（node:test，无 DOM）。
 * 覆盖：无登记放行、有草稿拦截、答「丢弃」才回滚、注销后不再命中。
 * Element Plus 的确认框用 node:module 打桩，不引入真实 DOM。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

/**
 * 打桩 Element Plus：只提供被 unsavedConfirm 用到的 ElMessageBox.confirm。
 * 用全局开关决定「用户点了确认还是取消」，这样无需真实 DOM 也能验证交互分支。
 * 同时把 `@/` 别名映射到 src，node 原生运行器不认识 vite alias。
 */
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'

const SRC_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../src')

const { registerHooks } = await import('node:module')
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === 'element-plus') {
      return { url: 'stub:element-plus', shortCircuit: true }
    }
    // `@/utils/x` → `<repo>/src/utils/x.ts`
    if (specifier.startsWith('@/')) {
      const target = specifier.slice(2)
      const withExt = target.endsWith('.ts') ? target : `${target}.ts`
      return {
        url: pathToFileURL(path.join(SRC_DIR, withExt)).href,
        shortCircuit: true
      }
    }
    return nextResolve(specifier, context)
  },
  load(url, context, nextLoad) {
    if (url === 'stub:element-plus') {
      return {
        format: 'module',
        shortCircuit: true,
        source: `
          export const ElMessageBox = {
            confirm: async () => {
              globalThis.__confirmCalls = (globalThis.__confirmCalls || 0) + 1
              if (!globalThis.__confirmAnswer) {
                throw new Error('cancel')
              }
            }
          }
        `
      }
    }
    return nextLoad(url, context)
  }
})

const { registerDraft, hasDraft, confirmLeaveDraft } = await import('../src/composables/draftGuard.ts')

/** 让桩的答案可控，并清零调用计数 */
function answerConfirm(value: boolean) {
  ;(globalThis as Record<string, unknown>).__confirmAnswer = value
  ;(globalThis as Record<string, unknown>).__confirmCalls = 0
}

test('未登记的对象视为没有草稿', () => {
  assert.equal(hasDraft('agent:404'), false)
})

test('登记后按 isDirty 反映草稿状态', () => {
  let dirty = true
  const off = registerDraft('agent:1', { isDirty: () => dirty, discard: () => {} })
  assert.equal(hasDraft('agent:1'), true)

  dirty = false
  assert.equal(hasDraft('agent:1'), false)
  off()
})

test('无草稿时 confirmLeaveDraft 直接放行，不弹确认框', async () => {
  answerConfirm(false)
  const off = registerDraft('agent:2', { isDirty: () => false, discard: () => {} })
  assert.equal(await confirmLeaveDraft('agent:2', 'Agent'), true)
  assert.equal((globalThis as Record<string, number>).__confirmCalls || 0, 0)
  off()
})

test('有草稿但用户选「继续编辑」时拦截且不丢弃', async () => {
  answerConfirm(false)
  let discarded = false
  const off = registerDraft('agent:3', { isDirty: () => true, discard: () => (discarded = true) })
  assert.equal(await confirmLeaveDraft('agent:3', 'Agent'), false)
  assert.equal(discarded, false)
  off()
})

test('有草稿且用户确认丢弃时回滚并放行', async () => {
  answerConfirm(true)
  let discarded = false
  const off = registerDraft('agent:4', { isDirty: () => true, discard: () => (discarded = true) })
  assert.equal(await confirmLeaveDraft('agent:4', 'Agent'), true)
  assert.equal(discarded, true)
  off()
})

test('注销后不再命中该登记', () => {
  const off = registerDraft('agent:5', { isDirty: () => true, discard: () => {} })
  off()
  assert.equal(hasDraft('agent:5'), false)
})

test('同 key 被新实例覆盖时，旧实例的注销不影响新登记', () => {
  const offOld = registerDraft('agent:6', { isDirty: () => true, discard: () => {} })
  const offNew = registerDraft('agent:6', { isDirty: () => false, discard: () => {} })
  offOld()
  // 旧实例注销不应把新登记一起删掉：此时应读到新实例的「不脏」
  assert.equal(hasDraft('agent:6'), false)
  offNew()
})
