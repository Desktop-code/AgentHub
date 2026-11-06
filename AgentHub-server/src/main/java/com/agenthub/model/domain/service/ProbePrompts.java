package com.agenthub.model.domain.service;

import com.agenthub.model.domain.model.ModelCapability;

/**
 * 文本生成族连通测提示词。所属域：model。
 *
 * <p>CHAT / ROUTER / REWRITE / SUMMARY / MEMORY 出站形态相同（都走聊天接口），
 * 但探活文案仍按原用途发，避免日志与用户看到「所有用途都在测 CHAT」。
 * 探活只验证「鉴权 + 模型 + 端点」可达，提示词保持极短，不引入真实业务内容。
 *
 * <p>线程安全：无状态，静态纯函数。
 */
public final class ProbePrompts {

    /** 极短兜底文案：不配置用途时的最小请求 */
    private static final String DEFAULT = "ping";

    private ProbePrompts() {
    }

    /**
     * 按用途取探活提示词。
     *
     * @param capability 用途；null 时回落到 {@link #DEFAULT}
     * @return 该用途的极短探活文案
     */
    public static String chat(ModelCapability capability) {
        if (capability == null) {
            return DEFAULT;
        }
        return switch (capability) {
            case ROUTER -> "请判断这句话该路由给哪个处理方，只回答用途码：ping";
            case REWRITE -> "请把这句话改写得更简洁，保留原意：ping";
            case SUMMARY -> "请用一句话概括：ping";
            case MEMORY -> "请从这句话中抽取需要长期记住的事实：ping";
            // CHAT 及其它非生成族（理论上不会走到这里）统一用最小 ping
            default -> DEFAULT;
        };
    }
}
