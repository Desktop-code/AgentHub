package com.agenthub.model.domain.service;

import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;

/**
 * 模型凭证的解密入口。所属域：model 的 domain。
 *
 * <p>存在意义：模型 Key 的密文用 AEAD 的 AAD 绑定到**供应商 id**（{@link SecretContexts#modelCredential}），
 * 解密时传入的 providerId 必须与加密时一致，否则解不开——这条是安全不变量，而不是普通参数。
 * 原先向量化、重排、评测打分、AgentScope 出站、连通测试五处各自拼一次 AAD 串，
 * 只要有一处拼错（例如误用 instanceId），症状会是「线上突然全部解密失败」而不是明显的编译错误。
 *
 * <p>边界（重要）：
 * <ul>
 *   <li>本类**只**负责「用正确的 AAD 解密」，不做凭证查询、不判断凭证是否停用、不拼错误文案——
 *       这些在各调用方语义不同（如评测还要校验 status），强行统一会改掉对外文案；</li>
 *   <li>异常原样抛出（{@link SecretStore#decrypt} 的 {@code IllegalArgumentException} 等），
 *       由调用方按自己的异常类型与文案包装，因此各处的降级路径完全不变；</li>
 *   <li>禁止在日志里打印密文或明文。</li>
 * </ul>
 * 线程安全：无状态，静态纯函数。
 */
public final class ModelCredentialResolver {

    /** 工具类禁止实例化。 */
    private ModelCredentialResolver() {
    }

    /**
     * 解密模型凭证 Key。
     *
     * @param store      加解密实现
     * @param cipherText 库中密文（{@code model_credential.api_key_cipher}）
     * @param providerId 凭证所属供应商 id，用于 AAD 绑定；调用方必须传与加密时相同的值
     * @return 明文 Key；仅用于当次出站，禁止落库或打日志
     * @throws IllegalArgumentException AAD 不匹配、密文损坏或主密钥都对不上
     */
    public static String decryptKey(SecretStore store, String cipherText, Long providerId) {
        // AAD 绑定供应商：这是「密文搬走就解不开」的唯一保障，不要在这里换成其他 id
        return store.decrypt(cipherText, SecretContexts.modelCredential(providerId));
    }
}
