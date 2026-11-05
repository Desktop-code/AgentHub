package com.agenthub.agent.api.dto;

import com.agenthub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 多智能体方案分页请求。所属域：agent。
 *
 * <p>keyword 同时匹配 name / code；hasRoot=true 只看绑根方案，false 只看虚拟根。
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
    /** true=有绑根；false=虚拟根；null 不筛 */
    private Boolean hasRoot;
}
