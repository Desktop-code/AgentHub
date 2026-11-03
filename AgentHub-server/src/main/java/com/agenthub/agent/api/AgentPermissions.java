package com.agenthub.agent.api;

/**
 * Agent 权限码。所属域：agent。
 * 与菜单种子、接口注解、前端按钮使用同一套码，绑定不再拆细。
 */
public final class AgentPermissions {

    public static final String LIST = "agent:list";
    public static final String ADD = "agent:add";
    public static final String EDIT = "agent:edit";
    public static final String REMOVE = "agent:remove";
    public static final String ENABLE = "agent:enable";
    public static final String BIND = "agent:bind";

    /**
     * 多智能体列表（编排根视图）专用查看码。
     * <p>与 agent:list 分开，便于只给「看编排」的角色开放入口而不放开 Agent 配置页；
     * 菜单种子与接口注解共用本常量，禁止再铸 ma:def:* 码（本阶段无 ma_def 表）。
     */
    public static final String ROUTE_VIEW = "agent:route:view";

    private AgentPermissions() {
    }
}
