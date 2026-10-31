package com.agenthub.spi.context;

import lombok.Builder;
import lombok.Data;

/**
 * 终端用户透传上下文。所属域：spi。
 * 一期只定义字段，不参与平台 RBAC，也不做业务行级权限。
 */
@Data
@Builder
public class AgentContext {

    /** 租户标识，由调用方传入 */
    private String tenant;
    /** 终端用户标识，不是平台 sys_user.id */
    private String user;
    /** 调用方声明的范围，平台不解释其业务含义 */
    private String scope;
}
