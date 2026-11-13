package com.agenthub.agent.domain.model;

import lombok.Data;

/**
 * 多智能体方案列表条件。所属域：agent。
 *
 * <p>tenantId 由应用层强制写入；keyword 同时匹配 name / code。
 * 方案已与 Agent 根断开：不再提供「绑根 / 虚拟根」筛选，历史列不参与查询条件。
 */
@Data
public class MaDefQuery {

    private long current = 1;
    private long size = 10;
    private Long tenantId;
    private String keyword;
    /** 1 启用 / 0 停用；null 不筛 */
    private Integer enabled;
    /** 模板码；null 不筛。入口 {@code ENTRY_ROUTER} 可显式筛选查看 */
    private String template;
}
