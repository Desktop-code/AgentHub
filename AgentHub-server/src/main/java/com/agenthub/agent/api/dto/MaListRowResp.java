package com.agenthub.agent.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多智能体列表行。所属域：agent。
 *
 * <p>一行 = 一个编排根（{@code run_mode=ROUTER} 的 Agent 定义）+ 它的模板与候选数。
 * 不新增 ma_def 实体：本刀只是把现网 ROUTER Agent 的「编排视图」列出来，
 * 点击仍回既有 Agent 详情「编排」Tab 编辑。
 */
@Data
public class MaListRowResp {

    /** Agent 主键，前端据此跳 /agent/list/{id}?tab=orchestration */
    private Long id;

    /** 名称 */
    private String name;

    /** 编码，便于与 Agent 管理对照 */
    private String code;

    /** 启停；1 正常，0 停用 */
    private Integer status;

    /**
     * 编排模板：ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL。
     * <p>由 runtime_config_json 解析；空/非法已回落 ROUTER_PARALLEL，列表不展示空值。
     */
    private String maTemplate;

    /** 启用中的候选子 Agent 数（agent_route.status=1 且未删） */
    private Integer candidateCount;

    /** 定义最后更新时间，供列表按时间观察变更 */
    private LocalDateTime updateTime;
}
