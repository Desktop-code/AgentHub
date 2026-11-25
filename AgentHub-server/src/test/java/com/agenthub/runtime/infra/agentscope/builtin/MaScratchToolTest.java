package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.model.ScratchBoard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享草稿工具（ma_scratch_get / ma_scratch_put）窄测。所属域：runtime 的 infra。
 *
 * <p>锁定交付契约：工具调用名、JSON 返回字段、冲突语义、注册后引擎可见；
 * 不起 Spring、不出站模型、不碰库。
 */
class MaScratchToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void toolNamesLocked() {
        // 工具名是交付契约：改名等于破坏模型侧已固化的目录，必须显式改测试
        assertEquals("ma_scratch_get", MaScratchTool.TOOL_GET);
        assertEquals("ma_scratch_put", MaScratchTool.TOOL_PUT);
        assertEquals(2, MaScratchTool.TOOL_NAMES.size());
        assertTrue(MaScratchTool.promptSection().contains("ma_scratch_get"));
        assertTrue(MaScratchTool.promptSection().contains("ma_scratch_put"));
    }

    @Test
    void installRegistersBothToolsIntoEngine() {
        ScratchBoard board = new ScratchBoard();
        Toolkit toolkit = new Toolkit();
        MaScratchTool.install(toolkit, board, "after_sale");

        assertNotNull(toolkit.getTool("ma_scratch_get"));
        assertNotNull(toolkit.getTool("ma_scratch_put"));
        // 重复安装不报错（P&E 每步重装 / 边界调用）
        MaScratchTool.install(toolkit, board, "after_sale");
        assertNotNull(toolkit.getTool("ma_scratch_put"));
        // schema 由 Java 注解覆盖：key 必填
        String required = String.valueOf(toolkit.getTool("ma_scratch_put").getParameters().get("required"));
        assertTrue(required.contains("key"), required);
    }

    @Test
    void installWithoutBoardLeavesToolkitUntouched() {
        Toolkit toolkit = new Toolkit();
        MaScratchTool.install(toolkit, null, "a");
        assertNull(toolkit.getTool("ma_scratch_get"));
        assertNull(toolkit.getTool("ma_scratch_put"));
    }

    @Test
    void getReturnsFoundFalseWhenMissing() throws Exception {
        MaScratchTool tool = new MaScratchTool(new ScratchBoard(), "a");
        JsonNode body = JSON.readTree(tool.scratchGet("nope"));

        assertFalse(body.get("found").asBoolean());
        assertEquals("nope", body.get("key").asText());
        assertTrue(body.has("message"));
        // 不存在不报错：没有价值字段，模型据此判断退路
        assertFalse(body.has("value"));
    }

    @Test
    void putThenGetSharesValueAcrossChildren() throws Exception {
        ScratchBoard board = new ScratchBoard();
        MaScratchTool first = new MaScratchTool(board, "after_sale");
        JsonNode put = JSON.readTree(first.scratchPut("draft/answer", "退款方案 A"));

        assertTrue(put.get("ok").asBoolean());
        assertEquals(ScratchBoard.CODE_OK, put.get("code").asText());

        // 另一个子（并行同伴 / 串行后续步）能读到同一个键
        MaScratchTool second = new MaScratchTool(board, "logistics");
        JsonNode read = JSON.readTree(second.scratchGet("draft/answer"));
        assertTrue(read.get("found").asBoolean());
        assertEquals("退款方案 A", read.get("value").asText());
        // 首写者信息保留，便于冲突时说明是谁占的键
        assertEquals("after_sale", read.get("ownerRouteKey").asText());
    }

    @Test
    void secondPutReturnsStructuredConflict() throws Exception {
        ScratchBoard board = new ScratchBoard();
        MaScratchTool a = new MaScratchTool(board, "a");
        MaScratchTool b = new MaScratchTool(board, "b");
        JSON.readTree(a.scratchPut("k", "首写"));

        JsonNode conflict = JSON.readTree(b.scratchPut("k", "覆盖"));
        assertFalse(conflict.get("ok").asBoolean());
        assertEquals(ScratchBoard.CODE_KEY_CONFLICT, conflict.get("code").asText());
        assertEquals("a", conflict.get("ownerRouteKey").asText());
        assertTrue(conflict.get("message").asText().contains("首次写入"));
        // 原值不变
        assertEquals("首写", JSON.readTree(b.scratchGet("k")).get("value").asText());
    }

    @Test
    void invalidKeyAndTooLongValueAreReadable() throws Exception {
        MaScratchTool tool = new MaScratchTool(new ScratchBoard(), "a");
        JsonNode invalid = JSON.readTree(tool.scratchPut("", "v"));
        assertFalse(invalid.get("ok").asBoolean());
        assertEquals(ScratchBoard.CODE_KEY_INVALID, invalid.get("code").asText());
        assertTrue(invalid.get("message").asText().contains("key"), invalid.toString());

        JsonNode tooLong = JSON.readTree(tool.scratchPut("k", "x".repeat(4001)));
        assertEquals(ScratchBoard.CODE_VALUE_TOO_LONG, tooLong.get("code").asText());
        assertTrue(tooLong.get("message").asText().contains("4000"), tooLong.toString());
    }
}
