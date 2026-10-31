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

    private AgentPermissions() {
    }
}
