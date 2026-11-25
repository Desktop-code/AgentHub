package com.agenthub.tool.api.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 工具项分页入参默认值。所属域：tool 的 api。
 * 未显式传 size 时默认 10 条，与全站列表默认一致（前端右栏工具表同值）。
 */
class ItemPageReqTest {

    /**
     * 默认 current=1、size=10；显式传入时以入参为准。
     */
    @Test
    void defaultPageSizeIsTen() {
        ItemPageReq req = new ItemPageReq();
        assertEquals(1L, req.getCurrent());
        assertEquals(10L, req.getSize());

        req.setSize(50);
        assertEquals(50L, req.getSize());
    }
}
