package com.agenthub.workflow.domain.enums;

/**
 * 运行触发类型。所属域：workflow 的 domain。
 * <p>WF-P0 只产出 TRIAL；WF-P1 刀1 增加 APP（sys_app 入站 OpenAPI）；刀5B 增加 WORKBENCH。
 * <p>APP / WORKBENCH 都只认「已发布 release 快照」，草稿不可被触发；TRIAL 仍在控制台按用户 Bearer 触发。
 * <p>WORKBENCH 与 APP 的区别：前者是平台用户在工作台会话里经判路由命中，权限口径随
 * {@code runtime:chat:use}，不是应用入站 OpenAPI，也不写 app_id / client_request_id。
 */
public enum WorkflowTriggerType {

    /** 编辑页 / 控制台试跑（平台用户 Bearer） */
    TRIAL,
    /** 应用入站 OpenAPI（app_key + secret），语义等同生产触发 */
    APP,
    /** 平台用户 Bearer 触发（保留：本刀不暴露入口） */
    API,
    /** 人工手工重跑（保留：本刀不暴露入口） */
    MANUAL,
    /**
     * 工作台默认接待路由命中（刀5B）：平台用户在工作台会话里触发，
     * 只绑默认发布指针，结果投影回同一条 {@code entry_channel=WORKBENCH} 会话。
     */
    WORKBENCH
}
