package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多智能体编排方案定义。所属域：agent。对应 ma_def。
 *
 * <p>本刀（MA-P2 刀1）把「编排策略 + 候选池」从 ROUTER Agent 的运行时配置里抽成独立方案：
 * <ul>
 *   <li>{@code rootAgentId} 非空＝绑定一个 ROUTER 编排根，试跑仍走现网 Router 执行器；</li>
 *   <li>{@code rootAgentId} 为空＝虚拟根，本刀只允许建档与列表展示，<b>禁止试跑</b>（刀2 才执行）。</li>
 * </ul>
 *
 * <p>{@code configJson} 与 {@link AgentRuntimeConfig} 同构（maTemplate / maContextPolicy /
 * answerMode / maxRouteChildren / 子模式 / 评审环等），方便与绑定根双向镜像，避免两套字段漂移。
 */
@Data
public class MaDef {

    private Long id;
    /** 租户；一期由配置提供，列表与写操作全部按此隔离 */
    private Long tenantId;
    /** 租户内唯一方案编码 */
    private String code;
    /** 展示名 */
    private String name;
    /**
     * 五值模板之一：ROUTER_PARALLEL / FIXED_SEQUENCE / FIXED_PARALLEL / CRITIC_LOOP /
     * ROUTER_THEN_SEQUENCE。写库前由 {@link com.agenthub.agent.domain.service.MaDefPolicy} 校验。
     */
    private String template;
    /** 与 AgentRuntimeConfig 同构的编排配置 JSON */
    private String configJson;
    /** 绑定的 ROUTER 编排根；空＝虚拟根（本刀不可试跑） */
    private Long rootAgentId;
    /** 1 启用 / 0 停用；同一 rootAgentId 最多一条启用中方案 */
    private Integer enabled;
    /** 方案版本：每次保存 +1，供后续乐观锁与审计使用（本刀不做 CAS） */
    private Integer version;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;
}
