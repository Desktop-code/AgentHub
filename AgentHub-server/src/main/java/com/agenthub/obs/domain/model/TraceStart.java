package com.agenthub.obs.domain.model;

/**
 * 打开一条控制台 trace 需要的身份和问题。所属域：obs。
 *
 * @param sessionId 试跑会话，对话场景必填
 * @param agentId   开跑 Agent。Router 时是路由 Agent，可空
 * @param userId    平台用户，权限按这个字段裁剪
 * @param title     用户问题原文，入库前只打码，不截断
 */
public record TraceStart(Long sessionId, Long agentId, Long userId, String title) {
}
