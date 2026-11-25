/**
 * 管理端布局与状态开关契约（本地 tests/，不进仓）。
 * 锁住本刀：画布高度、菜单树不再整表受控展开、系统管理表单去掉状态、筛选不换行、左栏搜索分行。
 */
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** 读前端源文件。 */
function readSrc(rel: string) {
  return readFileSync(join(WEB_ROOT, rel), 'utf8')
}

test('工作流画布按主区留白吃满高度，并在载入后把节点放到可视区域中部', () => {
  const page = readSrc('src/views/workflow/editor/index.vue')
  const layout = readSrc('src/views/layout/AdminLayout.vue')
  assert.equal(page.includes('100vh - 200px'), false)
  assert.match(page, /height:\s*calc\(100vh - 40px - var\(--ah-main-pad-y/)
  assert.match(page, /function placeNodesInView/)
  assert.match(layout, /--ah-main-pad-y:\s*36px/)
  assert.match(layout, /--ah-main-pad-y:\s*18px/)
})

test('菜单树懒加载且不再绑定 expand-row-keys', () => {
  const menu = readSrc('src/views/system/menu/index.vue')
  assert.equal(menu.includes(':expand-row-keys'), false)
  assert.equal(menu.includes('expand-row-keys='), false)
  assert.match(menu, /lazy/)
  assert.match(menu, /useLazyTreeTable/)
  assert.match(menu, /loadChildren/)
})

test('系统管理五个页用列表开关，表单里不再放状态单选', () => {
  const pages = [
    'src/views/system/user/index.vue',
    'src/views/system/role/index.vue',
    'src/views/system/menu/index.vue',
    'src/views/system/dept/index.vue',
    'src/views/system/app/index.vue'
  ]
  for (const rel of pages) {
    const text = readSrc(rel)
    assert.match(text, /StatusSwitch/, rel)
    assert.equal(text.includes('label="状态"'), true, rel)
    assert.equal(/<el-radio[^>]*>停用<\/el-radio>/.test(text), false, rel)
    assert.match(text, /status:\s*1/, rel)
  }
  const user = readSrc('src/views/system/user/index.vue')
  assert.match(user, /label="状态"/)
  assert.match(user, /placeholder="全部"/)
})

test('工具项筛选保持同一行，左栏搜索与视图切换分行', () => {
  const tool = readSrc('src/views/tool/index.vue')
  const css = readSrc('src/styles/index.css')
  const rail = readSrc('src/styles/browse-workspace.css')
  assert.match(tool, /filter-bar filter-bar--keep-row/)
  assert.match(css, /filter-bar--keep-row \.filter-form[\s\S]*flex-wrap:\s*nowrap/)
  assert.match(rail, /flex-direction:\s*column/)
  assert.match(rail, /\.page \.rail-toolbar/)
  assert.match(rail, /width:\s*100%/)
})
