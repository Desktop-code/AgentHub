package com.agenthub.memory.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆向量状态机：三个标记的边界。所属域：memory。
 * <p>
 * 重点验证「受控重建」与「平台级换绑后续补」互不污染：
 * <ul>
 *   <li>换绑续补不置 rebuilding（不阻止操作者继续改平台级）；</li>
 *   <li>重建未完成必然要求续补；</li>
 *   <li>补空才收口，读失败（-1）绝不误判为已补齐。</li>
 * </ul>
 */
class MemoryVectorRebuildStateTest {

    @Test
    @DisplayName("平台级换绑续补：不锁平台级，但要求服务端继续补")
    void drainRequestDoesNotLockPlatform() {
        MemoryVectorRebuildState state = new MemoryVectorRebuildState();

        state.requestDrain();

        assertTrue(state.isDrainWanted());
        // 同维换绑不该挡住后续调整配置：只续补，不置重建态
        assertFalse(state.isRebuilding());
    }

    @Test
    @DisplayName("受控重建未完成：必然要求续补，且锁住平台级变更")
    void rebuildImpliesDrainAndLock() {
        MemoryVectorRebuildState state = new MemoryVectorRebuildState();

        assertTrue(state.tryBegin());

        assertTrue(state.isRebuilding());
        assertTrue(state.isDrainWanted());
        state.endRun();
        // 执行结束不等于补齐：重建态与续补请求都必须保留
        assertTrue(state.isRebuilding());
        assertTrue(state.isDrainWanted());
    }

    @Test
    @DisplayName("还有待补（含读取失败 -1）：不得收口")
    void pendingKeepsState() {
        MemoryVectorRebuildState state = new MemoryVectorRebuildState();
        state.requestDrain();

        assertFalse(state.clearIfDrained(5));
        assertTrue(state.isDrainWanted());
        // -1 = pendingCount 读失败：必须保持续补，不能当成已补齐
        assertFalse(state.clearIfDrained(-1));
        assertTrue(state.isDrainWanted());
    }

    @Test
    @DisplayName("补空：撤掉续补请求；重建态在时一并放开平台级锁")
    void drainedClearsEverything() {
        MemoryVectorRebuildState state = new MemoryVectorRebuildState();
        state.tryBegin();
        state.endRun();

        // 补空 → 重建态收口，返回 true 表示平台级锁已放开
        assertTrue(state.clearIfDrained(0));
        assertFalse(state.isRebuilding());
        assertFalse(state.isDrainWanted());
        // 已经收口后再清一次：幂等，返回 false（本轮没有释放任何锁）
        assertFalse(state.clearIfDrained(0));
    }

    @Test
    @DisplayName("仅换绑续补结束时：清请求但不报告释放重建锁")
    void drainOnlyClearDoesNotReportRelease() {
        MemoryVectorRebuildState state = new MemoryVectorRebuildState();
        state.requestDrain();

        assertFalse(state.clearIfDrained(0));
        assertFalse(state.isDrainWanted());
    }
}
