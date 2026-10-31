/**
 * 模型防腐层（ACL）统一协议。所属域：runtime（模型出站）。
 * 职责：定义与厂商无关的内部标准类型（Unified*）与 Adapter / Gateway 契约。
 * 依据：docs/架构定稿-模型防腐层与统一SSE.md 第 3 章。
 * 约束：
 * 1. 本包只放接口与不可变数据约定，禁止出现任何厂商 SDK 的请求/响应 DTO；
 * 2. ReAct 循环、Trace、计费、前端 SSE 只认本包类型，厂商差异关在 Adapter 实现里；
 * 3. 本期仅骨架，无实现、不接线试跑（TrialRunAppService 等业务类未改动）；
 * 4. 流式依赖 Reactor 的 Flux（reactor-core 由现有依赖传递提供，compile 作用域）。
 * 命名说明：包名里的 model 指「模型出站协议」，与 com.agenthub.model 域（供应商/凭证/实例配置）不是同一概念。
 */
package com.agenthub.runtime.model.acl;
