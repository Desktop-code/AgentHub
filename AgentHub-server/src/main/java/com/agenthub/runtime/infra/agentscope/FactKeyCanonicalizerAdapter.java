package com.agenthub.runtime.infra.agentscope;

import com.agenthub.memory.domain.service.FactKeyNormalizer;
import com.agenthub.runtime.domain.service.FactKeyCanonicalizer;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 用 memory 的 fact_key 归一器做参数名/事实键的规范映射。所属域：runtime 的 infra。
 * 复用同一份别名表（内置 + YAML 扩展），避免工具补参与 LONG 合并对「居住城市」理解不一致。
 */
@Component
public class FactKeyCanonicalizerAdapter implements FactKeyCanonicalizer {

    private final FactKeyNormalizer normalizer;

    /**
     * @param normalizer memory 侧的 fact_key 归一器，含别名表
     */
    public FactKeyCanonicalizerAdapter(FactKeyNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * @param raw 参数名或事实键
     * @return 规范事实键；未配置同义、格式不合法的自定义键仍会被归一器原样校验后返回
     */
    @Override
    public Optional<String> canonical(String raw) {
        return normalizer.normalize(raw);
    }
}
