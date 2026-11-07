package com.agenthub.memory.domain.service;

/**
 * 记忆向量的数值处理。所属域：memory。
 * <p>
 * 为什么单独一层：建集用 AUTOINDEX + IP（内积），只有向量做过 L2 归一化时内积才等价余弦相似度。
 * Embedding 模型是否自带归一化不可假设，这里统一做一次幂等归一化：
 * 已归一化的向量再归一化结果不变，未归一化的也能保证 IP 口径正确。
 * 零范数（全 0 / 空向量）不参与除法，原样返回，交由上层按维度/内容边界拦截。
 */
public final class MemoryVectorMath {

    private MemoryVectorMath() {
    }

    /**
     * 对向量做 L2 归一化。
     *
     * @param vector 原始向量，可为空
     * @return 新建的归一化向量；入参为空或零范数时返回其副本
     */
    public static float[] l2Normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return vector == null ? new float[0] : vector.clone();
        }
        double sum = 0.0d;
        for (float value : vector) {
            sum += (double) value * value;
        }
        double norm = Math.sqrt(sum);
        // 零向量无法归一化：原样返回，避免产生 NaN 污染后续相似度计算
        if (norm <= 0.0d || Double.isNaN(norm) || Double.isInfinite(norm)) {
            return vector.clone();
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }
}
