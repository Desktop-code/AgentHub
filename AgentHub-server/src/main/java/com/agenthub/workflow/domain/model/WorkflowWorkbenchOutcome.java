package com.agenthub.workflow.domain.model;

/**
 * 工作台触发一次工作流的结果。所属域：workflow 的 domain。
 *
 * <p>{@code reused=true} 表示本次<b>没有</b>新建 run：同一会话里这个工作流上一轮仍是
 * RUNNING / PAUSED_HUMAN，按防重规则直接复用那条 run 的状态，由调用方给出
 * 「仍在进行 / 等待人工」提示。终态 run 不阻止重新发起。
 *
 * @param run    当前 run（新发起的，或复用的那条；状态以库里读回的为准）
 * @param reused true 表示命中了未结束 run，本次未新建
 */
public record WorkflowWorkbenchOutcome(WorkflowRun run, boolean reused) {
}
