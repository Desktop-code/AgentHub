package com.agenthub.common.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分页夹取契约。所属域：common。
 *
 * <p>验收口径：页码 &lt; 1 一律归 1；每页条数额定 [1, 100]；另立上限的查询用重载不得越过自身上限。
 */
class PageQueryTest {

    /** 页码最小为 1。 */
    @Test
    void clampCurrentFloorsAtOne() {
        assertEquals(1, PageQuery.clampCurrent(0));
        assertEquals(1, PageQuery.clampCurrent(-5));
        assertEquals(3, PageQuery.clampCurrent(3));
    }

    /** 每页条数默认上限 100。 */
    @Test
    void clampSizeUsesDefaultCap() {
        assertEquals(1, PageQuery.clampSize(0));
        assertEquals(1, PageQuery.clampSize(-1));
        assertEquals(20, PageQuery.clampSize(20));
        assertEquals(100, PageQuery.clampSize(101));
        assertEquals(100, PageQuery.clampSize(Long.MAX_VALUE));
    }

    /** 另立上限（workflow 50/200/500）不得被默认 100 覆盖。 */
    @Test
    void clampSizeHonoursCustomCap() {
        assertEquals(500, PageQuery.clampSize(1000, 500));
        assertEquals(200, PageQuery.clampSize(300, 200));
        assertEquals(50, PageQuery.clampSize(51, 50));
        assertEquals(1, PageQuery.clampSize(0, 50));
    }
}
