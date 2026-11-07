package com.agenthub.memory.domain.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LONG fact_key 格式校验与同义归一。所属域：memory。
 * <p>
 * 写入前调用；归一失败返回 empty，禁止无 key 瞎插。
 * 内置居住/城市类 + 饮食/饮品偏好族映射；YAML {@code agenthub.memory.fact-key-aliases} 可覆盖扩展。
 * <p>
 * <b>为什么必须在这里收口</b>：同一条偏好，模型换一种说法就会给出不同的 key
 * （{@code food_preference} / {@code favorite_food} / {@code 饮食偏好}）。
 * 合并只认 canonical fact_key，所以「同族归一」必须发生在写库之前；
 * 这一步不做，{@link com.agenthub.memory.application.LongMemoryMerger} 再正确也只能按两个键各存一条。
 */
public final class FactKeyNormalizer {

    /** 饮食偏好族 canonical：喜爱食物 / 爱吃 / 饮食偏好 等一律归一到这里 */
    public static final String FOOD_PREFERENCE = "food_preference";
    /** 饮品偏好族 canonical：喜爱饮品 / 爱喝 / 饮品偏好 等一律归一到这里 */
    public static final String BEVERAGE_PREFERENCE = "beverage_preference";

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_]{1,64}$");
    private static final int MAX_LEN = 64;

    /**
     * 内置同义 → 规范键；单点维护，禁止散落到 Merger。
     * <p>
     * 偏好族必须整族收口：饮食/饮品两条 canonical，其余近义写法全部映射过去，
     * 否则「爱吃评估」「喜爱食物=苹果」会被记成两条。
     */
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
            Map.entry("职业", "occupation"),
            // —— 饮食偏好族：喜爱食物 / 爱吃 / 饮食偏好 → food_preference ——
            Map.entry(FOOD_PREFERENCE, FOOD_PREFERENCE),
            Map.entry("favorite_food", FOOD_PREFERENCE),
            Map.entry("favourite_food", FOOD_PREFERENCE),
            Map.entry("preferred_food", FOOD_PREFERENCE),
            Map.entry("liked_food", FOOD_PREFERENCE),
            Map.entry("loved_food", FOOD_PREFERENCE),
            Map.entry("favorite_meal", FOOD_PREFERENCE),
            Map.entry("food_preferences", FOOD_PREFERENCE),
            Map.entry("cuisine_preference", FOOD_PREFERENCE),
            Map.entry("dietary_preference", FOOD_PREFERENCE),
            Map.entry("taste_preference", FOOD_PREFERENCE),
            Map.entry("饮食偏好", FOOD_PREFERENCE),
            Map.entry("饮食习惯", FOOD_PREFERENCE),
            Map.entry("喜爱食物", FOOD_PREFERENCE),
            Map.entry("喜欢的食物", FOOD_PREFERENCE),
            Map.entry("爱吃", FOOD_PREFERENCE),
            Map.entry("爱吃的", FOOD_PREFERENCE),
            Map.entry("爱吃的东西", FOOD_PREFERENCE),
            Map.entry("最爱吃", FOOD_PREFERENCE),
            // —— 饮品偏好族：喜爱饮品 / 爱喝 / 饮品偏好 → beverage_preference ——
            Map.entry(BEVERAGE_PREFERENCE, BEVERAGE_PREFERENCE),
            Map.entry("favorite_drink", BEVERAGE_PREFERENCE),
            Map.entry("favourite_drink", BEVERAGE_PREFERENCE),
            Map.entry("preferred_drink", BEVERAGE_PREFERENCE),
            Map.entry("liked_drink", BEVERAGE_PREFERENCE),
            Map.entry("favorite_beverage", BEVERAGE_PREFERENCE),
            Map.entry("drink_preference", BEVERAGE_PREFERENCE),
            Map.entry("beverage_preferences", BEVERAGE_PREFERENCE),
            Map.entry("饮品偏好", BEVERAGE_PREFERENCE),
            Map.entry("喜爱的饮品", BEVERAGE_PREFERENCE),
            Map.entry("喜爱饮品", BEVERAGE_PREFERENCE),
            Map.entry("喜欢的饮品", BEVERAGE_PREFERENCE),
            Map.entry("爱喝", BEVERAGE_PREFERENCE),
            Map.entry("爱喝的", BEVERAGE_PREFERENCE),
            Map.entry("最喜欢喝的", BEVERAGE_PREFERENCE)
    );

    private final Map<String, String> aliases;
    /** canonical → 同族里所有「能直接落库的 ascii 别名」（含自身），供历史行并轨查询 */
    private final Map<String, Set<String>> familyKeys;

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
        this.familyKeys = Collections.unmodifiableMap(buildFamilyKeys(merged));
    }

    /**
     * 反向索引：canonical → 该族所有可落库的 ascii 别名。
     * <p>
     * 用途是「并轨历史行」：旧版本没有这些别名时，库里可能已经用 {@code favorite_food}
     * 单独存过一条；只按 canonical 查会再插一条，永远收不成一条。
     * 中文别名不进索引——它们不是合法 fact_key，不会以原样出现在库里。
     *
     * @param merged 生效的 alias → canonical 表
     * @return canonical → 别名集合（含自身）
     */
    private static Map<String, Set<String>> buildFamilyKeys(Map<String, String> merged) {
        Map<String, Set<String>> index = new HashMap<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String alias = entry.getKey();
            String canonical = entry.getValue();
            index.computeIfAbsent(canonical, key -> new LinkedHashSet<>());
            if (VALID.matcher(canonical).matches()) {
                index.get(canonical).add(canonical);
            }
            if (VALID.matcher(alias).matches()) {
                index.get(canonical).add(alias);
            }
        }
        return index;
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
     * 取某个 canonical 的「同族键集合」（含自身与所有 ascii 别名）。
     * <p>
     * 调用方：{@link com.agenthub.memory.application.LongMemoryMerger} 按整族查活跃行，
     * 既能把换说法的旧行并进来，也能把历史上同族多行收敛成一条。
     * 非 canonical 输入（如未登记的裸 key）退化为「只查它自己」，行为不弱于现状。
     *
     * @param canonical 已经过 {@link #normalize(String)} 的规范键
     * @return 用于 IN 查询的键集合；非法输入为空集合
     */
    public Set<String> familyKeysOf(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return Set.of();
        }
        String shaped = softShape(canonical);
        Set<String> known = familyKeys.get(shaped);
        if (known != null && !known.isEmpty()) {
            return known;
        }
        return VALID.matcher(shaped).matches() ? Set.of(shaped) : Set.of();
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
