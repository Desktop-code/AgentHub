package com.agenthub.admin.domain.enums;

import java.util.Arrays;

/**
 * 角色数据范围。所属域：admin。
 * 数值与 sys_role.data_scope 的列注释一致，不要套用其他后台模板的编号。
 */
public enum DataScope {

    /** 只能看到自己的用户记录 */
    SELF(1, "仅本人"),
    /** 所属部门，不含下级 */
    DEPT(2, "本部门"),
    /** 所属部门 path 前缀下的全部部门 */
    DEPT_AND_CHILD(3, "本部门及以下"),
    /** 不做数据裁剪 */
    ALL(4, "全部"),
    /** 仅 sys_role_dept 中列出的部门 */
    CUSTOM(5, "自定义部门");

    private final int code;
    private final String label;

    DataScope(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * @return 入库的 tinyint
     */
    public int getCode() {
        return code;
    }

    /**
     * @return 给界面展示的名称
     */
    public String getLabel() {
        return label;
    }

    /**
     * @param code 库中的 data_scope
     * @return 对应枚举；无法识别时返回 null，由调用方决定是否拒绝
     */
    public static DataScope of(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values()).filter(item -> item.code == code).findFirst().orElse(null);
    }
}
