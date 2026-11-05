/**
 * 前端本地测试入口：有本地用例才跑，没有就友好跳过。所属域：agent（前端测试工具）。
 *
 * 背景：本仓约定「测试 / 自测源码不进仓」，`AgentHub-web/tests/` 已被 .gitignore 排除，
 * 干净克隆里 `tests/*.test.ts` 必然不存在。若直接把 glob 交给 `node --test`，
 * 找不到文件会以非零码退出，导致 `npm test` 在「无测试」场景也判红。
 * 本脚本把这条边界收口：有文件才执行，无文件输出一句提示并 exit 0。
 *
 * 运行（在 AgentHub-web 目录）：npm test
 *
 * 注意：本文件是**工具**而非测试用例，必须入库；勿命名成 *.test.ts / *.guard.mjs / *.selftest.ts，
 * 否则会被 .gitignore 的测试规则一起忽略。
 */
import { existsSync, readdirSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** 前端工程根目录（scripts 的上一级），固定在此避免依赖调用时的工作目录 */
const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
/** 本地用例目录（不随仓库分发） */
const TESTS_DIR = join(WEB_ROOT, 'tests')

/**
 * 收集本地用例的绝对路径。
 * 目录不存在（干净克隆的常态）或没有 *.test.ts 时返回空数组，不抛错。
 *
 * @return {string[]} 待执行的用例文件绝对路径
 */
function collectTestFiles() {
  if (!existsSync(TESTS_DIR)) return []
  return readdirSync(TESTS_DIR)
    .filter((name) => name.endsWith('.test.ts'))
    .sort()
    .map((name) => join(TESTS_DIR, name))
}

/**
 * 判断当前 Node 是否支持 `--experimental-strip-types`（Node >= 22.6 才有）。
 * 用例是 .ts，老版本 Node 直接跑会在解析阶段失败，提前给出可读提示。
 *
 * @return {boolean} true 表示支持 TS 类型剥离
 */
function supportsStripTypes() {
  const [major, minor] = process.versions.node.split('.').map(Number)
  if (major > 22) return true
  if (major === 22) return minor >= 6
  return false
}

const files = collectTestFiles()

// 无本地用例：这是干净克隆的正常状态，不算失败，直接跳过
if (files.length === 0) {
  console.log('[test] 未发现本地用例（tests/ 不随仓库分发），跳过；退出码 0。')
  process.exit(0)
}

// 有本地用例但 Node 太老：明确报错而不是静默跳过，避免把「没跑」误判成「通过」
if (!supportsStripTypes()) {
  console.error(
    `[test] 当前 Node ${process.versions.node} 不支持 --experimental-strip-types，` +
      '无法直接执行 .ts 用例；请升级到 Node 22.6+ 后重试。'
  )
  process.exit(1)
}

console.log(`[test] 发现 ${files.length} 个本地用例，开始执行……`)

// 用例数量有限，逐个路径传入即可；stdio 继承让输出直连终端
const result = spawnSync(
  process.execPath,
  ['--experimental-strip-types', '--test', ...files],
  { stdio: 'inherit', cwd: WEB_ROOT }
)

// spawn 本身失败（例如 node 不可执行）时 result.status 为 null，必须显式判成失败
if (result.error) {
  console.error(`[test] 启动测试进程失败：${result.error.message}`)
  process.exit(1)
}
process.exit(result.status ?? 1)
