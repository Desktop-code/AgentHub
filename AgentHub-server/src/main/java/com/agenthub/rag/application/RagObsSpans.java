package com.agenthub.rag.application;

import com.agenthub.obs.domain.ObsToolSpanContext;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.spi.rag.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库检索的 Trace 子步埋点。所属域：rag 的 application。
 * <p>
 * 目标：一次 {@code searchKnowledgeBase} 工具调用，在现有 TOOL span 下按检索链路拆出
 * {@code tool.input → rewrite → retrieve.vector → retrieve.bm25 → rrf → rerank → tool.output}
 * 子步，露出与评测检索链同口径的中间结果（片段 id、摘要、四类分数、跳过原因）。
 * <p>
 * 边界：
 * <ul>
 *   <li>只在工具调用线程且已绑定 {@link ObsToolSpanContext} 时生效；评测/接口直调无 trace 时全部空操作；</li>
 *   <li>只读现有检索参数与命中，不改检索算法、不新增业务表字段；</li>
 *   <li>正文只写截断摘要，禁止把完整文档 / 密钥灌进 span；</li>
 *   <li>埋点失败由 obs 侧吞掉，不能影响检索主流程。</li>
 * </ul>
 * 线程：会话只在创建它的工具线程内传递（ThreadLocal），与检索同步执行一致。
 */
public final class RagObsSpans {

    private static final Logger log = LoggerFactory.getLogger(RagObsSpans.class);

    /** 工具入参：原问、kb、topK、覆盖项、Agent 重排实例 */
    public static final String TOOL_INPUT = "tool.input";
    /** 查询改写：本期运行时未执行，只记 skip 原因 */
    public static final String REWRITE = "rewrite";
    /** 向量召回：id / 摘要 / 向量分 */
    public static final String RETRIEVE_VECTOR = "retrieve.vector";
    /** BM25 召回：id / 摘要 / BM25 分 */
    public static final String RETRIEVE_BM25 = "retrieve.bm25";
    /** RRF 融合：融合分与排序后列表 */
    public static final String RRF = "rrf";
    /** 精排：精排分与最终进上下文的片段 */
    public static final String RERANK = "rerank";
    /** 工具输出：返回给 Agent 的正文 / 引用 */
    public static final String TOOL_OUTPUT = "tool.output";

    /** 单步正文上限：span 是排障视图，不是文档仓库 */
    private static final int MAX_TEXT = 4000;
    /** 失败摘要上限 */
    private static final int MAX_ERROR = 500;
    /** 检索句列上限（与 retrieve_query 列语义一致） */
    private static final int MAX_QUERY = 512;
    /** 片段明细最多展示条数；超出只给计数 */
    private static final int MAX_ROWS = 30;
    /** 单条片段摘要上限 */
    private static final int MAX_SNIPPET = 120;
    /** brief 摘要上限 */
    private static final int MAX_BRIEF = 120;

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 当前工具线程上的检索埋点会话；没有 trace 时不写入 */
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private RagObsSpans() {
    }

    /**
     * 开始一次工具级检索埋点。
     * <p>父 span 必须是「本次工具调用」：只接受 RagContextAgentTool 在工具线程绑定的
     * {@link ObsToolSpanContext} 锚点；没有锚点（评测/接口直调、无 trace）时全部空操作，
     * 绝不退回到 AGENT/LLM 当前叶，避免把检索子步挂错父节点。
     * <p>锚点里有 trace 但没有 TOOL 行时打一行 warn：这种情况是埋点断了，必须能排查，
     * 不能和「本来就没开 trace」混为一谈。
     *
     * @param agentId 本轮 Agent；可空，锚点里有值时以锚点为准
     * @return 埋点会话；无锚点时为空操作会话
     */
    public static Session begin(Long agentId) {
        ObsToolSpanContext.Anchor anchor = ObsToolSpanContext.current();
        if (anchor == null || anchor.scope() == null) {
            return Session.INACTIVE;
        }
        if (anchor.toolSpanId() == null) {
            // 有 trace 却没锚到 TOOL 行：父节点不可靠，宁可少记也不挂错
            log.warn("[RAG][trace] 有 trace 但未匹配到 TOOL span，检索子步跳过 agentId={}", agentId);
            return Session.INACTIVE;
        }
        Long resolvedAgent = anchor.agentId() != null ? anchor.agentId() : agentId;
        Session session = new Session(anchor.scope(), anchor.toolSpanId(), resolvedAgent);
        CURRENT.set(session);
        return session;
    }

    /**
     * 取当前线程会话；检索服务在工具调用内读取它写子步。
     *
     * @return 活动会话；没有时为空操作会话
     */
    public static Session currentOrInactive() {
        Session session = CURRENT.get();
        return session == null ? Session.INACTIVE : session;
    }

    /**
     * 把请求参数拍成缩进 JSON，供 tool.input 展示。
     *
     * @param fields 键 → 值；空则返回 "{}"
     * @return JSON 文本；序列化失败退化为 Map.toString
     */
    public static String jsonText(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return "{}";
        }
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(fields);
        } catch (Exception ex) {
            return fields.toString();
        }
    }

    /**
     * 判断某知识库是否开了查询改写（覆盖项优先于 KB 配置）。
     * <p>运行时并不执行改写，这里只用于给出准确的 skip 原因。
     *
     * @param kbConfigJson  KB retrieval_config，可空
     * @param overridesJson Agent 绑定覆盖项，可空
     * @return 是否开启
     */
    public static boolean rewriteRequested(String kbConfigJson, String overridesJson) {
        Boolean over = boolField(overridesJson, "rewriteEnabled");
        if (over != null) {
            return over;
        }
        Boolean kb = boolField(kbConfigJson, "rewriteEnabled");
        return kb != null && kb;
    }

    /**
     * 读取 JSON 对象里的布尔字段；非法 JSON / 非布尔值都按「未配置」处理。
     */
    private static Boolean boolField(String json, String key) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            if (node != null && node.isObject() && node.has(key) && node.get(key).isBoolean()) {
                return node.get(key).asBoolean();
            }
        } catch (Exception ex) {
            // 配置坏值不应打断检索，也不该在 trace 里伪造开关
            return null;
        }
        return null;
    }

    /**
     * 一次工具调用内的检索埋点会话。
     * <p>非线程安全：只在创建它的工具线程内使用。
     */
    public static final class Session {

        /** 无 trace 时的空实现，所有方法 no-op */
        private static final Session INACTIVE = new Session(null, null, null);

        private final ObsTraceScope scope;
        /** 本次工具调用的 TOOL 行 id；为空说明没拿到锚点，整段埋点空操作 */
        private final Long toolSpanId;
        private final Long agentId;
        /** 同名 skip 只写一次（多库场景的 run 级步骤） */
        private final Set<String> skippedOnce = ConcurrentHashMap.newKeySet();
        /** tool.input 记录的检索句 / topK，供 tool.output 对齐 */
        private String query;
        private Integer topK;

        private Session(ObsTraceScope scope, Long toolSpanId, Long agentId) {
            this.scope = scope;
            this.toolSpanId = toolSpanId;
            this.agentId = agentId;
        }

        /**
         * @return 是否真的会写 span
         */
        public boolean isActive() {
            return scope != null;
        }

        /**
         * 记工具入参：只开一次，后续子步按检索流程依次追加。
         *
         * @param text   入参正文（建议缩进 JSON）
         * @param query  原问
         * @param topK   工具入参 topK
         * @param attrs  扁平统计（kbCount / kbIds / agentRerankModelId）
         */
        public void input(String text, String query, int topK, Map<String, Object> attrs) {
            if (!isActive()) {
                return;
            }
            this.query = query;
            this.topK = topK;
            Map<String, Object> fields = new LinkedHashMap<>();
            if (attrs != null) {
                fields.putAll(attrs);
            }
            // 卡片头直接给一句人话，不必展开 JSON
            fields.put("brief", clip("检索入参：" + (query == null ? "" : query) + "（topK=" + topK + "）",
                    MAX_BRIEF));
            step(TOOL_INPUT, null, query, topK, text).ok(null, fields);
        }

        /**
         * 记工具输出：与返回给 Agent 的文本一致，超长截断并标 truncated。
         *
         * @param text     返回正文
         * @param outcome  ok / no_hit / no_binding / unavailable / error
         * @param hitCount 命中条数；可空
         * @param error    是否按失败标色
         */
        public void output(String text, String outcome, Integer hitCount, boolean error) {
            if (!isActive()) {
                return;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("outcome", outcome);
            fields.put("chars", text == null ? 0 : text.length());
            fields.put("truncated", text != null && text.length() > MAX_TEXT);
            fields.put("brief", outputBrief(outcome, hitCount));
            if (hitCount != null) {
                fields.put("hitCount", hitCount);
            }
            step(TOOL_OUTPUT, null, query, topK, null).finish(
                    error ? ObsStatus.ERROR : ObsStatus.OK, text, error ? outcome : null, fields);
        }

        /**
         * 记一个 skip 子步：状态仍为 OK，用 attrs.skipped / skipReason 表达「本步未发生」。
         *
         * @param name   稳定步骤名
         * @param kbId   知识库；run 级步骤可空
         * @param query  检索句；可空
         * @param topK   条数；可空
         * @param reason 机器可读原因（rewrite_disabled / rrf_disabled …）
         * @param text   人话说明
         */
        public void skip(String name, Long kbId, String query, Integer topK, String reason, String text) {
            if (!isActive()) {
                return;
            }
            step(name, kbId, query, topK, null).skip(reason, text, null);
        }

        /**
         * 同名步骤只记一次 skip；用于多库共享的 run 级步骤（如 rewrite）。
         *
         * @param name   稳定步骤名
         * @param kbId   知识库；可空
         * @param query  检索句；可空
         * @param topK   条数；可空
         * @param reason 机器可读原因
         * @param text   人话说明
         */
        public void skipOnce(String name, Long kbId, String query, Integer topK, String reason, String text) {
            if (!isActive() || !skippedOnce.add(name)) {
                return;
            }
            skip(name, kbId, query, topK, reason, text);
        }

        /**
         * 开一个检索子步并挂到本次 TOOL span 下。
         *
         * @param name      稳定步骤名
         * @param kbId      知识库；可空
         * @param query     检索句；可空
         * @param topK      条数；可空
         * @param inputText 入参正文；可空
         * @return 子步句柄；无 trace 或写入失败时为空操作
         */
        public Step step(String name, Long kbId, String query, Integer topK, String inputText) {
            if (!isActive() || name == null || name.isBlank()) {
                return Step.INACTIVE;
            }
            // 父固定为本次工具调用：会话能创建就说明锚点里有 toolSpanId
            Long spanId = scope.open(SpanType.RETRIEVE, name.trim(), agentId, toolSpanId,
                    clip(inputText, MAX_TEXT));
            if (spanId == null) {
                return Step.INACTIVE;
            }
            return new Step(scope, spanId, name.trim(), kbId, clip(query, MAX_QUERY), topK);
        }

        /**
         * 结束会话并清理工具线程 ThreadLocal；由 {@link RagSearchTool} 的 finally 调用。
         */
        public void end() {
            if (CURRENT.get() == this) {
                CURRENT.remove();
            }
        }
    }

    /**
     * 单个检索子步的写入句柄；重复 close 会被忽略，保证失败路径不会写两条终态。
     */
    public static final class Step {

        private static final Step INACTIVE = new Step(null, null, null, null, null, null);

        private final ObsTraceScope scope;
        private final Long spanId;
        private final String name;
        private final Long kbId;
        private final String query;
        private final Integer topK;
        private boolean finished;

        private Step(ObsTraceScope scope, Long spanId, String name, Long kbId, String query, Integer topK) {
            this.scope = scope;
            this.spanId = spanId;
            this.name = name;
            this.kbId = kbId;
            this.query = query;
            this.topK = topK;
        }

        /**
         * 正常结束，只写输出。
         *
         * @param output 输出正文；可空
         * @param extra  扁平统计
         * @return this，便于链式收尾
         */
        public Step ok(String output, Map<String, Object> extra) {
            return finish(ObsStatus.OK, output, null, extra);
        }

        /**
         * 正常结束，输出一段说明（用于「精排回退原序」等非命中场景）。
         *
         * @param output 说明文本
         * @param extra  扁平统计
         * @return this
         */
        public Step text(String output, Map<String, Object> extra) {
            return finish(ObsStatus.OK, output, null, extra);
        }

        /**
         * 标记本步跳过：状态 OK，attrs 带 skipped / skipReason，泳道仍能看到顺序。
         *
         * @param reason 机器可读原因
         * @param text   人话说明
         * @param extra  额外统计
         * @return this
         */
        public Step skip(String reason, String text, Map<String, Object> extra) {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (extra != null) {
                fields.putAll(extra);
            }
            fields.put("skipped", true);
            fields.put("skipReason", reason == null || reason.isBlank() ? "unknown" : reason);
            return finish(ObsStatus.OK, text, null, fields);
        }

        /**
         * 记一组召回 / 融合 / 精排结果：片段明细写 output，计数写列与 attrs。
         *
         * @param label       展示前缀，如「向量召回」
         * @param hits        命中列表；可空
         * @param rawCount    过滤前原始条数；可空
         * @param emptyReason 零命中的机器原因；有命中时忽略
         * @param extra       额外统计
         * @return this
         */
        public Step hits(String label, List<SearchResult> hits, Integer rawCount,
                         String emptyReason, Map<String, Object> extra) {
            if (scope == null || spanId == null) {
                return this;
            }
            int count = hits == null ? 0 : hits.size();
            Map<String, Object> fields = new LinkedHashMap<>();
            if (extra != null) {
                fields.putAll(extra);
            }
            fields.put("hitCount", count);
            if (rawCount != null) {
                fields.put("rawCount", rawCount);
            }
            if (count == 0 && emptyReason != null) {
                fields.put("emptyReason", emptyReason);
            }
            return finish(ObsStatus.OK, formatHits(label, hits, rawCount, emptyReason), null, fields);
        }

        /**
         * 失败结束：正文留空，errorSummary 写异常类型与 message（obs 侧还会再打码）。
         *
         * @param ex 异常；可空
         * @return this
         */
        public Step fail(Throwable ex) {
            if (scope == null || spanId == null || finished) {
                return this;
            }
            String type = ex == null ? "unknown" : ex.getClass().getSimpleName();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("errorType", type);
            fields.put("brief", clip("失败：" + type, MAX_BRIEF));
            String message = ex == null || ex.getMessage() == null ? type : type + ": " + ex.getMessage();
            return finish(ObsStatus.ERROR, null, message, fields);
        }

        /**
         * 统一收尾：写终态、列、attrs 与 latency（latency 由 obs 侧按开工时刻算）。
         *
         * @param status       终态
         * @param output       输出正文
         * @param errorSummary 失败摘要
         * @param extra        扁平统计
         * @return this
         */
        public Step finish(ObsStatus status, String output, String errorSummary, Map<String, Object> extra) {
            if (scope == null || spanId == null || finished) {
                return this;
            }
            finished = true;
            ObsStatus finalStatus = status == null ? ObsStatus.OK : status;

            Map<String, Object> fields = new LinkedHashMap<>();
            if (extra != null) {
                fields.putAll(extra);
            }
            // brief 放在 attrs 里，列表/泳道卡片直接显示，不必展开 IO
            fields.putIfAbsent("brief", briefOf(finalStatus, output, fields));

            SpanClose close = new SpanClose();
            close.setStatus(finalStatus);
            close.setOutputText(clip(output, MAX_TEXT));
            close.setErrorSummary(clip(errorSummary, MAX_ERROR));
            close.setRetrieveKbId(kbId);
            close.setRetrieveQuery(clip(query, MAX_QUERY));
            close.setRetrieveTopK(topK);
            Object hitCount = fields.get("hitCount");
            if (hitCount instanceof Number number) {
                close.setRetrieveHitCount(number.intValue());
            }
            close.setAttrsJson(ObsAttrs.withFields(null, fields));
            scope.close(spanId, close);
            return this;
        }

        /**
         * 生成卡片摘要：失败优先、跳过次之、有计数给计数，否则取正文首行。
         */
        private String briefOf(ObsStatus status, String output, Map<String, Object> fields) {
            if (status == ObsStatus.ERROR) {
                Object errorType = fields.get("errorType");
                return clip("失败：" + (errorType == null ? "未知异常" : errorType), MAX_BRIEF);
            }
            if (Boolean.TRUE.equals(fields.get("skipped"))) {
                return clip(firstLine(output), MAX_BRIEF);
            }
            Object hitCount = fields.get("hitCount");
            if (hitCount instanceof Number number) {
                return clip(name + "：" + number.intValue() + " 条", MAX_BRIEF);
            }
            return clip(firstLine(output), MAX_BRIEF);
        }
    }

    /**
     * 工具输出卡片摘要：用业务结果词，避免把 JSON 首行当摘要。
     */
    private static String outputBrief(String outcome, Integer hitCount) {
        if ("ok".equals(outcome)) {
            return "返回 " + (hitCount == null ? 0 : hitCount) + " 条命中";
        }
        if ("no_hit".equals(outcome)) {
            return "无命中，返回固定拒答";
        }
        if ("no_binding".equals(outcome)) {
            return "未绑定知识库";
        }
        if ("unavailable".equals(outcome)) {
            return "知识库不可用";
        }
        return "检索失败";
    }

    /**
     * 拼召回 / 融合 / 精排的片段明细；零命中时写机器原因，避免只看空文本。
     *
     * @param label       展示前缀
     * @param hits        命中列表
     * @param rawCount    过滤前原始条数；可空
     * @param emptyReason 零命中原因
     * @return 可读文本
     */
    private static String formatHits(String label, List<SearchResult> hits, Integer rawCount, String emptyReason) {
        int count = hits == null ? 0 : hits.size();
        if (count == 0) {
            StringBuilder empty = new StringBuilder(label == null ? "" : label).append(" 0 条命中");
            if (rawCount != null && rawCount > 0) {
                empty.append("（原始召回 ").append(rawCount).append(" 条已全部被过滤）");
            }
            if (emptyReason != null && !emptyReason.isBlank()) {
                empty.append("；原因：").append(emptyReason);
            }
            return empty.toString();
        }
        StringBuilder text = new StringBuilder();
        text.append(label == null ? "命中" : label).append(' ').append(count).append(" 条");
        if (rawCount != null && rawCount > count) {
            text.append("（原始 ").append(rawCount).append(" 条，阈值/截断后 ").append(count).append(" 条）");
        }
        text.append('\n');
        int limit = Math.min(count, MAX_ROWS);
        for (int i = 0; i < limit; i++) {
            SearchResult hit = hits.get(i);
            text.append(i + 1).append(". chunkId=").append(value(hit.getChunkId()))
                    .append(" docId=").append(value(hit.getDocId()))
                    .append(" kbId=").append(value(hit.getKbId()))
                    .append(" score=").append(fmt(hit.primaryScore()))
                    .append(" vector=").append(fmt(hit.getVectorScore()))
                    .append(" bm25=").append(fmt(hit.getBm25Score()))
                    .append(" rrf=").append(fmt(hit.getRrfScore()))
                    .append(" rerank=").append(fmt(hit.getRerankScore()))
                    .append(" hitBy=").append(value(hit.getHitBy()))
                    .append('\n');
            text.append("   title: ").append(value(hit.getTitle())).append('\n');
            text.append("   snippet: ").append(snippet(hit.getContent())).append('\n');
        }
        if (count > limit) {
            text.append("… 其余 ").append(count - limit).append(" 条已省略（单步最多展示 ")
                    .append(limit).append(" 条）\n");
        }
        return text.toString().trim();
    }

    /**
     * 单条正文压成单行摘要：换行/连续空白折叠，防泳道卡片被撑爆。
     */
    private static String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flat = content.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return clip(flat, MAX_SNIPPET);
    }

    /**
     * 取第一行，供 brief 使用。
     */
    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int lineEnd = text.indexOf('\n');
        String line = lineEnd < 0 ? text : text.substring(0, lineEnd);
        return line.trim();
    }

    /**
     * 按字符截断，尾部补省略号；避免切断代理对（emoji）。
     */
    private static String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= max) {
            return trimmed;
        }
        int end = Math.max(1, max - 1);
        if (end > 1 && Character.isHighSurrogate(trimmed.charAt(end - 1))) {
            end--;
        }
        return trimmed.substring(0, end) + "…";
    }

    /**
     * 分数统一 4 位小数，避免长浮点刷屏。
     */
    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /**
     * 可空分数：缺失显示 "-"，与评测检索链口径一致。
     */
    private static String fmt(Double value) {
        return value == null ? "-" : fmt(value.doubleValue());
    }

    /**
     * 可空字段占位，避免文本出现 "null"。
     */
    private static String value(Object raw) {
        return raw == null ? "-" : String.valueOf(raw);
    }
}
