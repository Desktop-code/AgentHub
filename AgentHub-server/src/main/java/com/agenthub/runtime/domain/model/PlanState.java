package com.agenthub.runtime.domain.model;

/**
 * 计划状态。所属域：runtime。写在 PLAN 事件正文的 JSON 里，也出现在计划查询接口。
 * 状态只增不改行：一旦落库不再更新该事件，后续进度靠 STEP / SYSTEM / ASSISTANT 事件体现。
 */
public enum PlanState {

    /** 计划已生成，等待用户在试跑页批准（仅 require_plan_confirm=true） */
    PENDING,
    /** 计划已生成，直接进入执行（自动执行模式，或已被批准） */
    EXECUTING,
    /** 用户取消了计划，本步没有执行任何步骤 */
    REJECTED,
    /** 全部步骤执行完成，已给出终答 */
    DONE,
    /** 执行中途失败，整轮失败并附带已完成摘要 */
    FAILED
}
