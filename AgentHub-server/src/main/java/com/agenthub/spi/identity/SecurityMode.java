package com.agenthub.spi.identity;

/**
 * 部署形态里的安全模式。所属域：spi。
 * 一期只实现 LOCAL；另外两个值留给 cloud-sidecar，不要在业务里写企业协议。
 */
public enum SecurityMode {

    /** 本地用户名密码 + Sa-Token */
    LOCAL,
    /** 预留：对接客户 OAuth2/OIDC，本期无实现 */
    OAUTH2,
    /** 预留：网关已鉴权，平台只认透传身份，本期无实现 */
    PASSTHROUGH
}
