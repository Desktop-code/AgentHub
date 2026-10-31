package com.agenthub.model.infra.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地主密钥配置。所属域：model 的 infra。
 * <p>只读 {@code agenthub.crypto.secret-key}（当前主密钥，新写入使用）与
 * {@code agenthub.crypto.previous-keys}（轮换过渡期的历史密钥，只解密）。
 * 真实值来自环境变量 AGENTHUB_SECRET_KEY / AGENTHUB_PREVIOUS_SECRET_KEYS，禁止写进仓库。
 * 这是加密厂商 Key 的主密钥，不是某一家的 sk。
 * <p>轮换步骤：把旧值挪到 previous-keys → 配新值 → 启动后由迁移任务把旧密文重加密 → 观察线上
 * {@code needsUpgrade} 归零后即可从配置移除历史密钥。
 */
@ConfigurationProperties(prefix = "agenthub.crypto")
public class CryptoProperties {

    private String secretKey = "";

    /**
     * 历史主密钥列表，逗号分隔的 64 位 hex 或 Base64。为空表示没有历史密钥。
     * 环境变量形态：AGENTHUB_PREVIOUS_SECRET_KEYS=key1,key2
     */
    private List<String> previousKeys = new ArrayList<>();

    /**
     * @return 当前主密钥原文，可能为空；空值会在 SecretStore 创建时拒绝启动
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * @param secretKey 64 位 hex 或 32 字节的 Base64
     */
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * @return 历史主密钥；只用于解密旧密文，顺序无关
     */
    public List<String> getPreviousKeys() {
        return previousKeys;
    }

    /**
     * @param previousKeys 历史主密钥列表，允许为空或含空串（空串会被忽略）
     */
    public void setPreviousKeys(List<String> previousKeys) {
        this.previousKeys = previousKeys == null ? new ArrayList<>() : previousKeys;
    }
}
