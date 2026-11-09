package com.agenthub.rag.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.model.domain.model.ModelCapability;
import com.agenthub.model.domain.model.ModelInstance;
import com.agenthub.model.domain.repository.ModelInstanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 跨域只读引用模型实例。所属域：rag。
 * 只调 model 的领域仓储，不依赖其 Entity / AppService；Key 与 Base URL 一概不读，RAG 只存 id。
 * <p>
 * 维度来源（按可信度）：① 模型连通测试返回的 {@code model_instance.embedding_dim}；
 * ② 模型实例的 {@code extra_json.dimension}（或 {@code embeddingDimension}）。
 * 缺失时允许管理员在创建请求里显式给出兜底值，三者都没有则拒绝创建——
 * 不允许猜一个维度建出无法写入的集合。
 */
@Component
public class RagModelLookup {

    private static final Logger log = LoggerFactory.getLogger(RagModelLookup.class);

    /** extra_json 里表示维度的键名（按顺序取第一个存在的） */
    private static final String[] DIMENSION_KEYS = {"dimension", "embeddingDimension", "vectorDimension"};

    private final ModelInstanceRepository instances;
    private final ObjectMapper objectMapper;

    /**
     * @param instances    模型实例仓储
     * @param objectMapper 解析 extra_json
     */
    public RagModelLookup(ModelInstanceRepository instances, ObjectMapper objectMapper) {
        this.instances = instances;
        this.objectMapper = objectMapper;
    }

    /**
     * 取可用的 Embedding 实例：必须存在、未删除、已启用、用途为 EMBEDDING。
     *
     * @param id 模型实例主键
     * @return 实例
     * @throws BizException 不存在 / 已停用 / 用途不符
     */
    public ModelInstance requireEmbedding(Long id) {
        ModelInstance instance = require(id, "Embedding 模型");
        assertCapability(instance, ModelCapability.EMBEDDING, "所选模型用途不是 EMBEDDING");
        return instance;
    }

    /**
     * 取可用的 Rerank 实例（可选字段，传 null 直接返回 null）。
     *
     * @param id 模型实例主键，可空
     * @return 实例或 null
     * @throws BizException 不存在 / 已停用 / 用途不符
     */
    public ModelInstance requireRerank(Long id) {
        if (id == null) {
            return null;
        }
        ModelInstance instance = require(id, "Rerank 模型");
        assertCapability(instance, ModelCapability.RERANK, "所选模型用途不是 RERANK");
        return instance;
    }

    /**
     * 解析向量维度。取值顺序（与前端展示、记忆域口径一致）：
     * ① 模型连通测试返回的 {@code embedding_dim} 快照——模型自己回答的维度，最可信；
     * ② 模型实例 {@code extra_json.dimension}；
     * ③ 调用方给的兜底值；
     * 三者都没有则拒绝创建，绝不猜一个维度建出写不进向量的集合。
     *
     * @param instance   Embedding 实例
     * @param fallback   请求里显式给的维度，可空
     * @return 维度，必然 > 0
     * @throws BizException 三处都没有合法维度
     */
    public int resolveDimension(ModelInstance instance, Integer fallback) {
        // 连通测试快照优先：它是模型真实返回的维度，人工填的 extra_json 可能与模型不符
        Integer probed = instance.getEmbeddingDim();
        if (probed != null && probed > 0) {
            return probed;
        }
        Integer fromMeta = dimensionOf(instance.getExtraJson());
        if (fromMeta != null && fromMeta > 0) {
            return fromMeta;
        }
        if (fallback != null && fallback > 0) {
            return fallback;
        }
        throw new BizException(ErrorCode.BAD_REQUEST,
                "无法确定向量维度：" + instance.getDisplayName()
                        + " 还没有连通测试返回的维度，也未在模型中心填写 extraJson.dimension。"
                        + "请先在模型管理对该 Embedding 实例做一次连通测试后重试");
    }

    /**
     * 从 extra_json 读维度。解析失败只记日志并返回 null，不阻断创建流程。
     *
     * @param extraJson 模型实例扩展 JSON，可空
     * @return 维度或 null
     */
    Integer dimensionOf(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(extraJson);
            for (String key : DIMENSION_KEYS) {
                JsonNode node = root.get(key);
                if (node != null && node.isNumber() && node.asInt() > 0) {
                    return node.asInt();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[RAG] 解析模型 extra_json 维度失败，忽略: {}", e.getMessage());
            return null;
        }
    }

    private ModelInstance require(Long id, String label) {
        if (id == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "不能为空");
        }
        ModelInstance instance = instances.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, label + "不存在"));
        if (instance.getStatus() == null || instance.getStatus() != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "已停用");
        }
        return instance;
    }

    private static void assertCapability(ModelInstance instance, ModelCapability expected, String message) {
        ModelCapability actual = ModelCapability.tryParse(instance.getCapability()).orElse(null);
        if (actual != expected) {
            throw new BizException(ErrorCode.BAD_REQUEST, message);
        }
    }
}
