package com.agenthub.rag.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.rag.domain.model.RagKnowledgeBase;
import com.agenthub.rag.infra.milvus.LocalMilvusRagProvider;
import com.agenthub.spi.rag.EmbeddingException;
import com.agenthub.spi.rag.RagStoreException;
import com.agenthub.spi.rag.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台内置知识库检索工具。所属域：rag。③ 期。
 * <p>
 * 调用链：模型 → 本 Tool → {@link RagRetrievalAppService}（向量 → 可选 BM25+RRF → 截断）。
 * <p>
 * 四个硬约束：
 * <ol>
 *   <li><b>知识库范围只来自 Agent 绑定</b>：参数里不出现 kbId / tenantId，模型无法越权指定库；</li>
 *   <li><b>无权明确拒绝，不装成功</b>：没有绑定、绑定全停用、或库不可用时返回明确错误文案，
 *       **绝不返回空数组冒充「没查到」**——那会让模型编造答案；</li>
 *   <li><b>无命中给固定拒答文案</b>：让模型知道知识库没有依据，而不是自由发挥；</li>
 *   <li><b>故障只说用户话</b>：本方法返回的字符串既进模型上下文、也可能出现在用户可见的
 *       工具区，因此失败一律用 {@link #KB_UNAVAILABLE} 一类短句；主机、端口、异常码、
 *       本机路径与中间件名只进服务端日志（见 {@link UserFacingText}）。</li>
 * </ol>
 * 与 runtime 的耦合只有两处：读 {@link RagToolContext} 拿当前 Agent；
 * 读工具线程已绑定的 obs TOOL span 锚点，把检索中间态挂成子步（无 trace 时为空操作）。
 */
@Component
public class RagSearchTool {

    private static final Logger log = LoggerFactory.getLogger(RagSearchTool.class);

    /** 无命中时给模型的固定拒答，instruction 要求据此不要编造 */
    public static final String NO_HIT = "知识库中未找到相关内容。请明确告知用户没有依据，不要编造条款或数据。";
    /**
     * 未绑定时给模型的固定文案。
     * <p>仍属配置问题，但对用户只描述「未开通」，禁止出现配置路径、集合名等运维细节。
     */
    public static final String NO_BINDING = "当前未绑定知识库。请直接告知用户该能力未开通。";
    /**
     * 知识库侧不可用（超时 / 连不上 / 熔断 / 向量化失败）的唯一话术。
     * <p>工具结果会进模型上下文，也可能出现在用户可见时间线的可展开区，
     * 因此这里只给产品词，不出现主机、端口、异常码与中间件名；原始原因只进日志。
     */
    public static final String KB_UNAVAILABLE = "知识库暂时不可用，请稍后再试。";

    /** 工具入参 topK 上限：与检索服务硬顶一致 */
    private static final int MAX_TOP_K = 10;

    private final RagRetrievalAppService retrieval;
    private final AgentKbBindAppService binds;
    private final AgentRerankLookup rerankLookup;
    private final ObjectMapper objectMapper;

    /**
     * @param retrieval    检索用例
     * @param binds        Agent 绑定（取可用库与覆盖项）
     * @param rerankLookup Agent「知识库」Tab 的重排绑定（Agent 配置优先于 KB 自带）
     * @param objectMapper 结果序列化
     */
    public RagSearchTool(RagRetrievalAppService retrieval, AgentKbBindAppService binds,
                         AgentRerankLookup rerankLookup, ObjectMapper objectMapper) {
        this.retrieval = retrieval;
        this.binds = binds;
        this.rerankLookup = rerankLookup;
        this.objectMapper = objectMapper;
    }

    /**
     * 检索当前 Agent 绑定的知识库。
     * <p>
     * 返回 JSON 数组（含 content / title / score / hitBy），或明确的失败说明。
     * 异常不抛出：Tool 抛异常会让整轮试跑失败，而「查不到东西」是可预期的业务结果。
     * <p>
     * 命中有 trace 锚点时，本次调用会在 TOOL span 下写
     * {@code tool.input → rewrite → retrieve.vector → retrieve.bm25 → rrf → rerank → tool.output}
     * 子步；其它工具不读该锚点，仍保持单层入参/返回。
     *
     * @param query 检索问题或关键字
     * @param topK  返回条数，默认 5，最大 10
     * @return JSON 或中文说明
     */
    @Tool(name = "searchKnowledgeBase",
            description = "检索当前 Agent 已绑定的知识库，返回相关文档片段与来源。"
                    + "适用于制度、政策、FAQ、产品说明等知识型问题。"
                    + "无命中时会返回「未找到」，此时必须明确告知用户没有依据，禁止编造；"
                    + "返回「暂时不可用」时只需告知用户稍后再试，不要展开技术细节。")
    public String searchKnowledgeBase(
            @ToolParam(name = "query", description = "检索问题或关键字") String query,
            @ToolParam(name = "topK", description = "返回条数，默认 5，最大 10", required = false) Integer topK) {
        if (query == null || query.isBlank()) {
            return "检索问题不能为空。";
        }
        int k = topK == null ? 5 : Math.min(Math.max(topK, 1), MAX_TOP_K);

        Long agentId = RagToolContext.agentIdOrNull();
        String trimmedQuery = query.trim();
        // 工具线程已由 RagContextAgentTool 绑定 TOOL span 锚点；无 trace 时会话为空操作，不影响检索
        RagObsSpans.Session obs = RagObsSpans.begin(agentId);
        try {
            if (agentId == null) {
                // 上下文缺失说明装配没做对；宁可拒绝也不猜一个库去查
                log.warn("[RAG] 检索工具缺少 Agent 上下文，拒绝检索");
                obs.input(RagObsSpans.jsonText(Map.of("query", trimmedQuery, "topK", k)), trimmedQuery, k,
                        Map.of("topK", k, "kbCount", 0));
                obs.output(NO_BINDING, "no_binding", 0, false);
                return NO_BINDING;
            }

            List<RagKnowledgeBase> usable = binds.listUsableForAgent(agentId);
            // 没有可用库时无需再查覆盖项与重排绑定，省两次库读
            Map<Long, String> overrides = usable.isEmpty() ? Map.of() : binds.overridesFor(agentId);
            // Agent「知识库」Tab 配了重排就以它为准；没配则由各库自带配置决定
            Long agentRerankModelId = usable.isEmpty()
                    ? null : rerankLookup.enabledRerankModelId(agentId).orElse(null);

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("query", trimmedQuery);
            input.put("topK", k);
            input.put("kbIds", usable.stream().map(RagKnowledgeBase::getId).toList());
            input.put("overrides", overrides);
            input.put("agentRerankModelId", agentRerankModelId);
            Map<String, Object> inputAttrs = new LinkedHashMap<>();
            inputAttrs.put("topK", k);
            inputAttrs.put("kbCount", usable.size());
            inputAttrs.put("kbIds", usable.stream().map(RagKnowledgeBase::getId)
                    .map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
            if (agentRerankModelId != null) {
                inputAttrs.put("agentRerankModelId", agentRerankModelId);
            }
            obs.input(RagObsSpans.jsonText(input), trimmedQuery, k, inputAttrs);

            if (usable.isEmpty()) {
                obs.output(NO_BINDING, "no_binding", 0, false);
                return NO_BINDING;
            }

            List<SearchResult> hits;
            try {
                hits = retrieval.retrieveMulti(usable, trimmedQuery, k, overrides, agentRerankModelId);
            } catch (EmbeddingException e) {
                // 向量化失败同属知识库侧不可用：对用户只说产品话，异常原文（厂商地址/状态码）只进日志
                log.warn("[RAG] 知识库向量化失败 agentId={} reason={}", agentId, e.getMessage(), e);
                obs.output(KB_UNAVAILABLE, "unavailable", null, true);
                return KB_UNAVAILABLE;
            } catch (RagStoreException e) {
                log.warn("[RAG] 知识库向量检索失败 agentId={} reason={}", agentId, e.getMessage(), e);
                obs.output(KB_UNAVAILABLE, "unavailable", null, true);
                return KB_UNAVAILABLE;
            } catch (BizException e) {
                // 业务拒绝（库被停用/未初始化等）同样是配置侧问题：用户面不给路径与整改步骤
                log.warn("[RAG] 知识库检索被拒绝 agentId={} reason={}", agentId, e.getMessage());
                obs.output(KB_UNAVAILABLE, "unavailable", null, true);
                return KB_UNAVAILABLE;
            } catch (RuntimeException e) {
                // 兜底：任何未预期异常都不把 message 交给模型，否则等于把部署细节交给它复述
                log.warn("[RAG] 知识库检索异常 agentId={} type={}", agentId, e.getClass().getSimpleName(), e);
                obs.output(UserFacingText.SERVICE_UNAVAILABLE, "error", null, true);
                return UserFacingText.SERVICE_UNAVAILABLE;
            }
            if (hits.isEmpty()) {
                obs.output(NO_HIT, "no_hit", 0, false);
                return NO_HIT;
            }
            String json = toJson(hits);
            // 与工具真实返回对齐：正文一致，超长部分由 obs 截断并标 truncated
            obs.output(json, "ok", hits.size(), false);
            return json;
        } finally {
            obs.end();
        }
    }

    /**
     * 结果序列化成紧凑 JSON。只暴露模型需要判断的字段，不带内部 id 与向量库细节。
     *
     * @param hits 命中
     * @return JSON 字符串；序列化失败时退化为纯文本
     */
    private String toJson(List<SearchResult> hits) {
        List<Map<String, Object>> rows = new ArrayList<>(hits.size());
        for (SearchResult hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("content", hit.getContent());
            row.put("title", hit.getTitle());
            row.put("source", hit.getSource());
            // 只给一个综合分，避免把 vector/bm25/rrf 四路分数一起丢给模型造成噪声
            row.put("score", round(hit.primaryScore()));
            row.put("hitBy", hit.getHitBy());
            rows.add(row);
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            // 兜底：逐条拼文本，保证模型至少拿到内容
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                sb.append("【").append(row.get("title")).append("】\n")
                        .append(row.get("content")).append("\n\n");
            }
            return sb.toString().trim();
        }
    }

    /**
     * 分数保留 4 位，避免长浮点污染 token。
     */
    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    /**
     * 供单测与装配校验：工具名必须与 tool_item.name 一致。
     *
     * @return 调用名
     */
    public static String toolName() {
        return "searchKnowledgeBase";
    }

    /**
     * 供排障：当前 Agent 的可用知识库数量（不进模型）。
     *
     * @param agentId Agent
     * @return 可用库数
     */
    public int usableKbCount(Long agentId) {
        return binds.listUsableForAgent(agentId).size();
    }

    /**
     * 说明向量库实现类型，便于单测断言「DENSE_ONLY 也能跑」。
     *
     * @return 固定字符串
     */
    static String providerHint(LocalMilvusRagProvider provider) {
        return provider.getClass().getSimpleName();
    }
}
