package com.agenthub.auth.infra;

import com.agenthub.common.security.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 密码哈希。所属域：auth。只提供算法，不装配 Security 过滤器。
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * {@inheritDoc}
     */
    @Override
    public String hash(String raw) {
        return encoder.encode(raw);
    }

    /**
     * 哈希格式损坏时视为不匹配，避免把算法异常变成 500。
     *
     * @param raw  明文
     * @param hash 库中的哈希
     * @return 是否匹配
     */
    @Override
    public boolean matches(String raw, String hash) {
        if (raw == null || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return encoder.matches(raw, hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
