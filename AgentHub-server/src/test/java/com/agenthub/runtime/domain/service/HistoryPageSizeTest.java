package com.agenthub.runtime.domain.service;

import com.agenthub.common.api.PageQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 试跑会话页大小契约。所属域：runtime。
 *
 * <p>核心回归点：{@code history-limit} 只是「默认页大小」，<b>不能</b>再把调用方显式请求的
 * 更大 size 裁回固定值——旧实现固定 20 就是「历史会话只保留 20 条」的根因。
 */
class HistoryPageSizeTest {

    /** 显式 size 必须原样生效（受全站上限约束），不能被默认页大小压回去。 */
    @Test
    void keepsRequestedSizeAboveDefault() {
        assertEquals(100, HistoryPageSize.resolve(100, 50));
        assertEquals(80, HistoryPageSize.resolve(80, 20));
    }

    /** 小于默认页大小时按调用方要求，不擅自放大。 */
    @Test
    void keepsRequestedSizeBelowDefault() {
        assertEquals(10, HistoryPageSize.resolve(10, 50));
        assertEquals(1, HistoryPageSize.resolve(1, 50));
    }

    /** 未传 size（0 / 负数）才用配置的默认页大小。 */
    @Test
    void fallsBackToConfiguredDefault() {
        assertEquals(50, HistoryPageSize.resolve(0, 50));
        assertEquals(50, HistoryPageSize.resolve(-3, 50));
        assertEquals(20, HistoryPageSize.resolve(0, 20));
    }

    /** 配置缺失 / 非法时退回 50，而不是旧的 20。 */
    @Test
    void invalidConfigFallsBackToFifty() {
        assertEquals(50, HistoryPageSize.resolve(0, 0));
        assertEquals(50, HistoryPageSize.resolve(0, -10));
    }

    /** 全站分页硬顶 100：请求再大也只到 100，禁止抬高。 */
    @Test
    void neverExceedsGlobalCap() {
        assertEquals(100, HistoryPageSize.resolve(101, 50));
        assertEquals(100, HistoryPageSize.resolve(Long.MAX_VALUE, 50));
        assertEquals(100, HistoryPageSize.resolve(0, 500));
        assertEquals(HistoryPageSize.MAX_PAGE_SIZE, PageQuery.MAX_SIZE);
    }
}
