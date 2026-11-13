package com.agenthub.agent.domain.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多智能体编排方案定义。所属域：agent。对应 ma_def。
 *
 * <p><b>方案就是自己的根</b>（本刀）：模型槽、ROUTER 提示词、候选池、形象全部挂在方案上，
 * 不再要求绑定一个 ROUTER Agent 才能运行，也不再为「空根」在内存里合成 Agent 壳。
 * {@code rootAgentId} 列保留（历史数据可能仍有值），但运行与形象都不再读写它：
 * 页面保存时写入 null，旧值随下一次保存自然清空，不强制用户先手清。
 *
 * <p>{@code configJson} 与 {@link AgentRuntimeConfig} 同构（maTemplate / maContextPolicy /
 * answerMode / maxRouteChildren / 子模式 / 评审环等），判路由模型、模板引用与追加要求都在这份配置里。
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
     * 方案说明（刀5）：被别的方案当作路由目标时，这段文案就是判路由提示词里的「职责简介」。
     *
     * <p>与 {@code agent_def.description} 同语义，是 MA 候选意图说明的唯一真源；
     * 不在 {@code ma_candidate} 上再编一份。允许为空（普通方案不需要），
     * 但空说明的方案不能被加入任何路由池。
     */
    private String description;
    /**
     * 方案自有形象 JSON（与 {@code agent_def.avatar_config} 同构）：
     * {@code preset{icon,color}} 或 {@code custom{url}}；空＝未配置，前端按方案名走 8 套兜底。
     *
     * <p>与单兵形象彻底分开：改某个 Agent 的形象不会影响任何方案块。
     */
    private String avatarConfigJson;
    /**
     * 五值模板之一：ROUTER_PARALLEL / FIXED_SEQUENCE / FIXED_PARALLEL / CRITIC_LOOP /
     * ROUTER_THEN_SEQUENCE。写库前由 {@link com.agenthub.agent.domain.service.MaDefPolicy} 校验。
     */
    private String template;
    /** 与 AgentRuntimeConfig 同构的编排配置 JSON */
    private String configJson;
    /**
     * 历史绑根列：<b>已停用</b>。方案以自己为根，运行 / 形象都不读它；
     * 列先留着（不 DROP），页面保存时写 null，旧值随保存自然清空。
     */
    private Long rootAgentId;
    /** 1 启用 / 0 停用；不再按 rootAgentId 做同根唯一约束 */
    private Integer enabled;
    /**
     * 1=平台默认接待方案，0=普通方案。每租户最多一条为 1，
     * 由唯一索引 {@code uk_ma_def_reception (tenant_id, reception_slot)} 在库层兜住。
     */
    private Integer isReceptionDefault;
    /** 方案版本：每次保存 +1，供后续乐观锁与审计使用（本刀不做 CAS） */
    private Integer version;
    private Long createdBy;
    private LocalDateTime createTime;
    private Long updatedBy;
    private LocalDateTime updateTime;

    /**
     * 对外展示名：入口行固定「Router」，其余用库里的名称。
     *
     * <p>为什么不在各页面各写一次三元判断：列表、详情、默认接待状态行、工作台会话头都会回显方案名，
     * 口径必须唯一。入口是平台唯一网关，历史库里可能还叫「默认接待」，这里统一收敛，
     * 避免「同一条方案在四个地方四个叫法」。
     *
     * @return 展示名；普通方案原样返回
     */
    public String displayName() {
        return AgentRuntimeConfig.isEntryRouterTemplate(template)
                ? AgentRuntimeConfig.ENTRY_ROUTER_NAME
                : name;
    }

    /**
     * 对外展示说明（备注）：入口行固定为平台文案，其余用库里的描述。
     *
     * <p>入口备注写的是「唯一入口 + 哪些动作被禁止」，属于身份字段，不允许被改写；
     * 与 {@link #displayName()} 同源，保证页面回显与保存校验看到的是同一份文本。
     *
     * @return 说明；普通方案原样返回
     */
    public String displayDescription() {
        return AgentRuntimeConfig.isEntryRouterTemplate(template)
                ? AgentRuntimeConfig.ENTRY_ROUTER_DESCRIPTION
                : description;
    }
}
