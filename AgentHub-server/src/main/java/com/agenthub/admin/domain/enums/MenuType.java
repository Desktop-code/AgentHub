package com.agenthub.admin.domain.enums;

/**
 * 菜单节点类型。所属域：admin。与 sys_menu.type 一致。
 */
public enum MenuType {

    /** 侧栏目录，可折叠，没有页面 */
    DIRECTORY(1),
    /** 可打开的页面 */
    MENU(2),
    /** 按钮权限码，不进导航树 */
    BUTTON(3),
    /** 侧栏纯文本分组标题：不折叠、不进路由，子菜单直接平铺 */
    DIRECTORY_FLAT(4);

    private final int code;

    MenuType(int code) {
        this.code = code;
    }

    /**
     * @return 入库值
     */
    public int getCode() {
        return code;
    }

    /**
     * 是否为合法入库类型码。
     *
     * @param code sys_menu.type
     * @return true 表示可保存
     */
    public static boolean isValid(Integer code) {
        if (code == null) {
            return false;
        }
        for (MenuType type : values()) {
            if (type.code == code) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否为侧栏分组（可折叠目录或无折叠标题），本身不绑定页面。
     *
     * @param code sys_menu.type
     * @return true 表示目录类节点
     */
    public static boolean isDirectoryKind(Integer code) {
        return code != null && (code == DIRECTORY.code || code == DIRECTORY_FLAT.code);
    }
}
