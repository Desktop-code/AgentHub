package com.agenthub.spi.crypto;

/**
 * 密文的用途绑定串。所属域：spi。
 * <p>作为 AEAD 的附加认证数据（AAD）参与加解密：解密时必须传与加密时完全相同的串，
 * 否则解密失败。用来阻断「把 A 表密文搬到 B 表 / 搬给另一个供应商仍能解开」这类篡改。
 * <p>约定：新增存放密钥的列时，必须在此登记一个专属前缀，禁止复用其他列的前缀。
 */
public final class SecretContexts {

    /** 模型凭证前缀；完整值为 {@code model_credential:{providerId}}。 */
    private static final String MODEL_CREDENTIAL = "model_credential";

    /** 工具源前缀；完整值为 {@code tool_source:{sourceId}}。 */
    private static final String TOOL_SOURCE = "tool_source";

    private SecretContexts() {
    }

    /**
     * 模型凭证的绑定串。绑定到供应商，凭证在供应商之间搬动后即无法解密。
     *
     * @param providerId 供应商 id
     * @return AAD 串
     */
    public static String modelCredential(Long providerId) {
        if (providerId == null) {
            throw new IllegalArgumentException("providerId 不能为空");
        }
        return MODEL_CREDENTIAL + ":" + providerId;
    }

    /**
     * 工具源鉴权头的绑定串。绑定到具体源，跨源复制密文无法解密。
     * 因密文列可空，工具源采用「先插入拿 id、再回写密文」的顺序。
     *
     * @param sourceId 工具源 id
     * @return AAD 串
     */
    public static String toolSource(Long sourceId) {
        if (sourceId == null) {
            throw new IllegalArgumentException("sourceId 不能为空");
        }
        return TOOL_SOURCE + ":" + sourceId;
    }
}
