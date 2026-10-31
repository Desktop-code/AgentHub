package com.agenthub.agent.api.dto;

import lombok.Data;

/**
 * 各运行模式的平台前缀。所属域：agent。只给配置页只读展示，不接受回写。
 * PLAN_AND_EXECUTE 不加额外前缀句，与 REACT 同为基础约束，故 plan 与 react 内容相同，
 * 仍单独给字段，避免前端切模式时还要做字符串比较。
 */
@Data
public class PlatformPrefixResp {

    /** REACT 前缀 */
    private String react;
    /** ROUTER 前缀，在基础约束后多一句路由说明 */
    private String router;
    /** PLAN_AND_EXECUTE 前缀，与 REACT 相同（基础约束） */
    private String plan;
}
