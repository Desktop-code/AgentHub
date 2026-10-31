package com.agenthub.rag.domain.service;

import com.agenthub.rag.domain.service.ir.StructureAwareChunker;

import java.util.ArrayList;
import java.util.List;

/**
 * 入库切片编排。所属域：rag。
 * <p>
 * 钉死五段（对齐 mall IR）：切前清洗 → IR+原型路由 → 四策略切片 → 切后去噪 →（上层 Embedding）。
 * 不再使用「Recursive 一切了之」。参数来自 KB 的 ingest_config（默认 450/100/150）。
 * <p>
 * 纯函数，不碰 IO 与模型。{@link #chunkRich} 保留 IR 元数据供入库写 parent/part；
 * {@link #chunk} 仅返回正文，兼容旧调用方。
 */
public final class ChunkPipeline {

    private static final StructureAwareChunker DEFAULT = StructureAwareChunker.createDefault();

    private ChunkPipeline() {
    }

    /**
     * 执行结构感知切片，返回正文列表。
     *
     * @param rawText      解析出的原始正文
     * @param chunkSize    目标片长（字符）
     * @param overlap      重叠字符
     * @param minChunkSize 过短片合并阈值，≤0 时回落默认 150
     * @return 切片正文；无有效内容时为空
     */
    public static List<String> chunk(String rawText, int chunkSize, int overlap, int minChunkSize) {
        List<DocumentChunk> rich = chunkRich(null, rawText, chunkSize, overlap, minChunkSize);
        List<String> out = new ArrayList<>(rich.size());
        for (DocumentChunk c : rich) {
            out.add(c.getContent());
        }
        return out;
    }

    /**
     * 带文件名的切片（影响原型兜底与合集强制判定）。
     *
     * @param fileName     原始文件名，可空
     * @param rawText      解析正文
     * @param chunkSize    目标片长
     * @param overlap      重叠
     * @param minChunkSize 过短合并阈值
     * @return 正文列表
     */
    public static List<String> chunk(String fileName, String rawText, int chunkSize, int overlap, int minChunkSize) {
        List<DocumentChunk> rich = chunkRich(fileName, rawText, chunkSize, overlap, minChunkSize);
        List<String> out = new ArrayList<>(rich.size());
        for (DocumentChunk c : rich) {
            out.add(c.getContent());
        }
        return out;
    }

    /**
     * 结构感知切片，保留 sectionTitle / header_path / partIndex 等元数据。
     *
     * @param fileName     原始文件名；合集名含「合集」等会强制 HIERARCHICAL
     * @param rawText      解析正文
     * @param chunkSize    目标片长
     * @param overlap      重叠
     * @param minChunkSize 过短合并阈值；≤0 用 {@link DocumentChunker#DEFAULT_MIN_CHUNK_SIZE}
     * @return IR 切片；正文空白时为空
     */
    public static List<DocumentChunk> chunkRich(String fileName, String rawText,
                                                int chunkSize, int overlap, int minChunkSize) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }
        int size = Math.max(50, chunkSize);
        int ov = Math.max(0, Math.min(overlap, size / 2));
        int min = minChunkSize > 0 ? minChunkSize : DocumentChunker.DEFAULT_MIN_CHUNK_SIZE;
        return DEFAULT.chunk(fileName, rawText, size, ov, min);
    }

    /**
     * 估算 token 数：中文按 1 字 ≈ 1 token，英文按 4 字符 ≈ 1 token，两类分别折算后相加。
     * <p>
     * 只用于展示与预算参考，不参与计费（计费以模型回传为准）。
     *
     * @param text 文本
     * @return 估算值，空文本为 0
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        int tokens = cjk;
        if (other > 0) {
            // 英文/符号按 4 字符 1 token 折算，不足 4 也算 1，避免整段被算成 0
            tokens += Math.max(1, other / 4);
        }
        return Math.max(tokens, 1);
    }

    /**
     * 内容哈希，用于重灌去重与变更检测。
     *
     * @param text 文本
     * @return SHA-256 十六进制
     */
    public static String hash(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest((text == null ? "" : text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 必然存在；兜底返回长度，至少能区分不同文本
            return "len" + (text == null ? 0 : text.length());
        }
    }
}
