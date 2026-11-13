package com.agenthub.runtime.api.dto;

import com.agenthub.agent.api.dto.AgentAvatarConfigDto;
import lombok.Data;

/**
 * 方案（另一套编排方案）内部的一个子候选，供工作台思考时间线解析头像。所属域：runtime（api）。
 *
 * <p>为什么需要它：入口把问题分给「方案」后，方案内部还会分给自己的子专家。这些子专家
 * 不在入口候选池里，前端拿不到它们的形象配置，时间线上只能退化成首字方座。
 * 本 DTO 只补展示所需的两件事：展示名 + 形象配置，不暴露 route_key、不改路由。
 *
 * <p>子候选仍可能不是 Agent（例如工作流）：此时 {@code avatarConfig} 为空，前端走兜底形象。
 */
@Data
public class WorkbenchChildOptionResp {

    /** 子候选展示名（名称 → 编码 → route_key 兜底），与时间线「路由到 …」一致 */
    private String agentName;

    /** AGENT / WORKFLOW；目标方案的下一层不会再有 MA（装载时已剔除） */
    private String targetType;

    /** Agent 形象配置；非 Agent 子候选为空 */
    private AgentAvatarConfigDto avatarConfig;
}
