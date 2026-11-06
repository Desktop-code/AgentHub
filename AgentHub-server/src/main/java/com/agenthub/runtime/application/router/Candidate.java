package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;

/**
 * 一个可执行的子候选：路由行 + 子 Agent 定义。所属域：runtime。
 *
 * <p>由候选装载（{@code loadCandidates}）产出，参与判路由、固定模板、竞争选优等所有模板；
 * 抽到独立类型只是为了让模板执行器分担到同包协作类，字段与语义未变。
 *
 * @param route 子路由行（含 route_key / intent / 排序）
 * @param agent 子 Agent 定义（启用状态与运行模式已校验）
 */
public record Candidate(AgentRoute route, AgentDef agent) {
}
