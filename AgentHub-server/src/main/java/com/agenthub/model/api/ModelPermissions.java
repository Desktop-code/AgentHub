package com.agenthub.model.api;

/**
 * 模型管理权限码。所属域：model。
 * 与 sql/data-init-model-menu.sql、接口注解、前端按钮使用同一套字符串。
 */
public final class ModelPermissions {

    public static final String PROVIDER_LIST = "model:provider:list";
    public static final String PROVIDER_ADD = "model:provider:add";
    public static final String PROVIDER_EDIT = "model:provider:edit";
    public static final String PROVIDER_REMOVE = "model:provider:remove";
    public static final String PROVIDER_RESET_KEY = "model:provider:resetKey";

    public static final String INSTANCE_LIST = "model:instance:list";
    public static final String INSTANCE_ADD = "model:instance:add";
    public static final String INSTANCE_EDIT = "model:instance:edit";
    public static final String INSTANCE_REMOVE = "model:instance:remove";
    public static final String INSTANCE_TEST = "model:instance:test";
    /** 设置 / 取消 / 换绑平台级实例；影响记忆与检索全局，权限单独收口 */
    public static final String INSTANCE_PLATFORM = "model:instance:platform";

    private ModelPermissions() {
    }
}
