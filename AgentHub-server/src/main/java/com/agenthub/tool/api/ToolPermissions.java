package com.agenthub.tool.api;

/**
 * 工具目录权限码。所属域：tool。
 * 与 sql/data-init-tool-menu.sql、接口注解、前端按钮使用同一套字符串。
 */
public final class ToolPermissions {

    public static final String SOURCE_LIST = "tool:source:list";
    public static final String SOURCE_ADD = "tool:source:add";
    public static final String SOURCE_EDIT = "tool:source:edit";
    public static final String SOURCE_REMOVE = "tool:source:remove";
    public static final String SOURCE_ENABLE = "tool:source:enable";
    public static final String SOURCE_SYNC = "tool:source:sync";

    public static final String ITEM_LIST = "tool:item:list";
    public static final String ITEM_ADD = "tool:item:add";
    public static final String ITEM_EDIT = "tool:item:edit";
    public static final String ITEM_REMOVE = "tool:item:remove";
    public static final String ITEM_ENABLE = "tool:item:enable";
    /** 菜单种子保留。本期同步接口和页面按钮只使用 {@link #SOURCE_SYNC}。 */
    public static final String ITEM_SYNC = "tool:item:sync";

    private ToolPermissions() {
    }
}
