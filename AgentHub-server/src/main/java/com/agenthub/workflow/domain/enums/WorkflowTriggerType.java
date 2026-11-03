package com.agenthub.workflow.domain.enums;

/**
 * 运行触发类型。所属域：workflow 的 domain。
 * <p>WF-P0 只产出 TRIAL；WF-P1 刀1 增加 APP（sys_app 入站 OpenAPI）。
 * <p>APP 只认「已发布 release 快照」，草稿不可被应用触发；TRIAL 仍在控制台按用户 Bearer 触发。
 */
public enum WorkflowTriggerType {

    /** 编辑页 / 控制台试跑（平台用户 Bearer） */
    TRIAL,
    /** 应用入站 OpenAPI（app_key + secret），语义等同生产触发 */
    APP,
    /** 平台用户 Bearer 触发（保留：本刀不暴露入口） */
    API,
    /** 人工手工重跑（保留：本刀不暴露入口） */
    MANUAL
}
