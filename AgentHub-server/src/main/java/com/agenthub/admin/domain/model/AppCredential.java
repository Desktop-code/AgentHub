package com.agenthub.admin.domain.model;

import lombok.Data;

/**
 * 应用密钥校验视图。所属域：admin。
 * <p>为什么单独建模：{@link App} 刻意不带 any 密钥字段（列表/详情不出哈希），
 * 只有入站鉴权这一条路径需要 {@code app_secret_hash}，用独立模型把「谁能读到哈希」收窄到一次查询。
 * <p>约束：本对象只在鉴权服务内存活，禁止放进响应 DTO、日志或操作明细。
 */
@Data
public class AppCredential {

    /** sys_app.id */
    private Long appId;
    /** 对外 appId */
    private String appKey;
    /** 应用名，仅用于鉴权通过后回填身份上下文 */
    private String name;
    /** 归属部门，透传给应用身份 */
    private Long deptId;
    /** 1 启用 / 0 停用 */
    private Integer status;
    /** BCrypt 哈希；只用于 {@code PasswordHasher#matches}，禁止外传 */
    private String secretHash;
}
