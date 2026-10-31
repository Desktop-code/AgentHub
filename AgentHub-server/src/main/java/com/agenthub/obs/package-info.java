/**
 * 调用追踪。所属域：obs。
 * 与 agent_run_session / agent_run_event、billing_* 分表。对话真源仍在会话和事件；
 * 同一次试跑可以同时写 BillingMeter 和 TraceRecorder，这里不计价、不写 token 账。
 * 已知债：编排若无会话，session_id 是否可空以后再议；与 billing 无共用 turn_id；
 * 与 event 双写允许短暂不一致；title 的 LIKE 量大后再考虑全文索引。本期不改表。
 */
package com.agenthub.obs;
