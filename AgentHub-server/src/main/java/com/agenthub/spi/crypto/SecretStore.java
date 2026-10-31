package com.agenthub.spi.crypto;

/**
 * 出站密钥的加密、解密与掩码。所属域：spi。
 * <p>调用方：model / tool 的应用服务在入库前加密，出站或探活前解密。
 * 默认实现是本地 AES-GCM，主密钥来自 AGENTHUB_SECRET_KEY，不引入 Vault。
 * 以后替换托管实现时只换本接口的 Bean，业务层继续调用 encrypt / decrypt / mask。
 * <p>与 sys_app 不同：调用方 apiKey 只走 PasswordHasher 哈希，不能解密；
 * 模型供应商 Key 必须能还原后放进出站请求。实现需线程安全，且不得把明文、密文或主密钥写入日志。
 * <p>安全要求：
 * <ul>
 *   <li>密文必须绑定 {@code context}（用 AEAD 的附加认证数据），防止把 A 表的密文搬到 B 表后仍能解密；</li>
 *   <li>密文带版本与主密钥指纹前缀，支持主密钥轮换后的双密钥在线过渡；</li>
 *   <li>新写入一律用当前主密钥 + 版本前缀，旧格式由 {@link #needsUpgrade(String)} 判定后再迁移。</li>
 * </ul>
 */
public interface SecretStore {

    /**
     * 把厂商密钥编成可入库的密文。
     *
     * @param plainSecret 明文，空白会被拒绝
     * @param context     用途绑定串，取 {@link SecretContexts} 常量；解密时必须传同一个值
     * @return 带版本前缀的密文：v2:keyId:base64(iv):base64(ciphertext+tag)
     */
    String encrypt(String plainSecret, String context);

    /**
     * 还原明文，供出站探活或对话使用。
     *
     * @param cipherText encrypt 的返回值，也兼容升级前的旧格式
     * @param context    加密时使用的同一个用途绑定串
     * @return 明文
     * @throws IllegalArgumentException 格式损坏、context 不匹配或主密钥都对不上时抛出；
     *                                  调用方转成业务错误，且不要记录密文
     */
    String decrypt(String cipherText, String context);

    /**
     * 生成列表和详情展示用的掩码。
     *
     * @param plainSecret 明文
     * @return 保留短前缀和末 4 位、中间为星号的字符串
     */
    String mask(String plainSecret);

    /**
     * 判断密文是否需要迁移到当前主密钥与最新格式。旧格式、或指纹不是当前主密钥时返回 true。
     * 只读判断，不涉及解密，可安全地在启动期批量调用。
     *
     * @param cipherText 库中密文，可为空
     * @return 需要重加密时为 true
     */
    boolean needsUpgrade(String cipherText);

    /**
     * @return 当前主密钥指纹前 8 位，仅用于日志与排障，不泄露主密钥
     */
    String currentKeyId();
}
