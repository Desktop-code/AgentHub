/**
 * 试跑 / 会话的 SSE 出站层。所属域：runtime。
 * 职责：把内部 {@code UnifiedStreamEvent} 编成 SSE 帧写出，并强制保证「一次流必有且仅有一帧结束」。
 * 依据：docs/架构定稿-模型防腐层与统一SSE.md §4。
 * 约束：
 * 1. done 兜底只在出站层做一次（CAS），Adapter 层的 done 视为上游事件，不与之重复；
 * 2. 旧前端字段（assistant / reasoning / tool / done / error）继续下发，新字段以 Unified 为准；
 * 3. 传输仍是 Servlet 同步 SSE，不引入第二套响应式 Web 栈；
 * 4. 帧体一律 JSON，禁止把未脱敏的正文、密钥或堆栈写进帧。
 */
package com.agenthub.runtime.api.sse;
