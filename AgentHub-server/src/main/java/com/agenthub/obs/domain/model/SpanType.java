package com.agenthub.obs.domain.model;

/**
 * 步骤类型。所属域：obs。
 * Router 用 ROUTE → AGENT → LLM/TOOL 组成父子树；
 * Plan-and-Execute 用 AGENT →（PLAN ∥ 各步 AGENT）→ LLM/TOOL，PLAN 与每步互不为父子，都是串行兄弟。
 * TASK 只留给以后的编排节点。
 */
public enum SpanType {

    /** Router 的 judge 或路由决策 */
    ROUTE,
    /** 进入某个子 Agent，或纯 ReAct / 计划执行的主 Agent 根 */
    AGENT,
    /** 计划执行模式的规划步。其下挂一次不含工具的 LLM 调用 */
    PLAN,
    /** 一次模型调用 */
    LLM,
    /** 一次工具执行 */
    TOOL,
    /** 检索。本期试跑没有单独检索步时不会写入 */
    RETRIEVE,
    /** 其它系统步 */
    SYSTEM,
    /** 预留编排节点。一期对话埋点禁止写入 */
    TASK
}
