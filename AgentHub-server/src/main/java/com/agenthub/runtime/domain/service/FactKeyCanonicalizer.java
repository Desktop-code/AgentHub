package com.agenthub.runtime.domain.service;

import java.util.Optional;

/**
 * 参数名与事实键的规范映射出口。所属域：runtime。
 * 供工具必填参数闸门判断「这个参数对应哪条长期记忆」；实现放在 infra，复用 memory 的 fact_key 归一器。
 */
public interface FactKeyCanonicalizer {

    /**
     * @param raw 参数名（如 city）或事实键（如 居住城市）
     * @return 规范事实键（如 residence_city）；无法归一或不属于已知事实时为空
     */
    Optional<String> canonical(String raw);
}
