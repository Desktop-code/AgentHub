/**
 * 表单脏检查 composable 单测（node:test，无 DOM）。
 * 覆盖：初始干净、文本改动置脏、开关字段不纳入、markClean、reset 回滚、稳定序列化。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { useFormDirty, useObjectDirty, stableKey } from '../src/composables/useFormDirty.ts'

/** 造一份「文本/选择 + 开关」混合表单，开关字段故意不放进快照 */
function makeForm() {
  const form = reactive({
    name: '',
    remark: '',
    answerMode: 'CONCAT',
    // 开关：即时落库语义，不参与脏检查
    status: 0
  })
  const dirty = useFormDirty(
    () => ({ name: form.name, remark: form.remark, answerMode: form.answerMode }),
    (snap) => {
      form.name = snap.name
      form.remark = snap.remark
      form.answerMode = snap.answerMode
    }
  )
  return { form, dirty }
}

test('未 markClean 前一律不脏（异步回填不会误判）', () => {
  const { form, dirty } = makeForm()
  form.name = '草稿'
  assert.equal(dirty.isDirty.value, false)
})

test('初始回填后 markClean：未改动为干净', () => {
  const { form, dirty } = makeForm()
  form.name = '客服助手'
  dirty.markClean()
  assert.equal(dirty.isDirty.value, false)
})

test('文本改动置脏，改回原值恢复干净', () => {
  const { form, dirty } = makeForm()
  form.name = '客服助手'
  dirty.markClean()

  form.name = '客服助手2'
  assert.equal(dirty.isDirty.value, true)

  form.name = '客服助手'
  assert.equal(dirty.isDirty.value, false)
})

test('选择类（下拉/单选）改动置脏', () => {
  const { form, dirty } = makeForm()
  dirty.markClean()
  form.answerMode = 'SUMMARIZE'
  assert.equal(dirty.isDirty.value, true)
})

test('开关类字段不纳入快照：改了也不置脏', () => {
  const { form, dirty } = makeForm()
  dirty.markClean()
  form.status = 1
  assert.equal(dirty.isDirty.value, false)
})

test('reset 丢弃草稿回到快照，随后灭脏', () => {
  const { form, dirty } = makeForm()
  form.name = '客服助手'
  form.remark = '备注'
  dirty.markClean()

  form.name = '改名'
  form.remark = '改备注'
  assert.equal(dirty.isDirty.value, true)

  dirty.reset()
  assert.equal(form.name, '客服助手')
  assert.equal(form.remark, '备注')
  assert.equal(dirty.isDirty.value, false)
})

test('reset 后继续编辑旧快照不会被草稿污染', () => {
  const { form, dirty } = makeForm()
  form.name = 'A'
  dirty.markClean()
  form.name = 'B'
  dirty.reset()
  // 快照是深拷贝：再次编辑表单不应改动基线
  form.name = 'C'
  dirty.reset()
  assert.equal(form.name, 'A')
})

test('纯展示字段不在快照里：引用换新对象不误脏', () => {
  const { dirty } = makeForm()
  dirty.markClean()
  const a = { id: 1, name: 'x', updateTime: '2026-01-01' }
  const b = { updateTime: '2026-01-01', name: 'x', id: 1 }
  // 键序不同但值相同 → 稳定序列化必须一致
  assert.equal(stableKey(a), stableKey(b))
})

test('数组顺序参与比较（选项顺序是业务语义）', () => {
  assert.notEqual(stableKey({ ids: [1, 2] }), stableKey({ ids: [2, 1] }))
})

test('patchBaseline 只洗白指定字段：其它草稿仍算脏', () => {
  const form = reactive({ name: 'A', remark: '', promptExtra: '' })
  const dirty = useFormDirty(
    () => ({ name: form.name, remark: form.remark, promptExtra: form.promptExtra }),
    (snap) => {
      form.name = snap.name
      form.remark = snap.remark
      form.promptExtra = snap.promptExtra
    }
  )
  dirty.markClean()

  // 用户改了两处：提示词（弹窗自带保存）与备注（页底保存）
  form.promptExtra = '追加要求'
  form.remark = '备注草稿'
  assert.equal(dirty.isDirty.value, true)

  // 提示词落库成功 → 只洗白这一列
  dirty.patchBaseline({ promptExtra: '追加要求' })
  assert.equal(dirty.isDirty.value, true, '备注草稿必须继续算脏')

  // 备注也落库后再比较才回到干净
  dirty.patchBaseline({ remark: '备注草稿' })
  assert.equal(dirty.isDirty.value, false)
})

test('useObjectDirty：编辑弹窗未改动为干净，改回原值也回到干净', () => {
  const form = reactive({ name: '旧名', sort: 0, status: 1 })
  const dirty = useObjectDirty(() => form)
  dirty.markClean()
  assert.equal(dirty.isDirty.value, false)

  form.name = '新名'
  assert.equal(dirty.isDirty.value, true)

  form.name = '旧名'
  assert.equal(dirty.isDirty.value, false)
})

test('useObjectDirty：ignore 列改动不置脏，reset 可回滚', () => {
  const form = reactive({ id: 7, name: '旧名' })
  const dirty = useObjectDirty(() => form, ['id'])
  dirty.markClean()

  // id 被忽略：改它不算改动
  form.id = 9
  assert.equal(dirty.isDirty.value, false)

  form.name = '新名'
  dirty.reset()
  assert.equal(form.name, '旧名')
  assert.equal(form.id, 9, 'ignored 字段不参与回滚')
  assert.equal(dirty.isDirty.value, false)
})

test('setBaseline：服务端刷新后保留草稿，脏态不丢', () => {
  const form = reactive({ name: '旧名', remark: '' })
  const dirty = useFormDirty(
    () => ({ name: form.name, remark: form.remark }),
    (snap) => {
      form.name = snap.name
      form.remark = snap.remark
    }
  )
  dirty.markClean()

  // 用户在编辑，同时触发了接口刷新（如增删绑定）
  form.name = '未保存的改名'
  assert.equal(dirty.isDirty.value, true)

  // 刷新回来的服务端值只是新基线，不能把表单草稿当成基线
  dirty.setBaseline({ name: '旧名', remark: '' })
  assert.equal(dirty.isDirty.value, true, '草稿必须继续算脏')

  // 取消仍能回到服务端值
  dirty.reset()
  assert.equal(form.name, '旧名')
  assert.equal(dirty.isDirty.value, false)
})
