package com.agenthub.runtime.infra.agentscope;

import com.agenthub.rag.application.RagToolContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具换线程后仍能读到 Agent id。所属域：runtime 的 infra。
 * 复现 AgentScope ToolExecutor 的 boundedElastic：主线程 bind 对工具线程无效。
 */
class RagContextAgentToolTest {

    @AfterEach
    void tearDown() {
        RagToolContext.clear();
    }

    /**
     * 主线程不设上下文；包装后在 elastic 上调用 → 内层仍看见 agentId，结束后本线程清空。
     */
    @Test
    void rebindsAgentIdOnElasticThread() {
        AtomicReference<Long> seenOnToolThread = new AtomicReference<>();
        AgentTool wrapped = new RagContextAgentTool(stubTool(seenOnToolThread), 42L);

        RagToolContext.clear();
        assertNull(RagToolContext.agentIdOrNull());

        ToolResultBlock result = wrapped.callAsync(param("stub"))
                .subscribeOn(Schedulers.boundedElastic())
                .block();

        assertEquals(42L, seenOnToolThread.get());
        assertNull(RagToolContext.agentIdOrNull(), "执行线程结束后应 clear，避免池线程串库");
        assertTrue(result != null);
    }

    /**
     * ToolkitRagContext：套完后调用名仍在，且能读到 Agent。
     */
    @Test
    void toolkitWrapPreservesNameAndContext() {
        AtomicReference<Long> seen = new AtomicReference<>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(stubTool(seen));
        ToolkitRagContext.wrap(toolkit, 7L);

        assertTrue(toolkit.getTool("stub") instanceof RagContextAgentTool);
        toolkit.getTool("stub").callAsync(param("stub")).block();
        assertEquals(7L, seen.get());
    }

    private static ToolCallParam param(String name) {
        ToolUseBlock use = ToolUseBlock.builder().id("c1").name(name).input(Map.of()).build();
        return ToolCallParam.builder().toolUseBlock(use).input(Map.of()).build();
    }

    private static AgentTool stubTool(AtomicReference<Long> seen) {
        return new AgentTool() {
            @Override
            public String getName() {
                return "stub";
            }

            @Override
            public String getDescription() {
                return "stub";
            }

            @Override
            public Map<String, Object> getParameters() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                seen.set(RagToolContext.agentIdOrNull());
                return Mono.just(ToolResultBlock.builder()
                        .output(TextBlock.builder().text("ok").build())
                        .build());
            }
        };
    }
}
