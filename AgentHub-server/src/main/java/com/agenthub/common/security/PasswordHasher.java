package com.agenthub.common.security;

/**
 * 密码哈希。所属域：common。
 * <p>约束：只处理不可逆哈希，调用方不得把明文写入日志或操作明细。
 * 实现使用 BCrypt，放在 auth，避免 admin 依赖具体算法库。
 * 平台用户密码和 sys_app 调用方密钥只走本接口，库中不能还原明文。
 * 模型供应商 API Key 不走哈希，必须用 spi.crypto.SecretStore 做 AES-GCM，以便出站调用。
 */
public interface PasswordHasher {

    /**
     * @param raw 明文密码，不为空
     * @return BCrypt 哈希，可直接存 password_hash / app_secret_hash
     */
    String hash(String raw);

    /**
     * @param raw  用户提交的明文
     * @param hash 库中的哈希
     * @return 匹配时为 true；哈希损坏时为 false，不抛异常
     */
    boolean matches(String raw, String hash);
}
