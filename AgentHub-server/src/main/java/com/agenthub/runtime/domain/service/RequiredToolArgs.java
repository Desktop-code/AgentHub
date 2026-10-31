package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.MemoryFact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 工具必填参数完备性检查。所属域：runtime，纯逻辑，不依赖引擎与 Spring。
 *
 * <p>职责：执行工具之前，按 JSON Schema 的 {@code required} 核对入参，保证「缺必填不得脑补」。
 * 可选参数一律不拦；只看顶层 {@code required}，嵌套对象仍交给引擎按工具自身 schema 校验。
 *
 * <p>补参与放行依据的优先级（严格按序，前一档命中即不再往后看）：
 * <ol>
 *   <li>本轮用户输入里已明确给出</li>
 *   <li>本会话已确认的上下文（摘要、近轮、短期记忆已拼进模型输入）</li>
 *   <li>本轮已注入系统提示的长期记忆，且字段映射唯一、明确</li>
 *   <li>以上都没有 → 不执行工具，返回追问文案，禁止模型臆造</li>
 * </ol>
 *
 * <p>同时拦截「模型自带臆造参数」：值虽存在，但在三档来源里都找不到依据时视为编造，同样不执行。
 * 该判定只作用于实体类参数（城市、地点、时区等），避免把分页、天数、枚举等业务可推导的参数误判成编造。
 */
public final class RequiredToolArgs {

    /** 追问文案里的硬约束，便于模型直接照做 */
    static final String NO_GUESS_HINT = "禁止编造、猜测或用示例值替换，也不要用其它参数值重试。"
            + "请直接用中文向用户追问该参数，拿到明确值后再调用工具。";

    /** 实体参数名兜底集合：这些参数一旦取值找不到依据，就按编造处理 */
    private static final Set<String> DEFAULT_ENTITY_PARAMS = Set.of(
            "city", "place", "location", "address", "region", "province", "country", "timezone");

    /** 实体参数对应的事实键兜底集合 */
    private static final Set<String> DEFAULT_ENTITY_FACT_KEYS = Set.of("residence_city", "timezone");

    /** 判断「像具体值」而不是一句描述的最长字符数 */
    private static final int BARE_VALUE_MAX = 12;

    /** 从「键：值」里取值时允许的最长字符数 */
    private static final int LABELED_VALUE_MAX = 24;

    /** 值的行政后缀；比较时两侧都去掉，兼容「北京市」与「北京」 */
    private static final String[] ADMIN_SUFFIXES = {"特别行政区", "自治区", "地区", "城市", "市", "省", "县"};

    /** 明显是叙述而非取值的词；残留这些词说明无法可靠取值，应改为追问 */
    private static final String[] NARRATIVE_NOISE = {"我", "住", "是", "在", "的"};

    /**
     * 记忆正文常见的前置引导语，按长度从长到短匹配后剥掉。
     * 抽取模型常写「用户常住杭州」「居住城市是杭州」，剥掉这层后才能当字段值用。
     */
    private static final String[] LEAD_INS = {
            "居住城市是", "居住城市为", "常住城市是", "常住城市为", "所在城市是", "所在城市为",
            "居住地是", "居住地为", "用户的居住城市", "用户的常住城市", "用户居住地",
            "居住在", "常住于", "常住", "居住", "住在", "家乡是", "家乡为", "城市是", "城市为",
            "用户", "本人", "我的", "我", "城市", "是", "为", "在", "于", "的"
    };

    private RequiredToolArgs() {
    }

    /**
     * 本轮可用于判定的来源。只应传入本轮真正注入的内容；未注入的记忆不得参与补参。
     *
     * @param userText    本轮送进模型的用户侧文本（含会话摘要、近轮、短期记忆与当前用户话）
     * @param contextText 会话上下文文本；调用方没有更细粒度时可直接复用 userText
     * @param facts       本轮已注入的长期记忆，可为空
     */
    public record Evidence(String userText, String contextText, List<MemoryFact> facts) {

        public Evidence {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        /**
         * @return 三档来源都为空时的证据，用于「无记忆」场景
         */
        public static Evidence empty() {
            return new Evidence("", "", List.of());
        }
    }

    /**
     * 实体参数判据。可通过 YAML 扩展，不改代码即可覆盖新的工具字段。
     *
     * @param entityParamNames 需要做臆造校验的参数名（内部已按小写下划线比较）
     * @param entityFactKeys   需要做臆造校验的规范事实键
     */
    public record Rules(Set<String> entityParamNames, Set<String> entityFactKeys) {

        public Rules {
            entityParamNames = entityParamNames == null ? Set.of() : Set.copyOf(entityParamNames);
            entityFactKeys = entityFactKeys == null ? Set.of() : Set.copyOf(entityFactKeys);
        }

        /**
         * @return 内置默认：城市/地点/时区类 + 居住城市与时间区事实键
         */
        public static Rules defaults() {
            return new Rules(DEFAULT_ENTITY_PARAMS, DEFAULT_ENTITY_FACT_KEYS);
        }
    }

    /**
     * 校验结论。
     *
     * @param allowed    是否允许执行；false 时禁止出站
     * @param input      放行时实际使用的入参（可能已由长期记忆补全）
     * @param missing    缺失且无法补全的必填参数
     * @param unverified 有值但三档来源都无依据的实体参数（疑似编造）
     * @param filled     由长期记忆补全的参数
     * @param message    不执行时给模型的追问文案；放行时为空串
     */
    public record Result(boolean allowed, Map<String, Object> input, List<String> missing,
                         List<String> unverified, List<String> filled, String message) {

        public Result {
            // 不能用 Map.copyOf：JSON 里的 null 会作为 null value 进来，copyOf 会直接抛 NPE
            input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
            missing = missing == null ? List.of() : List.copyOf(missing);
            unverified = unverified == null ? List.of() : List.copyOf(unverified);
            filled = filled == null ? List.of() : List.copyOf(filled);
            message = message == null ? "" : message;
        }
    }

    /**
     * 按 schema 的 required 核对入参，并在映射唯一时用已注入的长期记忆补参。
     *
     * @param toolName    工具调用名，仅用于追问文案
     * @param required    schema 的 required 列表；空表示无必填，直接放行
     * @param input       模型给出的入参，可空
     * @param evidence    本轮可用的事实来源
     * @param rules       实体参数判据；可空则用默认
     * @param canonicalOf 参数名/事实键 → 规范事实键（通常复用 fact_key 归一器），返回空表示无明确映射
     * @return 放行或追问结论
     */
    public static Result check(String toolName, List<String> required, Map<String, Object> input,
                               Evidence evidence, Rules rules, Function<String, Optional<String>> canonicalOf) {
        Evidence source = evidence == null ? Evidence.empty() : evidence;
        Rules limits = rules == null ? Rules.defaults() : rules;
        Map<String, Object> merged = new LinkedHashMap<>();
        if (input != null) {
            input.forEach((key, value) -> {
                if (key != null) {
                    merged.put(key, value);
                }
            });
        }
        if (required == null || required.isEmpty()) {
            return new Result(true, merged, List.of(), List.of(), List.of(), "");
        }
        List<String> missing = new ArrayList<>();
        List<String> unverified = new ArrayList<>();
        List<String> filled = new ArrayList<>();
        for (String rawName : required) {
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            String name = rawName.trim();
            Optional<String> canonical = canonical(canonicalOf, name);
            Object value = merged.get(name);
            if (isBlank(value)) {
                // 值缺失：只有映射明确且记忆唯一时才补；其余一律追问，绝不由模型填空
                Optional<String> fromMemory = canonical.isPresent()
                        ? memoryValue(name, canonical.get(), source.facts(), canonicalOf)
                        : Optional.empty();
                if (fromMemory.isPresent()) {
                    merged.put(name, fromMemory.get());
                    filled.add(name);
                } else {
                    missing.add(name);
                }
                continue;
            }
            // 有值：实体参数必须能在三档来源里找到依据，否则按编造拦下
            if (isEntity(name, canonical, limits) && !traceable(String.valueOf(value), source)) {
                unverified.add(name);
            }
        }
        boolean allowed = missing.isEmpty() && unverified.isEmpty();
        return new Result(allowed, merged, missing, unverified, filled,
                allowed ? "" : composeMessage(toolName, missing, unverified));
    }

    /**
     * 从 schema 的 required 里取字符串列表；类型不符时视为无必填。
     *
     * @param parameters 工具 parameters map，可空
     * @return required 列表；没有则空
     */
    public static List<String> requiredOf(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        Object raw = parameters.get("required");
        if (!(raw instanceof Collection<?> collection) || collection.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (item != null && !item.toString().isBlank()) {
                names.add(item.toString().trim());
            }
        }
        return names;
    }

    private static Optional<String> canonical(Function<String, Optional<String>> canonicalOf, String raw) {
        if (canonicalOf == null || raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return canonicalOf.apply(raw);
    }

    /**
     * @param param     参数名
     * @param canonical 规范事实键，可空
     * @param rules     规则
     * @return 需要做「取值有无依据」校验的实体参数
     */
    private static boolean isEntity(String param, Optional<String> canonical, Rules rules) {
        if (rules.entityParamNames().contains(shape(param))) {
            return true;
        }
        return canonical.isPresent() && rules.entityFactKeys().contains(canonical.get());
    }

    /**
     * 从已注入的长期记忆里取唯一值。键不一致、值不唯一或正文像一句描述时放弃，交给追问。
     *
     * @param param       参数名，仅用于日志语义（此处不写日志）
     * @param canonical   规范事实键
     * @param facts       本轮已注入的长期记忆
     * @param canonicalOf 事实键归一
     * @return 唯一可用值
     */
    private static Optional<String> memoryValue(String param, String canonical, List<MemoryFact> facts,
                                                Function<String, Optional<String>> canonicalOf) {
        if (facts.isEmpty() || canonical == null || canonical.isBlank()) {
            return Optional.empty();
        }
        Set<String> values = new LinkedHashSet<>();
        for (MemoryFact fact : facts) {
            if (fact == null) {
                continue;
            }
            // 没有事实键就无法证明该条记忆对应这个字段，按映射不明确处理
            Optional<String> factKey = fact.factKey() == null || fact.factKey().isBlank()
                    ? Optional.empty()
                    : canonical(canonicalOf, fact.factKey());
            if (factKey.isEmpty() || !canonical.equals(factKey.get())) {
                continue;
            }
            bareValue(fact).ifPresent(values::add);
        }
        // 多值即模糊（例如两条记忆给了两个城市），不猜，改为追问
        return values.size() == 1 ? Optional.of(values.iterator().next()) : Optional.empty();
    }

    /**
     * 从一条记忆正文里剥出「像值」的部分。
     * 支持「居住城市：杭州」「city: Hangzhou」与极短纯值；带叙述句或多值列举一律放弃。
     */
    private static Optional<String> bareValue(MemoryFact fact) {
        String content = fact.content() == null ? "" : fact.content().trim();
        String candidate = content.isEmpty() ? fact.searchable() : content;
        int colon = firstColon(candidate);
        if (colon >= 0) {
            return cleanValue(candidate.substring(colon + 1), LABELED_VALUE_MAX);
        }
        return cleanValue(candidate, BARE_VALUE_MAX);
    }

    private static int firstColon(String text) {
        int zh = text.indexOf('：');
        int en = text.indexOf(':');
        if (zh < 0) {
            return en;
        }
        if (en < 0) {
            return zh;
        }
        return Math.min(zh, en);
    }

    /**
     * 去掉句末标点与前置引导语后，判断能否直接当参数值。
     *
     * @param raw 候选片段
     * @param max 允许的最大字符数
     * @return 可用的单一取值
     */
    private static Optional<String> cleanValue(String raw, int max) {
        String text = stripTail(raw);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        // 逗号/顿号意味列举了多个取值，无法确定唯一字段值
        if (text.indexOf('，') >= 0 || text.indexOf(',') >= 0 || text.indexOf('、') >= 0) {
            return Optional.empty();
        }
        String value = stripLeadIns(text);
        if (value.isEmpty() || value.length() > max) {
            return Optional.empty();
        }
        // 剥掉引导语后仍含人称或动词，说明是叙述句而非取值
        for (String noise : NARRATIVE_NOISE) {
            if (value.contains(noise)) {
                return Optional.empty();
            }
        }
        return Optional.of(value);
    }

    /**
     * 截到第一个句末标点，去掉换行与分号后的内容。
     */
    private static String stripTail(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return "";
        }
        int cut = text.length();
        for (char stop : new char[]{'。', '\n', '\r', '；', ';'}) {
            int at = text.indexOf(stop);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return text.substring(0, cut).trim();
    }

    /**
     * 反复剥掉开头的前置引导语（可叠加，如「用户的居住城市是杭州」）。
     */
    private static String stripLeadIns(String text) {
        String value = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String lead : LEAD_INS) {
                if (value.startsWith(lead) && value.length() > lead.length()) {
                    value = value.substring(lead.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        return value;
    }

    /**
     * 三档来源里任一命中即算有依据；比较前统一小写、去空白与行政后缀。
     */
    private static boolean traceable(String value, Evidence evidence) {
        String needle = normalizeForMatch(value);
        if (needle.isEmpty()) {
            return false;
        }
        if (contains(evidence.userText(), needle) || contains(evidence.contextText(), needle)) {
            return true;
        }
        for (MemoryFact fact : evidence.facts()) {
            if (fact != null && contains(fact.searchable(), needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String haystack, String needle) {
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        return normalizeForMatch(haystack).contains(needle);
    }

    /**
     * 比较用归一：小写、去空白与引号、去行政后缀。
     */
    private static String normalizeForMatch(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s\"'“”‘’]", "");
        for (String suffix : ADMIN_SUFFIXES) {
            if (text.endsWith(suffix) && text.length() > suffix.length()) {
                text = text.substring(0, text.length() - suffix.length());
            }
        }
        return text;
    }

    private static String shape(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence text) {
            return text.toString().isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    /**
     * 组装追问文案：说明未执行、缺什么或哪一项无依据，并要求向用户追问。
     */
    private static String composeMessage(String toolName, List<String> missing, List<String> unverified) {
        String name = toolName == null || toolName.isBlank() ? "未命名工具" : toolName.trim();
        StringBuilder text = new StringBuilder("本轮未执行工具「").append(name).append("」，工具没有出站。");
        if (!missing.isEmpty()) {
            text.append("缺少必填参数 ").append(String.join("、", missing)).append('。');
        }
        if (!unverified.isEmpty()) {
            text.append("必填参数 ").append(String.join("、", unverified))
                    .append(" 的取值在本轮提问、本会话上下文和已注入的长期记忆里都找不到依据。");
        }
        return text.append(NO_GUESS_HINT).toString();
    }
}
