package com.agenthub.audit.domain;

/**
 * 控制台写操作日志。所属域：audit。
 * 调用方：admin 与 model 的应用服务，在业务成功之后调用。
 * 不要传入密码、Token、厂商 API Key、密文或主密钥。
 */
public interface OperLogWriter {

    /**
     * @param module     模块，如 user/role/menu/dept/app
     * @param action     create/update/delete/grant/resetPwd/enable/disable
     * @param targetType 资源类型
     * @param targetId   资源 id
     * @param summary    一句变更摘要，会放进 detail_json
     */
    void write(String module, String action, String targetType, String targetId, String summary);
}
