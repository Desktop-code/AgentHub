/**
 * Vue 布尔 prop 陷阱的运行时验证（Node + SSR，无需浏览器/DOM）。所属域：agent（前端自测）。
 *
 * 运行（在 AgentHub-web 目录）：
 *   node scripts/booleanProp.guard.mjs
 *
 * 背景：线上曾出现「模型槽已绑模型却看不到『配置提示词』入口」。
 * 根因是 `promptSupported?: boolean` 用纯类型声明 —— Vue 3 对 type:Boolean 的 prop
 * 在父级**未传**时会强制转成 `false`（而非 undefined），于是 `!== false` 恒为假，入口被隐藏。
 * 本脚本用两个只有 props 声明的极简组件对比「纯类型声明」与「default: undefined」的实际取值，
 * 让修复由 Vue 运行时行为证明，而不是靠肉眼读代码。
 */
import { createSSRApp, defineComponent, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import assert from 'node:assert/strict'

/** 模拟旧写法：纯类型声明（Vue 把未传的 Boolean prop 强转 false） */
const LegacyChild = defineComponent({
  name: 'LegacyChild',
  props: {
    purpose: { type: String, required: true },
    // 关键：没有 default，Vue 对 Boolean 类型在缺省时给 false
    promptSupported: Boolean
  },
  setup(props) {
    const showEntry = props.promptSupported !== false
    return () => h('span', `val=${String(props.promptSupported)};show=${String(showEntry)}`)
  }
})

/** 修复后写法：显式 default: undefined，让「未传」保持未指定 */
const FixedChild = defineComponent({
  name: 'FixedChild',
  props: {
    purpose: { type: String, required: true },
    promptSupported: { type: Boolean, default: undefined }
  },
  setup(props) {
    const showEntry = props.promptSupported !== false
    return () => h('span', `val=${String(props.promptSupported)};show=${String(showEntry)}`)
  }
})

/**
 * 用 SSR 渲染一个只渲染子组件的父组件，返回子组件吐出的文本。
 *
 * @param component 被测子组件
 * @param parentProps 父级传给它的 props
 * @return 形如 "val=undefined;show=true" 的文本
 */
async function renderChild(component, parentProps) {
  const Parent = defineComponent({
    setup() {
      return () => h(component, parentProps)
    }
  })
  const html = await renderToString(createSSRApp(Parent))
  const match = html.match(/<span>(.*?)<\/span>/)
  assert.ok(match, `未渲染出 span，实际 HTML: ${html}`)
  return match[1]
}

/* 1) 旧写法：不传 promptSupported → false → 入口恒不可见（复现线上 bug） */
const legacy = await renderChild(LegacyChild, { purpose: 'REWRITE' })
assert.equal(legacy, 'val=false;show=false', `旧写法应复现 bug，实际: ${legacy}`)

/* 2) 新写法：不传 → undefined → 入口可见（修复生效） */
const notPassed = await renderChild(FixedChild, { purpose: 'REWRITE' })
assert.equal(notPassed, 'val=undefined;show=true', `未传时入口必须可见，实际: ${notPassed}`)

/* 3) 新写法：显式 false → 仍可整体关闭（父级开关不被削弱） */
const off = await renderChild(FixedChild, { purpose: 'REWRITE', promptSupported: false })
assert.equal(off, 'val=false;show=false', `显式 false 应隐藏入口，实际: ${off}`)

/* 4) 新写法：显式 true → 可见 */
const on = await renderChild(FixedChild, { purpose: 'REWRITE', promptSupported: true })
assert.equal(on, 'val=true;show=true', `显式 true 应可见，实际: ${on}`)

console.log('booleanProp guard: 全部通过（旧写法复现 bug，新写法规避）')
