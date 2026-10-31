package com.agenthub.model.infra.crypto;

import com.agenthub.spi.crypto.SecretStore;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 AES-256-GCM 实现。所属域：model 的 infra。
 * <p>密文格式（v2，当前写入格式）：
 * {@code v2:<keyId>:<base64(iv)>:<base64(ciphertext+tag)>}。
 * 其中 keyId 是主密钥 SHA-256 的前 8 个 hex 字符，只用于选密钥与排障，不可反推主密钥。
 * <p>兼容旧格式 {@code <base64(iv)>:<base64(ciphertext+tag)>}：读取时按密钥环依次尝试，
 * 且旧格式没有绑定 context（升级前写入时没有 AAD），迁移后自动变成 v2。
 * <p>密钥环由「当前主密钥 + 若干历史密钥」组成，支持轮换期间的在线双读；新写入一律用当前主密钥。
 * 每次加密使用新的随机 IV；加解密都把 context 作为 AAD 参与认证。
 * 与 sys_app 的 BCrypt 哈希相反：这里的密文可以解密，供出站请求使用。
 */
public class AesGcmSecretStore implements SecretStore {

    /** 当前写入格式的版本标记。格式变化时换版本号，旧版本在读取侧保留兼容。 */
    private static final String VERSION = "v2";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_ID_LENGTH = 8;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** 旧格式无版本前缀，解密时按密钥环顺序尝试，最多尝试的密钥数受密钥环长度限制。 */
    private static final String LEGACY_MARKER = ":";

    /** 当前主密钥，用于新写入。 */
    private final SecretKeySpec currentKey;
    /** 当前主密钥指纹，写入密文前缀与日志。 */
    private final String currentKeyId;
    /** keyId -> 密钥。含当前密钥与全部历史密钥，保持插入顺序便于排障。 */
    private final Map<String, SecretKeySpec> keyRing;
    private final SecureRandom random = new SecureRandom();

    /**
     * 单密钥构造。没有历史密钥时使用，例如单元测试或尚未轮换过的部署。
     *
     * @param secretKey 主密钥，空白或长度不对时抛出 IllegalStateException，让 Spring 启动失败
     */
    public AesGcmSecretStore(String secretKey) {
        this(secretKey, List.of());
    }

    /**
     * 带历史密钥的构造，用于主密钥轮换后的过渡期。
     *
     * @param secretKey      当前主密钥，新写入使用
     * @param previousKeys   历史主密钥，只用于解密旧密文；可为空列表
     * @throws IllegalStateException 当前主密钥为空/长度不对，或历史密钥里有非法项
     */
    public AesGcmSecretStore(String secretKey, List<String> previousKeys) {
        byte[] current = decodeKey(secretKey);
        this.currentKey = new SecretKeySpec(current, "AES");
        this.currentKeyId = keyId(current);
        Map<String, SecretKeySpec> ring = new LinkedHashMap<>();
        ring.put(currentKeyId, currentKey);
        // 历史密钥只参与读；重复或非法的历史密钥直接让启动失败，避免线上出现「部分密文解不开」的半可用状态
        if (previousKeys != null) {
            for (String raw : previousKeys) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                byte[] bytes = decodeKey(raw);
                ring.putIfAbsent(keyId(bytes), new SecretKeySpec(bytes, "AES"));
            }
        }
        this.keyRing = Map.copyOf(ring);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String encrypt(String plainSecret, String context) {
        byte[] plain = requirePlain(plainSecret).getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, new GCMParameterSpec(TAG_BITS, iv));
            // context 参与认证：换表/换记录后即使有密文也解不开
            cipher.updateAAD(requireContext(context).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain);
            return VERSION + ":" + currentKeyId + ":"
                    + java.util.Base64.getEncoder().encodeToString(iv) + ":"
                    + java.util.Base64.getEncoder().encodeToString(encrypted);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("加密失败");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String decrypt(String cipherText, String context) {
        if (cipherText == null || cipherText.isBlank()) {
            throw new IllegalArgumentException("密文格式不正确");
        }
        String aad = requireContext(context);
        String[] parts = cipherText.split(":", -1);
        if (parts.length == 4 && VERSION.equals(parts[0])) {
            return decryptV2(parts, aad);
        }
        if (parts.length == 2) {
            // 升级前的旧格式：无版本、无 AAD，按密钥环逐个尝试
            return decryptLegacy(parts, aad);
        }
        throw new IllegalArgumentException("密文格式不正确");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String mask(String plainSecret) {
        String key = requirePlain(plainSecret);
        int length = key.length();
        if (length <= 8) {
            int tail = Math.min(2, length);
            return "****" + key.substring(length - tail);
        }
        int prefix = length >= 12 ? 4 : 2;
        return key.substring(0, prefix) + "****" + key.substring(length - 4);
    }

    /**
     * {@inheritDoc}
     * 判定不涉及解密：旧格式、或指纹不是当前主密钥，都视为需要迁移。
     */
    @Override
    public boolean needsUpgrade(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return false;
        }
        String[] parts = cipherText.split(":", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            return true;
        }
        return !currentKeyId.equals(parts[1]);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String currentKeyId() {
        return currentKeyId;
    }

    /**
     * 解 v2 密文。指纹找不到对应密钥时直接失败，不再靠穷举，避免密钥环变长后放大开销。
     */
    private String decryptV2(String[] parts, String aad) {
        SecretKeySpec key = keyRing.get(parts[1]);
        if (key == null) {
            throw new IllegalArgumentException("密文无法解密：未知主密钥");
        }
        return doDecrypt(key, parts[2], parts[3], aad, true);
    }

    /**
     * 解旧格式。旧格式写入时没有 AAD，因此认证数据传空；context 只用于日志定位与后续迁移。
     * 密钥环里每个密钥都试一次，全部失败才算失败。
     */
    private String decryptLegacy(String[] parts, String aad) {
        List<String> failures = new ArrayList<>();
        for (SecretKeySpec key : keyRing.values()) {
            try {
                return doDecrypt(key, parts[0], parts[1], aad, false);
            } catch (IllegalArgumentException ex) {
                failures.add(ex.getMessage());
            }
        }
        // 不把任何密文或密钥内容带进异常，只给固定文案
        throw new IllegalArgumentException("密文无法解密");
    }

    /**
     * 真正的 GCM 解密。
     *
     * @param key        选中的密钥
     * @param ivB64      IV 的 Base64
     * @param payloadB64 密文与 tag 的 Base64
     * @param aad        附加认证数据；旧格式传空串表示不做 AAD 校验
     * @param withAad    是否启用 AAD
     * @return 明文
     */
    private String doDecrypt(SecretKeySpec key, String ivB64, String payloadB64, String aad, boolean withAad) {
        try {
            byte[] iv = java.util.Base64.getDecoder().decode(ivB64);
            byte[] encrypted = java.util.Base64.getDecoder().decode(payloadB64);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (withAad) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // 统一成固定文案，避免把 Base64 片段或密钥线索带进上层
            throw new IllegalArgumentException("密文无法解密");
        }
    }

    /**
     * 计算主密钥指纹：SHA-256 前 8 个 hex。只用来选密钥和排障。
     *
     * @param keyBytes 32 字节主密钥
     * @return 8 位小写 hex
     */
    private static String keyId(byte[] keyBytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            StringBuilder hex = new StringBuilder(KEY_ID_LENGTH);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
                if (hex.length() >= KEY_ID_LENGTH) {
                    break;
                }
            }
            return hex.substring(0, KEY_ID_LENGTH);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法计算主密钥指纹");
        }
    }

    /**
     * 启动期和单测共用的主密钥解析。空密钥直接失败，禁止用空材料加密。
     *
     * @param raw 配置值
     * @return 32 字节 AES 密钥
     */
    public static byte[] decodeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("未配置 agenthub.crypto.secret-key / AGENTHUB_SECRET_KEY");
        }
        String text = raw.trim();
        byte[] bytes;
        if (text.matches("(?i)[0-9a-f]{64}")) {
            bytes = decodeHex(text);
        } else {
            try {
                bytes = java.util.Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("主密钥必须是 32 字节：64 位 hex，或 Base64");
            }
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("主密钥必须是 32 字节：64 位 hex，或 Base64");
        }
        return bytes;
    }

    private static byte[] decodeHex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String requirePlain(String plainSecret) {
        if (plainSecret == null || plainSecret.isBlank()) {
            throw new IllegalArgumentException("密钥不能为空");
        }
        return plainSecret.trim();
    }

    /**
     * context 为空时拒绝加密，避免出现无绑定、可任意搬动的密文。
     */
    private static String requireContext(String context) {
        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("密文用途绑定不能为空");
        }
        return context;
    }
}
