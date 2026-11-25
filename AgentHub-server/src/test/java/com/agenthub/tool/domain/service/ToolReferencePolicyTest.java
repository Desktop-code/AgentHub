package com.agenthub.tool.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.tool.domain.model.ToolSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置源不能删，停用的 MCP 不能同步。
 */
class ToolReferencePolicyTest {

    private final ToolReferencePolicy policy = new ToolReferencePolicy();

    /**
     * 种子卡片被删掉后，目录里就没有只读内置源了。
     */
    @Test
    void builtinSourceCannotBeDeleted() {
        ToolSource source = new ToolSource();
        source.setType("BUILTIN");
        BizException ex = assertThrows(BizException.class, () -> policy.assertDeletable(source));
        assertTrue(ex.getMessage().contains("不能删除"));
    }

    /**
     * 停用源如果继续拉远端，会把白名单同步和启停混在一起。
     */
    @Test
    void disabledMcpCannotSync() {
        ToolSource source = mcp();
        source.setStatus(0);
        assertThrows(BizException.class, () -> policy.assertSyncable(source));
        source.setStatus(1);
        assertDoesNotThrow(() -> policy.assertSyncable(source));
    }

    /**
     * Streamable HTTP 源与 SSE 同等可同步。
     */
    @Test
    void httpTransportCanSync() {
        ToolSource source = mcp();
        source.setTransport("HTTP");
        source.setBaseUrl("http://127.0.0.1/mcp");
        assertDoesNotThrow(() -> policy.assertSyncable(source));
    }

    /**
     * 非法传输必须拒绝，避免悄悄落到错误客户端。
     */
    @Test
    void illegalTransportCannotSync() {
        ToolSource source = mcp();
        source.setTransport("stdio");
        BizException ex = assertThrows(BizException.class, () -> policy.assertSyncable(source));
        assertTrue(ex.getMessage().contains("SSE") || ex.getMessage().contains("HTTP"));
    }

    private ToolSource mcp() {
        ToolSource source = new ToolSource();
        source.setType("MCP");
        source.setTransport("SSE");
        source.setStatus(1);
        source.setBaseUrl("http://127.0.0.1/sse");
        return source;
    }
}
