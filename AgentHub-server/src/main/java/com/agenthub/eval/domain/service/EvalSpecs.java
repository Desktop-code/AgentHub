package com.agenthub.eval.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.domain.model.EvalCase;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalSuite;
import com.agenthub.eval.domain.model.EvalSuiteSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 套件/用例 JSON 的收口校验与转换。所属域：eval 的 domain。
 * <p>为什么集中在 domain：导入接口、classpath 样例装载、单测三条路径都必须走同一套规则，
 * 否则会出现「接口能存、样例装不进」或反过来的分裂。
 * <p>校验策略：一律抛 {@link ErrorCode#BAD_REQUEST}，让事务在写库前就回滚，
 * 保证不落「半截版本」（版本号推进了但用例只写了一部分）。
 * <p>无状态，可并发调用。
 */
public final class EvalSpecs {

    /** suite_key / case_key 列宽 */
    public static final int KEY_MAX = 64;
    /** name 列宽 */
    public static final int NAME_MAX = 128;
    /** description 列宽 */
    public static final int DESCRIPTION_MAX = 512;
    /** title 列宽 */
    public static final int TITLE_MAX = 256;
    /** 单套件用例数上限：P0 手写用例集，超过说明该拆套件 */
    public static final int CASES_MAX = 500;
    /** 单 case 标签数上限 */
    public static final int TAGS_MAX = 20;
    /** 单标签长度上限 */
    public static final int TAG_MAX = 32;
    /** 默认语言 */
    public static final String DEFAULT_LOCALE = "zh-CN";

    private EvalSpecs() {
    }

    /**
     * 校验并返回归一化后的套件 spec（原地 trim，不改语义）。
     * <p>导入到已有套件时 suite_key / name 可空，由应用层补齐；这里只校验「给了就合法」。
     *
     * @param spec 待校验的套件 JSON
     * @return 同一实例，字段已 trim
     * @throws BizException 必填缺失、超长、结构非法
     */
    public static EvalSuiteSpec normalize(EvalSuiteSpec spec) {
        if (spec == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "套件 JSON 不能为空");
        }
        spec.setSuiteKey(optionalText(spec.getSuiteKey(), KEY_MAX, "suite_key"));
        spec.setName(optionalText(spec.getName(), NAME_MAX, "名称"));
        spec.setDescription(optionalText(spec.getDescription(), DESCRIPTION_MAX, "描述"));
        spec.setLocale(optionalText(spec.getLocale(), 16, "语言"));

        // default_config 必须是对象，否则运行期解析不出 max_parallel / rule 开关
        JsonNode config = spec.getDefaultConfig();
        if (config != null && !config.isNull() && !config.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "default_config 必须是 JSON 对象");
        }

        List<EvalCaseSpec> cases = spec.getCases();
        if (cases == null) {
            spec.setCases(new ArrayList<>());
            return spec;
        }
        if (cases.size() > CASES_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单个套件最多 " + CASES_MAX + " 条用例");
        }
        Set<String> seen = new HashSet<>();
        for (EvalCaseSpec one : cases) {
            normalizeCase(one);
            // 同一版本内 case_key 必须唯一：库里有 UK，但先在前置校验给出可读错误
            if (!seen.add(one.getCaseKey())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "case_key 重复：" + one.getCaseKey());
            }
        }
        return spec;
    }

    /**
     * 校验单条用例。
     *
     * @param one 用例
     * @throws BizException 必填缺失、超长或结构非法
     */
    public static void normalizeCase(EvalCaseSpec one) {
        if (one == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用例不能为空");
        }
        one.setCaseKey(requiredText(one.getCaseKey(), KEY_MAX, "case_key"));
        one.setTitle(requiredText(one.getTitle(), TITLE_MAX, "标题"));

        // input 必填：评测最基础的信息是「发什么」。缺 message 的用例跑起来只能空转
        JsonNode input = one.getInput();
        if (input == null || input.isNull() || !input.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "用例 " + one.getCaseKey() + " 的 input 必须是 JSON 对象");
        }
        JsonNode message = input.get("message");
        if (message == null || !message.isTextual() || message.asText().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "用例 " + one.getCaseKey() + " 的 input.message 不能为空");
        }

        // expect / scoring 允许为空对象，但给了就必须是对象
        requireObjectOrNull(one.getExpect(), one.getCaseKey(), "expect");
        requireObjectOrNull(one.getScoring(), one.getCaseKey(), "scoring");
        requireAssertions(one);

        List<String> tags = one.getTags();
        if (tags != null) {
            if (tags.size() > TAGS_MAX) {
                throw new BizException(ErrorCode.BAD_REQUEST, "单个用例最多 " + TAGS_MAX + " 个标签");
            }
            List<String> cleaned = new ArrayList<>();
            for (String tag : tags) {
                String text = optionalText(tag, TAG_MAX, "标签");
                if (text != null) {
                    cleaned.add(text);
                }
            }
            one.setTags(cleaned);
        } else {
            one.setTags(new ArrayList<>());
        }
        if (one.getEnabled() == null) {
            one.setEnabled(EvalSuite.ENABLED);
        } else if (one.getEnabled() != EvalSuite.ENABLED && one.getEnabled() != EvalSuite.DISABLED) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用例启用状态只能是 1 或 0");
        }
        if (one.getSortOrder() == null) {
            one.setSortOrder(0);
        }
    }

    /**
     * 把 spec 的用例转成待入库的领域行（固定版本号）。
     * <p>调用前必须先 {@link #normalize(EvalSuiteSpec)}，这里不再重复校验必填，
     * 只做 JSON → 文本的序列化（库列是 JSON 类型，驱动按字符串写入）。
     *
     * @param suiteId    套件主键
     * @param version    钉死版本
     * @param spec       已归一化的套件
     * @param operatorId 操作人
     * @param json       序列化器
     * @return 用例行，顺序与 spec.cases 一致
     */
    public static List<EvalCase> toCaseRows(Long suiteId, int version, EvalSuiteSpec spec,
                                            Long operatorId, ObjectMapper json) {
        List<EvalCase> rows = new ArrayList<>();
        List<EvalCaseSpec> cases = spec.getCases() == null ? List.of() : spec.getCases();
        int index = 0;
        for (EvalCaseSpec one : cases) {
            EvalCase row = new EvalCase();
            row.setSuiteId(suiteId);
            row.setSuiteVersion(version);
            row.setCaseKey(one.getCaseKey());
            row.setTitle(one.getTitle());
            row.setTagsJson(writeJson(json, one.getTags()));
            row.setInputJson(writeJson(json, one.getInput()));
            row.setExpectJson(writeJson(json, one.getExpect()));
            row.setAssertionsJson(writeJson(json, one.getAssertions()));
            row.setScoringJson(writeJson(json, one.getScoring()));
            row.setEnabled(one.getEnabled() == null ? EvalSuite.ENABLED : one.getEnabled());
            // sort_order 未显式给出时按数组顺序，保证回读顺序稳定
            row.setSortOrder(one.getSortOrder() == null ? index * 10 : one.getSortOrder());
            row.setCreatedBy(operatorId);
            rows.add(row);
            index++;
        }
        return rows;
    }

    /**
     * 解析 JSON 文本为 spec（与 classpath 样例、HTTP 导入共用）。
     *
     * @param raw  文本（允许 UTF-8 BOM）
     * @param json 解析器
     * @return 结构化 spec
     * @throws BizException 文本为空或不是合法 JSON；字段类型不符时带中文摘要
     */
    public static EvalSuiteSpec parse(String raw, ObjectMapper json) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "套件 JSON 不能为空");
        }
        // 记事本另存 UTF-8 常带 BOM；不剥会导致「看起来合法、解析却失败」
        String text = stripBom(raw).trim();
        if (text.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "套件 JSON 不能为空");
        }
        try {
            return fromTree(json.readTree(text), json);
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "套件 JSON 格式不正确：" + brief(ex));
        }
    }

    /**
     * 从已解析的 JsonNode 转为 spec 并 normalize。
     * <p>若根节点是字符串，按「二次编码的 JSON 文本」再解一层（兼容误把文件内容当字符串字段提交）。
     *
     * @param node JSON 树
     * @param json 解析器
     * @return 已归一化的 spec
     * @throws BizException 空树、类型不匹配、缺必填
     */
    public static EvalSuiteSpec fromTree(JsonNode node, ObjectMapper json) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "套件 JSON 不能为空");
        }
        try {
            JsonNode root = node;
            // 兼容 body 为 "\"{...}\"" 或 { "content": "{...}" } 误用
            if (root.isTextual()) {
                return parse(root.asText(), json);
            }
            if (root.isObject() && root.size() == 1 && root.has("content") && root.get("content").isTextual()) {
                return parse(root.get("content").asText(), json);
            }
            EvalSuiteSpec spec = json.treeToValue(root, EvalSuiteSpec.class);
            if (spec == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "套件 JSON 不能为空");
            }
            return normalize(spec);
        } catch (BizException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "套件 JSON 格式不正确：" + brief(ex));
        } catch (IllegalArgumentException ex) {
            // treeToValue 在部分类型失败时抛 IllegalArgumentException 包住 Jackson 原因
            throw new BizException(ErrorCode.BAD_REQUEST, "套件 JSON 格式不正确：" + brief(ex));
        }
    }

    /** 去掉 UTF-8 BOM，避免记事本粘贴导致假「格式错误」。 */
    public static String stripBom(String raw) {
        if (raw != null && !raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
            return raw.substring(1);
        }
        return raw;
    }

    /**
     * 序列化 JsonNode 为库内文本；空节点写 null（不写空串，便于判空统一）。
     *
     * @param json 序列化器
     * @param node 节点，可空
     * @return 文本或 null
     * @throws BizException 序列化失败（几乎不可能，兜底成 400 而不是 500）
     */
    public static String writeJson(ObjectMapper json, Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof JsonNode jsonNode && (jsonNode.isNull() || jsonNode.isMissingNode())) {
            return null;
        }
        if (node instanceof List<?> list && list.isEmpty()) {
            return "[]";
        }
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new BizException(ErrorCode.BAD_REQUEST, "JSON 序列化失败：" + brief(ex));
        }
    }

    /**
     * @param raw   原文
     * @param max   最大长度
     * @param label 字段中文名
     * @return trim 后的文本
     * @throws BizException 空白或超长
     */
    public static String requiredText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + max);
        }
        return text;
    }

    /**
     * @param raw   原文，可空
     * @param max   最大长度
     * @param label 字段中文名
     * @return 空白为 null
     * @throws BizException 超长
     */
    public static String optionalText(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > max) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + max);
        }
        return text;
    }

    /**
     * suite_key 只允许字母数字与 . _ -，避免出现 `/`、空格等在 URL/日志里难处理的字符。
     *
     * @param suiteKey 已 trim 的逻辑键
     * @return 原值
     * @throws BizException 含非法字符
     */
    public static String checkSuiteKeyPattern(String suiteKey) {
        if (!suiteKey.matches("[A-Za-z0-9._-]+")) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "suite_key 只能包含字母、数字、点、下划线和中划线");
        }
        return suiteKey;
    }

    /**
     * 语言标签归一：空则 zh-CN。
     *
     * @param locale 原文
     * @return 归一后的 locale
     */
    public static String localeOrDefault(String locale) {
        return locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale.trim();
    }

    /**
     * expect / scoring 若给出必须是对象。
     */
    private static void requireObjectOrNull(JsonNode node, String caseKey, String field) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "用例 " + caseKey + " 的 " + field + " 必须是 JSON 对象");
        }
    }

    /**
     * assertions 若给出必须是数组，且每项带 type 字符串。
     * <p>类型缺失进库后 RuleScorer 会判该条失败；导入阶段宁可 400 拦下。
     */
    private static void requireAssertions(EvalCaseSpec one) {
        JsonNode assertions = one.getAssertions();
        if (assertions == null || assertions.isNull()) {
            return;
        }
        if (!assertions.isArray()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "用例 " + one.getCaseKey() + " 的 assertions 必须是数组");
        }
        for (JsonNode item : assertions) {
            JsonNode type = item.get("type");
            if (type == null || !type.isTextual() || type.asText().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "用例 " + one.getCaseKey() + " 的断言缺少 type");
            }
            String name = type.asText().trim().toLowerCase(Locale.ROOT);
            if (name.equals("llm_judge_inline")) {
                // 定稿 §3.4：内嵌再调 LLM 禁止进 RuleScorer，只在 OpenJudge 通道做
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "断言 llm_judge_inline 不允许出现在 assertions，请走 scoring.openjudge");
            }
        }
    }

    /**
     * 异常摘要：只取首行，避免把整段 Jackson 位置信息塞进响应。
     */
    private static String brief(Exception ex) {
        String message = ex.getMessage() == null ? "无法解析" : ex.getMessage();
        int cut = message.indexOf('\n');
        return cut > 0 ? message.substring(0, cut) : message;
    }
}
