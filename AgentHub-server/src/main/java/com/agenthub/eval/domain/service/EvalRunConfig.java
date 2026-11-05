package com.agenthub.eval.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 跑批配置快照的合并与取值。所属域：eval 的 domain。**纯函数**，无 Spring / 无 IO。
 * <p>来源三层：全局 YAML 基线 → 套件 {@code default_config_json} → 建跑 {@code config_override}。
 * 合并规则：对象逐层深合并（覆盖方优先），数组与标量整体替换 —— 与定稿 §5.3 一致。
 * <p>已消费字段：{@code openjudge.*}（含 {@code judge_model_instance_id}）、{@code report.*}、
 * {@code evalscope.*}、{@code case_timeout_ms}、{@code continue_on_case_error}。
 * 线程：无状态，可并发调用。
 */
public final class EvalRunConfig {

    /** openjudge 里指定 Judge 模型实例的字段名（定稿 §5.3）。 */
    public static final String JUDGE_MODEL_FIELD = "judge_model_instance_id";

    private EvalRunConfig() {
    }

    /**
     * 深合并配置：同名字段以 {@code override} 为准，对象递归合并。
     *
     * @param base     基线（套件默认配置），可空
     * @param override 覆盖（本次请求），可空
     * @return 合并结果；两侧都空时返回 null（不写空对象，便于判空口径统一）
     */
    public static JsonNode merge(JsonNode base, JsonNode override) {
        boolean baseBlank = base == null || base.isNull() || base.isMissingNode();
        boolean overrideBlank = override == null || override.isNull() || override.isMissingNode();
        if (baseBlank) {
            return overrideBlank ? null : override.deepCopy();
        }
        if (overrideBlank) {
            return base.deepCopy();
        }
        return mergeNode(base.deepCopy(), override);
    }

    /**
     * 读取 {@code openjudge.enabled}；缺失、类型不对一律按关闭处理。
     *
     * @param config 合并后的配置，可空
     * @return true 表示本次启用 OpenJudge
     */
    public static boolean openjudgeEnabled(JsonNode config) {
        return boolAt(objectAt(config, "openjudge"), "enabled", false);
    }

    /**
     * 读取 {@code openjudge.judge_model_instance_id}。
     * <p>只有数字或数字字符串才算有效；缺失、null、空串、乱码一律返回 null（= 本 run 不打分），
     * 不做「猜一个默认模型」的兜底 —— 打分模型必须来自显式选择。
     *
     * @param config 合并后的配置，可空
     * @return 模型实例 id；未配置返回 null
     */
    public static Long judgeModelInstanceId(JsonNode config) {
        JsonNode openjudge = objectAt(config, "openjudge");
        if (openjudge == null) {
            return null;
        }
        JsonNode node = openjudge.get(JUDGE_MODEL_FIELD);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            long value = node.asLong();
            return value > 0 ? value : null;
        }
        if (node.isTextual()) {
            String raw = node.asText() == null ? "" : node.asText().trim();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                long value = Long.parseLong(raw);
                return value > 0 ? value : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 本 run 是否真的会调用软评分：{@code enabled=true} <b>且</b> 有非空 Judge 实例 id。
     * <p>只有开关没有模型（历史行、套件默认开但建跑未选模型）时同样按关闭处理，
     * 保证「无模型 → 零 Judge 出站」。
     *
     * @param config 合并后的配置，可空
     * @return true 表示允许调 OpenJudge
     */
    public static boolean judgeEnabled(JsonNode config) {
        return openjudgeEnabled(config) && judgeModelInstanceId(config) != null;
    }

    /**
     * 把「本次建跑的打分模型选择」钉进配置快照（run 级覆盖，优先级高于 suite default 与全局 yml）。
     * <p><b>不选即关闭</b>：{@code judgeModelInstanceId == null} 时强制 {@code enabled=false} 且清空 id，
     * 避免套件默认配置或全局 yml 把打分重新打开（禁止默认偷用全局模型）。
     * <p><b>选了即启用</b>：写入选中的 id 并置 {@code enabled=true}，同样覆盖下层配置。
     *
     * @param config               已合并的配置，可空（为空时新建根对象）
     * @param judgeModelInstanceId 本 run 选中的实例 id；null 表示不打分
     * @return 新的配置节点（不修改入参）
     */
    public static ObjectNode pinJudgeSelection(JsonNode config, Long judgeModelInstanceId) {
        ObjectNode root;
        if (config != null && config.isObject()) {
            root = ((ObjectNode) config).deepCopy();
        } else {
            root = JsonNodeFactory.instance.objectNode();
        }
        ObjectNode openjudge;
        JsonNode current = root.get("openjudge");
        if (current != null && current.isObject()) {
            openjudge = (ObjectNode) current;
        } else {
            openjudge = root.putObject("openjudge");
        }
        if (judgeModelInstanceId == null) {
            // 显式置 null 而不是删字段：快照要能被读出「本 run 选择了不打分」
            openjudge.putNull(JUDGE_MODEL_FIELD);
            openjudge.put("enabled", false);
        } else {
            openjudge.put(JUDGE_MODEL_FIELD, judgeModelInstanceId);
            openjudge.put("enabled", true);
        }
        return root;
    }

    /**
     * 读取 {@code openjudge.required}；默认 false（失败不进 gate）。
     *
     * @param config 合并配置
     * @return true 表示 required（本刀仍不改 verdict，只记 status）
     */
    public static boolean openjudgeRequired(JsonNode config) {
        return boolAt(objectAt(config, "openjudge"), "required", false);
    }

    /**
     * 读取 {@code openjudge.base_url}；缺失回落 fallback。
     *
     * @param config   合并配置
     * @param fallback 全局缺省
     * @return 基址
     */
    public static String openjudgeBaseUrl(JsonNode config, String fallback) {
        String fb = fallback == null || fallback.isBlank() ? "http://127.0.0.1:18765" : fallback.trim();
        JsonNode openjudge = objectAt(config, "openjudge");
        if (openjudge == null) {
            return fb;
        }
        JsonNode node = openjudge.get("base_url");
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return fb;
        }
        return node.asText().trim();
    }

    /**
     * 读取 {@code openjudge.timeout_ms}；非法回落 fallback。
     *
     * @param config   合并配置
     * @param fallback 全局缺省
     * @return 正数毫秒
     */
    public static long openjudgeTimeoutMs(JsonNode config, long fallback) {
        long fb = Math.max(1_000L, fallback);
        JsonNode openjudge = objectAt(config, "openjudge");
        if (openjudge == null) {
            return fb;
        }
        JsonNode node = openjudge.get("timeout_ms");
        if (node == null || !node.isNumber()) {
            return fb;
        }
        long value = node.asLong();
        return value >= 1_000L ? value : fb;
    }

    /**
     * 读取 {@code openjudge.default_graders}；缺失回落 fallback。
     *
     * @param config   合并配置
     * @param fallback 全局缺省
     * @return grader 名列表（可能为空）
     */
    public static java.util.List<String> openjudgeDefaultGraders(JsonNode config, java.util.List<String> fallback) {
        java.util.List<String> fb = fallback == null ? java.util.List.of() : fallback;
        JsonNode openjudge = objectAt(config, "openjudge");
        if (openjudge == null) {
            return fb;
        }
        JsonNode node = openjudge.get("default_graders");
        if (node == null || !node.isArray() || node.isEmpty()) {
            return fb;
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (JsonNode one : node) {
            if (one != null && one.isTextual() && !one.asText().isBlank()) {
                list.add(one.asText().trim());
            }
        }
        return list.isEmpty() ? fb : list;
    }

    /**
     * case 级是否发 OpenJudge：**只认 run 级开关**。
     * <p>为什么不再看 {@code scoring.openjudge.enabled}：控制台没有 case 级打分开关，
     * 套件多由样例/历史 JSON 导入，case 上常带着复制来的 {@code enabled=false}；
     * 若让它否决，就会出现「建跑时明明选了打分模型，结果 Judge 列全部未启用」。
     * run 级已由 {@link #pinJudgeSelection} 钉死为「选了模型才 true」，
     * 因此只需跟随 run 级即可；case 级 scoring 仍用于覆盖 graders / threshold 等参数。
     *
     * @param scoringJson case.scoring_json 节点，可空（保留入参以便将来恢复细粒度开关）
     * @param runEnabled  run 级是否启用（已含「有打分模型」校验）
     * @return 本 case 是否发 OpenJudge HTTP
     */
    public static boolean caseOpenjudgeEnabled(JsonNode scoringJson, boolean runEnabled) {
        return runEnabled;
    }

    /**
     * 读取 case {@code scoring.openjudge.graders}；缺省空列表（调用方再补 default）。
     *
     * @param scoringJson scoring 节点
     * @return grader 列表
     */
    public static java.util.List<String> caseOpenjudgeGraders(JsonNode scoringJson) {
        JsonNode openjudge = objectAt(scoringJson, "openjudge");
        if (openjudge == null) {
            return java.util.List.of();
        }
        JsonNode node = openjudge.get("graders");
        if (node == null || !node.isArray()) {
            return java.util.List.of();
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (JsonNode one : node) {
            if (one != null && one.isTextual() && !one.asText().isBlank()) {
                list.add(one.asText().trim());
            }
        }
        return list;
    }

    /**
     * 读取 {@code evalscope.enabled}；缺失继承 {@code fallback}（全局默认 false）。
     *
     * @param config   合并后的 run/suite 配置，可空
     * @param fallback 全局 YAML 缺省
     * @return 本 run 是否允许走 EvalScope 桥
     */
    public static boolean evalscopeEnabled(JsonNode config, boolean fallback) {
        return boolAt(objectAt(config, "evalscope"), "enabled", fallback);
    }

    /**
     * 读取 {@code evalscope.base_url}；缺失回落 fallback（可空字符串）。
     *
     * @param config   合并配置
     * @param fallback 全局缺省
     * @return 基址；可能为空
     */
    public static String evalscopeBaseUrl(JsonNode config, String fallback) {
        String fb = fallback == null ? "" : fallback.trim();
        JsonNode node = objectAt(config, "evalscope");
        if (node == null) {
            return fb;
        }
        JsonNode url = node.get("base_url");
        if (url == null || !url.isTextual() || url.asText().isBlank()) {
            return fb;
        }
        return url.asText().trim();
    }

    /**
     * 读取 {@code evalscope.timeout_ms}；非法回落 fallback。
     *
     * @param config   合并配置
     * @param fallback 全局缺省
     * @return 正数毫秒
     */
    public static long evalscopeTimeoutMs(JsonNode config, long fallback) {
        long fb = Math.max(1_000L, fallback);
        JsonNode node = objectAt(config, "evalscope");
        if (node == null) {
            return fb;
        }
        JsonNode timeout = node.get("timeout_ms");
        if (timeout == null || !timeout.isNumber()) {
            return fb;
        }
        long value = timeout.asLong();
        return value >= 1_000L ? value : fb;
    }

    /**
     * {@code report.write_workspace}；缺省 fallback。
     */
    public static boolean reportWriteWorkspace(JsonNode config, boolean fallback) {
        return boolAt(objectAt(config, "report"), "write_workspace", fallback);
    }

    /**
     * {@code report.markdown}；缺省 fallback。
     */
    public static boolean reportMarkdown(JsonNode config, boolean fallback) {
        return boolAt(objectAt(config, "report"), "markdown", fallback);
    }

    private static JsonNode objectAt(JsonNode root, String field) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode node = root.get(field);
        return node != null && node.isObject() ? node : null;
    }

    private static boolean boolAt(JsonNode object, String field, boolean fallback) {
        if (object == null) {
            return fallback;
        }
        JsonNode enabled = object.get(field);
        if (enabled == null) {
            return fallback;
        }
        if (enabled.isBoolean()) {
            return enabled.asBoolean();
        }
        if (enabled.isInt()) {
            return enabled.asInt() == 1;
        }
        return fallback;
    }

    /**
     * 读取 {@code case_timeout_ms}；缺失或非法时回落 {@code fallbackMs}。
     *
     * @param config     合并配置，可空
     * @param fallbackMs 全局缺省
     * @return 正数毫秒
     */
    public static long caseTimeoutMs(JsonNode config, long fallbackMs) {
        long fallback = Math.max(1_000L, fallbackMs);
        if (config == null || !config.isObject()) {
            return fallback;
        }
        JsonNode node = config.get("case_timeout_ms");
        if (node == null || !node.isNumber()) {
            return fallback;
        }
        long value = node.asLong();
        return value >= 1_000L ? value : fallback;
    }

    /**
     * 读取 {@code continue_on_case_error}；缺失时回落 {@code fallback}。
     *
     * @param config   合并配置，可空
     * @param fallback 全局缺省
     * @return 是否继续
     */
    public static boolean continueOnCaseError(JsonNode config, boolean fallback) {
        if (config == null || !config.isObject()) {
            return fallback;
        }
        JsonNode node = config.get("continue_on_case_error");
        if (node == null) {
            return fallback;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt()) {
            return node.asInt() == 1;
        }
        return fallback;
    }

    /**
     * 递归合并：target 就地把 source 的字段合并进来，返回 target。
     *
     * @param target 被覆盖的节点
     * @param source 覆盖来源
     * @return target 本身
     */
    private static JsonNode mergeNode(JsonNode target, JsonNode source) {
        if (!(target instanceof ObjectNode targetObject) || !source.isObject()) {
            // 任一侧不是对象（数组/标量）：整体替换，避免出现「半个数组合并」的怪语义
            return source.deepCopy();
        }
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode current = targetObject.get(entry.getKey());
            JsonNode incoming = entry.getValue();
            if (current != null && current.isObject() && incoming.isObject()) {
                targetObject.set(entry.getKey(), mergeNode(current, incoming));
            } else {
                targetObject.set(entry.getKey(), incoming.deepCopy());
            }
        }
        return targetObject;
    }
}
