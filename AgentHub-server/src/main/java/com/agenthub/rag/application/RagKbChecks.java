package com.agenthub.rag.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 知识库入参检查与默认值。所属域：rag。
 * 注解负责空值与长度，这里负责去空白、枚举收口和默认 JSON。
 */
public final class RagKbChecks {

    /** 名称上限，与 rag_knowledge_base.name 一致 */
    public static final int NAME_MAX = 100;
    /** 描述上限 */
    public static final int DESCRIPTION_MAX = 512;
    /** retrieval_config / ingest_config 的字符上限，低于 MySQL JSON 列上限，防止单请求过大 */
    public static final int CONFIG_JSON_MAX = 4000;

    /**
     * 一期默认检索参数。写进 retrieval_config，③ 期检索 Tool 直接读这份。
     * 取值参考 mall-swarm 调优结论：向量 20 / BM25 20 / RRF k=60 / 输出 5，改写与 Rerank 默认关。
     */
    public static final String DEFAULT_RETRIEVAL_CONFIG = """
            {"topK":5,"vectorTopK":20,"bm25TopK":20,"rrfK":60,"rrfTopN":20,\
            "scoreThreshold":0.5,"rewriteEnabled":false,"rerankEnabled":false}""";

    /**
     * 一期默认入库参数。② 期使用，与 mall-swarm 的 450/100/150 对齐。
     */
    public static final String DEFAULT_INGEST_CONFIG = """
            {"chunkSize":450,"chunkOverlap":100,"minChunkSize":150}""";

    private RagKbChecks() {
    }

    /**
     * @param raw   原文
     * @param max   最大长度
     * @param label 字段中文名
     * @return 去掉首尾空白的文本
     * @throws BizException 为空或超长
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
     * @param raw   可空文本
     * @param max   最大长度
     * @param label 字段中文名
     * @return 空白转 null
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
     * 校验并收紧 JSON 配置。空白返回默认值。
     * <p>
     * 只做可解析性与长度检查，不校验具体键——③ 期读取时缺键回落默认，避免这里成为第二个 schema。
     *
     * @param raw     前端传入的 JSON 字符串
     * @param fallback 默认 JSON
     * @param label   字段中文名
     * @return 可入库 JSON
     * @throws BizException 非法 JSON 或超长
     */
    public static String configJson(String raw, String fallback, String label) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String text = raw.trim();
        if (text.length() > CONFIG_JSON_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "最长 " + CONFIG_JSON_MAX);
        }
        if (!looksLikeJsonObject(text)) {
            throw new BizException(ErrorCode.BAD_REQUEST, label + "必须是 JSON 对象");
        }
        return text;
    }

    /**
     * 极简 JSON 对象形状检查：首尾必须是花括号。完整语法错误由 MySQL JSON 列落库时报错兜底，
     * 这里只挡明显非 JSON 的输入，避免为一段配置引入额外依赖。
     */
    private static boolean looksLikeJsonObject(String text) {
        return text.startsWith("{") && text.endsWith("}") && text.length() >= 2;
    }
}
