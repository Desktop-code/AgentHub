package com.agenthub.agent.domain.model;

import lombok.Data;

/**
 * 多智能体方案列表条件。所属域：agent。
 *
 * <p>tenantId 由应用层强制写入；keyword 同时匹配 name / code；
 * hasRoot 为 true 只看绑根方案，false 只看虚拟根，null 不筛。
 */
@Data
public class MaDefQuery {

    private long current = 1;
    private long size = 10;
    private Long tenantId;
    private String keyword;
    /** 1 启用 / 0 停用；null 不筛 */
    private Integer enabled;
    /** 模板码；null 不筛 */
    private String template;
    /** true 只看有绑根；false 只看虚拟根；null 不筛 */
    private Boolean hasRoot;
}
