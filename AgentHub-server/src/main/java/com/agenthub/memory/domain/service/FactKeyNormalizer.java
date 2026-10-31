package com.agenthub.memory.domain.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * LONG fact_key 格式校验与同义归一。所属域：memory。
 * 写入前调用；归一失败返回 empty，禁止无 key 瞎插。
 * 内置居住/城市类映射；YAML {@code agenthub.memory.fact-key-aliases} 可覆盖扩展。
 */
public final class FactKeyNormalizer {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{1,64}$");
    private static final int MAX_LEN = 64;

    /** 内置同义 → 规范键；至少覆盖居住/城市类。 */
    private static final Map<String, String> BUILTIN = Map.ofEntries(
            Map.entry("residence", "residence_city"),
            Map.entry("residence_city", "residence_city"),
            Map.entry("city_of_residence", "residence_city"),
            Map.entry("living_city", "residence_city"),
            Map.entry("home_city", "residence_city"),
            Map.entry("hometown", "residence_city"),
            Map.entry("city", "residence_city"),
            Map.entry("居住地", "residence_city"),
            Map.entry("居住城市", "residence_city"),
            Map.entry("常驻城市", "residence_city"),
            Map.entry("常住城市", "residence_city"),
            Map.entry("所在城市", "residence_city"),
            Map.entry("dietary_restriction", "dietary_restriction"),
            Map.entry("food_taboo", "dietary_restriction"),
            Map.entry("饮食禁忌", "dietary_restriction"),
            Map.entry("language_preference", "language_preference"),
            Map.entry("preferred_language", "language_preference"),
            Map.entry("语言偏好", "language_preference"),
            Map.entry("timezone", "timezone"),
            Map.entry("时区", "timezone"),
            Map.entry("occupation", "occupation"),
            Map.entry("job", "occupation"),
            Map.entry("职业", "occupation")
    );

    private final Map<String, String> aliases;

    /**
     * @param extraAliases 配置扩展（alias → 规范键）；可空；同名覆盖内置
     */
    public FactKeyNormalizer(Map<String, String> extraAliases) {
        Map<String, String> merged = new LinkedHashMap<>(BUILTIN);
        if (extraAliases != null) {
            for (Map.Entry<String, String> e : extraAliases.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue().isBlank()) {
                    continue;
                }
                // 配置侧也先做一次轻量整形，避免大小写/空白导致映射失效
                String from = softShape(e.getKey());
                String to = softShape(e.getValue());
                if (!from.isEmpty() && !to.isEmpty()) {
                    merged.put(from, to);
                }
            }
        }
        this.aliases = Collections.unmodifiableMap(merged);
    }

    /**
     * 将模型或人工输入的键归一为规范 snake_case。
     *
     * @param raw 原始 fact_key（可含近义英文/中文）
     * @return 合法规范键；空白或无法整形为 [a-z0-9_]{1,64} 时为空
     */
    public Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String shaped = softShape(raw);
        if (shaped.isEmpty()) {
            return Optional.empty();
        }
        // 先查同义表（含中文别名），再校验规范键形态
        String mapped = aliases.getOrDefault(shaped, shaped);
        String canonical = softShape(mapped);
        if (canonical.length() > MAX_LEN || !VALID.matcher(canonical).matches()) {
            return Optional.empty();
        }
        return Optional.of(canonical);
    }

    /**
     * @return 当前生效的 alias→规范键表（含内置），只读
     */
    public Map<String, String> aliases() {
        return aliases;
    }

    /**
     * 空白压缩、小写、连字符/空格改下划线；中文原样保留供同义表命中。
     */
    private static String softShape(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_')
                .replaceAll("_+", "_");
        if (t.startsWith("_")) {
            t = t.substring(1);
        }
        if (t.endsWith("_")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
