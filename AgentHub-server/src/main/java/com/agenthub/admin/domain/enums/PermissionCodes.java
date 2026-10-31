package com.agenthub.admin.domain.enums;

/**
 * 权限码。所属域：admin。
 * 与 sql/data-init.sql 的菜单种子、接口注解、前端按钮使用同一套字符串。
 */
public final class PermissionCodes {

    public static final String USER_LIST = "system:user:list";
    public static final String USER_ADD = "system:user:add";
    public static final String USER_EDIT = "system:user:edit";
    public static final String USER_REMOVE = "system:user:remove";
    public static final String USER_RESET_PWD = "system:user:resetPwd";
    public static final String USER_ROLE = "system:user:role";

    public static final String ROLE_LIST = "system:role:list";
    public static final String ROLE_ADD = "system:role:add";
    public static final String ROLE_EDIT = "system:role:edit";
    public static final String ROLE_REMOVE = "system:role:remove";
    public static final String ROLE_MENU = "system:role:menu";

    public static final String MENU_LIST = "system:menu:list";
    public static final String MENU_ADD = "system:menu:add";
    public static final String MENU_EDIT = "system:menu:edit";
    public static final String MENU_REMOVE = "system:menu:remove";

    public static final String DEPT_LIST = "system:dept:list";
    public static final String DEPT_ADD = "system:dept:add";
    public static final String DEPT_EDIT = "system:dept:edit";
    public static final String DEPT_REMOVE = "system:dept:remove";

    public static final String APP_LIST = "system:app:list";
    public static final String APP_ADD = "system:app:add";
    public static final String APP_EDIT = "system:app:edit";
    public static final String APP_REMOVE = "system:app:remove";

    public static final String LOG_LOGIN = "system:log:login";
    public static final String LOG_OPER = "system:log:oper";

    /** 种子数据和「最后一个管理员」判断都认这个角色编码 */
    public static final String ADMIN_ROLE = "admin";

    private PermissionCodes() {
    }
}
