package com.agenthub.admin.domain.enums;

/**
 * 登录方式。所属域：admin。一期只写密码登录。
 */
public enum LoginType {

    /** 用户名密码 */
    PASSWORD(1),
    /** 预留 SSO，本期不产生这种日志 */
    SSO(2),
    /** 预留调用方 apiKey，本期不产生这种日志 */
    API_KEY(3);

    private final int code;

    LoginType(int code) {
        this.code = code;
    }

    /**
     * @return 写入 sys_login_log.login_type 的值
     */
    public int getCode() {
        return code;
    }
}
