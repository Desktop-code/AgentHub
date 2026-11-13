package com.agenthub.agent.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 多智能体方案分页请求。所属域：agent。
 *
 * <p>keyword 同时匹配 name / code。方案已与 Agent 根断开，不再接收「绑根」筛选参数。
 * 分页沿用全站 current / size。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MaDefPageReq extends PageQuery {

    private String keyword;
    /** 1 启用 / 0 停用；null 不筛 */
    private Integer enabled;
    /** 五值模板之一；null 不筛 */
    private String template;
}
