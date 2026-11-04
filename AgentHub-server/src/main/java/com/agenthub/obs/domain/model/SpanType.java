package com.agenthub.obs.domain.model;

/**
 * 步骤类型。所属域：obs。
 * Router 用 ROUTE → AGENT → LLM/TOOL 组成父子树；
 * Plan-and-Execute 用 AGENT →（PLAN ∥ 各步 AGENT）→ LLM/TOOL，PLAN 与每步互不为父子，都是串行兄弟。
 * TASK 只留给编排节点（工作流 run 的每个节点），对话埋点不写。
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
    /**
     * 一次视觉模型调用（图片文字提取 / OCR）。
     * 为什么单列而不复用 LLM：它发生在主对话之前，耗时与 token 要能和「对话用了几次模型」分开看；
     * 复用 LLM 会让 trace 里的「轮数」把 OCR 也算成一轮。
     */
    VISION,
    /** 检索。本期试跑没有单独检索步时不会写入 */
    RETRIEVE,
    /** 其它系统步 */
    SYSTEM,
    /**
     * 编排节点（工作流 run 的每个节点一步）。
     * 对话埋点不写；工作流埋点写入，节点内部不再强行编造父子层。
     */
    TASK
}
