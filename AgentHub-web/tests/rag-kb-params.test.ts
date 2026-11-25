/**
 * 临时校验脚本：确认 ragKbParams 的解析 / 序列化 / 校验在 Node 下按预期工作。
 * 仅本地跑，不入库。
 */
import assert from 'node:assert/strict'
import {
  buildIngestConfig,
  buildRetrievalConfig,
  clampIngest,
  parseIngestConfig,
  parseRetrievalConfig,
  validateKbParams
} from '../src/utils/ragKbParams.ts'

// 1) 空配置 -> 默认值
const empty = parseRetrievalConfig(null)
assert.equal(empty.params.topK, 5)
assert.equal(empty.params.maxChunksPerDoc, 3)
assert.equal(empty.params.mergedTopK, null)
assert.equal(empty.params.rrfEnabled, true)
assert.equal(empty.params.rerankEnabled, false)
assert.equal(empty.params.rewriteEnabled, false)
assert.equal(empty.params.scoreThreshold, 0.5)

// 2) 老 JSON（缺 maxChunksPerDoc / mergedTopK / rrfEnabled）
const old = parseRetrievalConfig('{"topK":5,"vectorTopK":20,"bm25TopK":20,"rrfK":60,"rrfTopN":20,"scoreThreshold":0.5,"rewriteEnabled":false,"rerankEnabled":false}')
assert.equal(old.params.maxChunksPerDoc, 3, '缺 maxChunksPerDoc 应显示默认 3')
assert.equal(old.params.mergedTopK, null)
assert.equal(old.params.rrfEnabled, true, '缺 rrfEnabled 应显示默认开')
assert.equal(old.params.rewriteEnabled, false)

// 3) 未知键保留
const extra = parseRetrievalConfig('{"topK":8,"unknownKey":{"a":1},"rewriteEnabled":true}')
assert.deepEqual(extra.extra, { unknownKey: { a: 1 } })
const json = buildRetrievalConfig(extra.params, extra.extra)
assert.deepEqual(JSON.parse(json), {
  topK: 8,
  maxChunksPerDoc: 3,
  vectorTopK: 20,
  bm25TopK: 20,
  rrfK: 60,
  rrfTopN: 20,
  rrfEnabled: true,
  scoreThreshold: 0.5,
  rerankEnabled: false,
  rewriteEnabled: true,
  unknownKey: { a: 1 }
})

// 4) mergedTopK 为空不写键；有值则写
assert.ok(!('mergedTopK' in JSON.parse(buildRetrievalConfig(empty.params))))
assert.equal(JSON.parse(buildRetrievalConfig({ ...empty.params, mergedTopK: 12 })).mergedTopK, 12)

// 5) 越界回填被夹紧（与后端解析器一致）
const clamped = parseRetrievalConfig('{"topK":999,"vectorTopK":0,"rrfK":0,"maxChunksPerDoc":0,"mergedTopK":-3,"scoreThreshold":9}')
assert.equal(clamped.params.topK, 20)
assert.equal(clamped.params.vectorTopK, 1)
assert.equal(clamped.params.rrfK, 1)
assert.equal(clamped.params.maxChunksPerDoc, 3)
assert.equal(clamped.params.mergedTopK, null)
assert.equal(clamped.params.scoreThreshold, 1)

// 6) 入库：默认值与收紧
const ingest = parseIngestConfig('{"chunkSize":200,"chunkOverlap":300,"minChunkSize":500}')
assert.deepEqual(ingest.params, { chunkSize: 200, chunkOverlap: 100, minChunkSize: 200 })
assert.deepEqual(parseIngestConfig('').params, { chunkSize: 450, chunkOverlap: 100, minChunkSize: 150 })
assert.deepEqual(clampIngest({ chunkSize: 50, chunkOverlap: 80, minChunkSize: 999 }), { chunkSize: 100, chunkOverlap: 50, minChunkSize: 100 })
assert.equal(buildIngestConfig({ chunkSize: 800, chunkOverlap: 900, minChunkSize: 900 }), '{"chunkSize":800,"chunkOverlap":400,"minChunkSize":800}')

// 7) 校验
assert.deepEqual(validateKbParams(empty.params, parseIngestConfig('').params), [])
assert.equal(validateKbParams({ ...empty.params, topK: 21 }, parseIngestConfig('').params).length, 1)
assert.equal(validateKbParams(empty.params, { chunkSize: 450, chunkOverlap: 300, minChunkSize: 100 }).length, 1)
assert.equal(validateKbParams(empty.params, { chunkSize: 450, chunkOverlap: 100, minChunkSize: 500 }).length, 1)
assert.equal(validateKbParams({ ...empty.params, scoreThreshold: 2 }, parseIngestConfig('').params).length, 1)

// 8) 坏 JSON 不抛错
assert.equal(parseRetrievalConfig('not json').params.topK, 5)
assert.equal(parseRetrievalConfig('[1,2]').params.topK, 5)

console.log('[ok] ragKbParams 校验通过')
