package com.agenthub.runtime.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可调用判据：源启用 ∧ 工具项启用 ∧ 绑定启用，任一为 0 或空都不可调用。
 * 所属域：runtime。装配侧与执行侧共用这一份，避免「schema 有、句柄无」的假 tool_call。
 */
class ToolEnablementTest {

    /**
     * 三者全开才可调用。
     */
    @Test
    void callableOnlyWhenAllThreeOn() {
        assertTrue(ToolEnablement.callable(1, 1, 1));
    }

    /**
     * 任一停用都不可调用：覆盖源停、项停、绑定关三种线上报障形态。
     */
    @Test
    void anyDisabledPartBlocksCallable() {
        assertFalse(ToolEnablement.callable(1, 1, 0), "源停用应不可调用");
        assertFalse(ToolEnablement.callable(1, 0, 1), "工具项停用应不可调用");
        assertFalse(ToolEnablement.callable(0, 1, 1), "绑定关应不可调用");
    }

    /**
     * 空状态按停用处理，不能让脏数据顶成启用。
     */
    @Test
    void nullStatusCountsAsDisabled() {
        assertFalse(ToolEnablement.callable(null, 1, 1));
        assertFalse(ToolEnablement.callable(1, null, 1));
        assertFalse(ToolEnablement.callable(1, 1, null));
        assertFalse(ToolEnablement.sourceOn(null));
        assertFalse(ToolEnablement.itemOn(null));
        assertFalse(ToolEnablement.bindingOn(null));
    }
}
