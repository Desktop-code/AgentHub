/**
 * 刀 D 接线自检：MA 运行页与工作台共用同一套「运行中显示停止」判定。所属域：runtime（前端）。
 *
 * <p>钉住三件事（组件层模板不易在 node 里渲染，用源码契约断言）：
 * <ol>
 *   <li>MA 输入条确实拿到 can-stop 与 stop 处理器——否则 skin=workbench 下停止按钮不出现，
 *       运行中只剩可点的发送，下一句会被当成新问题发进还在跑的那一轮；</li>
 *   <li>ChatPanel / ChatComposer 把续订状态（resuming）透传到输入条；</li>
 *   <li>停止 / 发送的显隐只走 {@code sessionProgress.composerButtons}，不再在模板里各写一套条件。</li>
 * </ol>
 *
 * 运行：在 AgentHub-web 下 `npm test`。
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/**
 * 读仓内源文件文本。
 *
 * @param rel 相对 AgentHub-web 的路径
 * @return 文件全文
 */
function readSrc(rel: string): string {
  return readFileSync(join(WEB_ROOT, rel), 'utf8')
}

describe('MA 运行页停止按钮接线', () => {
  const trialPanel = readSrc('src/components/runtime/TrialPanel.vue')
  const maRunFlow = readSrc('src/components/runtime/ma/MaRunFlow.vue')

  it('TrialPanel 把 can-stop 与 @stop 传给 MaRunFlow', () => {
    const start = trialPanel.indexOf('<MaRunFlow')
    assert.ok(start > 0, 'TrialPanel 应渲染 MaRunFlow')
    const block = trialPanel.slice(start, trialPanel.indexOf('/>', start))
    assert.match(block, /:can-stop="canStopRun"/, 'MA 输入条必须拿到可停止信号')
    assert.match(block, /@stop="stopRun"/, 'MA 输入条必须接停止处理器，不能露出死按钮')
  })

  it('MaRunFlow 把两者透传给 ChatPanel，且接的是工作台 skin', () => {
    const start = maRunFlow.indexOf('<ChatPanel')
    assert.ok(start > 0, 'MaRunFlow 应渲染 ChatPanel 输入条')
    const block = maRunFlow.slice(start, maRunFlow.indexOf('/>', start))
    assert.match(block, /skin="workbench"/)
    assert.match(block, /:can-stop="canStop"/)
    assert.match(block, /@stop="emit\('stop'\)"/)
  })

  it('TrialPanel 的可停止判据复用 sessionProgress，不另写一套', () => {
    assert.match(trialPanel, /canStopRun as canStopRunOf/, '应复用共用判定，不另起一套按钮逻辑')
    assert.match(trialPanel, /canStopRunOf\(\s*\{[^}]*sending[^}]*resuming/s, '续订必须算进可停止信号')
  })
})

describe('输入条停止 / 发送只用一套判定', () => {
  const composer = readSrc('src/components/runtime/workbench/chat/ChatComposer.vue')
  const chatPanel = readSrc('src/components/runtime/ChatPanel.vue')
  const workbench = readSrc('src/components/runtime/workbench/WorkbenchChat.vue')

  it('ChatComposer 的按钮显隐来自 composerButtons', () => {
    assert.match(composer, /composerButtons/, '停止 / 发送显隐应走共用纯函数')
    assert.match(composer, /const stopShown = computed\(\(\) => props\.canSend && buttons\.value\.stop\)/)
    assert.match(composer, /const sendShown = computed\(\(\) => props\.canSend && buttons\.value\.send\)/)
  })

  it('ChatComposer 的发送闸门与按钮显隐同口径（回车 / 快捷卡也拦得住）', () => {
    const submitStart = composer.indexOf('function submit()')
    assert.ok(submitStart > 0)
    const submitBody = composer.slice(submitStart, submitStart + 2000)
    assert.match(submitBody, /runBlocksSending\(runActivity\.value\)/, '发送必须被同一条判定拦住')
    const promptStart = composer.indexOf('function sendPrompt(')
    const promptBody = composer.slice(promptStart, promptStart + 600)
    assert.match(promptBody, /runBlocksSending\(runActivity\.value\)/, '外部 sendPrompt 同样要拦')
  })

  it('续订状态从壳层透传到输入条（刷新后续跑这一拍）', () => {
    assert.match(chatPanel, /:resuming="resuming"/, 'ChatPanel 应把 resuming 传给输入条')
    assert.match(workbench, /:can-stop="canStopRun"\s*\n\s*:resuming="resuming"/, '工作台应同时给出 can-stop 与 resuming')
  })
})
