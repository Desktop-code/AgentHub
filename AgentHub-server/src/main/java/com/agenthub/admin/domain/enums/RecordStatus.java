package com.agenthub.admin.domain.enums;

/**
 * 通用启停。所属域：admin。1 正常，0 停用，与各表 status 列一致。
 */
public enum RecordStatus {

    /** 停用，不能登录；角色停用后不参与鉴权和数据范围 */
    DISABLED(0),
    /** 正常 */
    ENABLED(1);

    private final int code;

    RecordStatus(int code) {
        this.code = code;
    }

    /**
     * @return 入库值
     */
    public int getCode() {
        return code;
    }

    /**
     * @param code 库中的 status
     * @return 非 1 都视为停用，避免脏数据被当成启用
     */
    public static boolean isEnabled(Integer code) {
        return code != null && code == ENABLED.code;
    }
}
