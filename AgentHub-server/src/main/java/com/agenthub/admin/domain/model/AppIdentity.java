package com.agenthub.admin.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入站应用身份。所属域：admin。
 * <p>由 {@code AppKeyAuthService} 在 app_key + secret 校验通过后构造，
 * 通过 request attribute 传给 OpenAPI 控制器；不写 ThreadLocal，避免线程池串身份。
 * <p>约束：只带非敏感标识（appId / appKey / 部门），<b>不含</b> secret 或哈希。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AppIdentity {

    /** sys_app.id，写 run.app_id 与授权校验用 */
    private Long appId;
    /** 对外 appId，可脱敏展示 / 记日志 */
    private String appKey;
    /** 应用名，仅供文案 */
    private String name;
    /** 归属部门，预留给后续数据范围；本刀不做业务行级权限 */
    private Long deptId;
}
