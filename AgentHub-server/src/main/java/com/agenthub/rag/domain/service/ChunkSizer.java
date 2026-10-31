package com.agenthub.rag.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 超长切片再切与过短片合并。所属域：rag。
 * <p>
 * 参考 mall-swarm {@code OversizePartSplitter} 的思路，只保留与业务无关的部分：
 * 超过 embedding 上下文上限的片必须再切，否则向量化时会被模型静默截断（尾段永久检索不到）；
 * 过短的碎片并入同章节邻片，避免产生大量无信息切片。
 */
public final class ChunkSizer {

    private ChunkSizer() {
    }

    /**
     * 把超过 maxChars 的片再切。
     * <p>
     * 切出来的分片标题追加 {@code (i/n)}，便于在检索结果里看出这是同一片的不同部分。
     *
     * @param texts     原始片
     * @param maxChars  单片上限
     * @param overlap   重叠字符
     * @return 新片列表；不超限的片原样返回
     */
    public static List<String> splitOversize(List<String> texts, int maxChars, int overlap) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(50, maxChars);
        List<String> out = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            if (text.length() <= limit) {
                out.add(text);
                continue;
            }
            out.addAll(RecursiveTextSplitter.split(text, limit, overlap));
        }
        return out;
    }

    /**
     * 合并过短片：长度小于 minChars 的片并入前一片。首片过短时与后一片合并，避免开头被丢。
     * <p>
     * 合并只按相邻顺序进行，不跨文档；调用方负责保证入参来自同一文档同一批次。
     *
     * @param texts    切片
     * @param minChars 最小长度，≤0 表示不合并
     * @return 合并后切片
     */
    public static List<String> mergeSmall(List<String> texts, int minChars) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (minChars <= 0) {
            return List.copyOf(texts);
        }
        List<String> out = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            if (out.isEmpty()) {
                out.add(text);
                continue;
            }
            String prev = out.get(out.size() - 1);
            // 当前片过短：并入前一片；前一片过短：把当前片并进前一片。两种都等价于「短的让位给邻居」
            if (text.trim().length() < minChars || prev.trim().length() < minChars) {
                out.set(out.size() - 1, prev + "\n" + text);
                continue;
            }
            out.add(text);
        }
        return out;
    }
}
