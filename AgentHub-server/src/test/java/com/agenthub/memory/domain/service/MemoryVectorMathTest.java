package com.agenthub.memory.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L2 归一化：内积等价余弦的前提；零向量与空向量不得产生 NaN。
 */
class MemoryVectorMathTest {

    @Test
    void normalizesToUnitLength() {
        float[] normalized = MemoryVectorMath.l2Normalize(new float[]{3f, 4f});

        assertEquals(0.6f, normalized[0], 1e-6);
        assertEquals(0.8f, normalized[1], 1e-6);
    }

    @Test
    void alreadyUnitVectorStaysUnchanged() {
        assertArrayEquals(new float[]{0.6f, 0.8f}, MemoryVectorMath.l2Normalize(new float[]{0.6f, 0.8f}), 1e-6f);
    }

    @Test
    void zeroVectorStaysZero() {
        assertArrayEquals(new float[]{0f, 0f}, MemoryVectorMath.l2Normalize(new float[]{0f, 0f}));
    }

    @Test
    void emptyVectorReturnsEmptyCopy() {
        assertEquals(0, MemoryVectorMath.l2Normalize(new float[0]).length);
    }

    @Test
    void nullVectorReturnsEmpty() {
        assertEquals(0, MemoryVectorMath.l2Normalize(null).length);
    }
}
