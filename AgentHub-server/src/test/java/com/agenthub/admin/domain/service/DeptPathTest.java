package com.agenthub.admin.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部门 path 拼装与层级前缀判定。所属域：admin。不连数据库。
 */
class DeptPathTest {

    /**
     * 顶层从虚拟根 0 开始，子级按逗号续接。
     */
    @Test
    void buildsCommaHierarchy() {
        assertEquals("0,1", DeptPath.ofRoot(1L));
        assertEquals("0,1,3", DeptPath.ofChild("0,1", 3L));
        assertEquals("0,1,3,9", DeptPath.ofChild("0,1,3", 9L));
    }

    /**
     * 父路径为空时按虚拟根处理，不产生以逗号开头的脏值。
     */
    @Test
    void blankParentFallsBackToRoot() {
        assertEquals("0,5", DeptPath.ofChild("", 5L));
        assertEquals("0,5", DeptPath.ofChild(null, 5L));
    }

    /**
     * 前缀覆盖自身与下级，但不误命中同前缀的兄弟编号（0,1 不应命中 0,12）。
     */
    @Test
    void coversSelfAndDescendantsOnly() {
        assertTrue(DeptPath.covers("0,1", "0,1"));
        assertTrue(DeptPath.covers("0,1", "0,1,3"));
        assertTrue(DeptPath.covers("0,1", "0,1,3,9"));
        assertFalse(DeptPath.covers("0,1", "0,12"));
        assertFalse(DeptPath.covers("0,1", "0,2"));
        assertFalse(DeptPath.covers("0,1", null));
        assertFalse(DeptPath.covers(null, "0,1"));
    }
}
