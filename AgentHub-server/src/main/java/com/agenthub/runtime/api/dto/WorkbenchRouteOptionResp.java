package com.agenthub.runtime.api.dto;

import com.agenthub.agent.api.dto.AgentAvatarConfigDto;
import lombok.Data;

import java.util.List;

/**
 * 工作台换线下拉里的一个候选：子 Agent / 另一套编排方案 / 已发布工作流。所属域：runtime（api）。
 *
 * <p>只暴露展示所需的字段：候选展示名 + 意图说明 + 排序。
 * {@code routeKey} 用于提交换线请求；前端不得把它直接渲染给用户。
 * {@code targetType=MA} 时 {@code agentId} 为空、{@code maDefId} 有值；
 * {@code targetType=WORKFLOW}（刀5B）时 {@code workflowDefId} 有值；
 * 两者的展示名都在 {@code agentName}（目标名称），前端据此打「方案」/「工作流」标签。
 */
@Data
public class WorkbenchRouteOptionResp {

    /** 方案候选的 route_key；换线请求按它定位候选 */
    private String routeKey;
    /** AGENT / MA / WORKFLOW */
    private String targetType;
    /** 子 Agent 主键；非 Agent 候选为空 */
    private Long agentId;
    /** targetType=MA 时的目标方案主键 */
    private Long maDefId;
    /** targetType=WORKFLOW 时的目标工作流主键 */
    private Long workflowDefId;
    /** 候选展示名（子 Agent / 方案 / 工作流 名称 → 编码 → 兜底） */
    private String agentName;
    /** Agent 形象配置；MA/工作流候选为空 */
    private AgentAvatarConfigDto avatarConfig;
    /** 该候选负责的意图说明；可空 */
    private String intentDesc;
    /** 方案内排序，小的在前 */
    private Integer sortNo;
    /**
     * 方案内部的子候选（仅 {@code targetType=MA} 可能有值）：展示名 + 形象配置。
     *
     * <p>只用于思考时间线给「方案 → 各子专家」的块解析头像；入口候选池不因它扩张。
     */
    private List<WorkbenchChildOptionResp> children = List.of();
}
