package com.agenthub.prompt.api;

/**
 * 提示词权限码。所属域：prompt。
 * 与 sql/data-init-prompt-menu.sql、接口注解、前端按钮同码。
 */
public final class PromptPermissions {

    public static final String L1_LIST = "prompt:l1:list";
    public static final String L1_EDIT = "prompt:l1:edit";
    public static final String L1_PUBLISH = "prompt:l1:publish";

    public static final String TEMPLATE_LIST = "prompt:template:list";
    public static final String TEMPLATE_ADD = "prompt:template:add";
    public static final String TEMPLATE_EDIT = "prompt:template:edit";
    public static final String TEMPLATE_PUBLISH = "prompt:template:publish";

    private PromptPermissions() {
    }
}
