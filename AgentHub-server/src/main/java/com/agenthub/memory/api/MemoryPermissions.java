package com.agenthub.memory.api;

/**
 * 记忆权限码。所属域：memory。
 * 与 sql/data-init-memory-menu.sql、接口注解、前端按钮使用同一套码。不另写菜单脚本。
 */
public final class MemoryPermissions {

    public static final String ITEM_LIST = "memory:item:list";
    public static final String ITEM_ADD = "memory:item:add";
    public static final String ITEM_EDIT = "memory:item:edit";
    public static final String ITEM_REMOVE = "memory:item:remove";
    public static final String ITEM_ENABLE = "memory:item:enable";
    public static final String POLICY_LIST = "memory:policy:list";
    public static final String POLICY_EDIT = "memory:policy:edit";

    private MemoryPermissions() {
    }
}
