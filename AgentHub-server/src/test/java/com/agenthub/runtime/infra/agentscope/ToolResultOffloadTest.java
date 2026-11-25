package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.model.PromptInjectionStats;
import com.agenthub.runtime.domain.service.ToolResultOffloadPolicy;
import com.agenthub.runtime.infra.agentscope.builtin.WorkspaceSandbox;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超长工具结果的端到端句柄化。所属域：runtime 的 infra 单测。
 * <p>
 * 只操作系统临时目录下的测试专用根，不连库、不建 Spring 容器、不出站。
 * 验收口径：
 * <ol>
 *   <li>超阈值：回灌正文里<b>没有</b>原全文，只有摘要 + 路径；文件真的落盘且内容完整；</li>
 *   <li>未超阈值：原样回灌，一个字符都不改，也不产生文件；</li>
 *   <li>工作区工具：即使超长也不落盘（避免文件套文件）；</li>
 *   <li>失败结果：不落盘；</li>
 *   <li>统计：落盘条数与原始字符数被记账（证明「省了多少」可查）。</li>
 * </ol>
 */
class ToolResultOffloadTest {

    private static final Path TEST_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "agenthub_offload_test");
    /** 与引擎同款 sessionId 格式（尾部纳秒每轮变） */
    private static final String SESSION = "trial-555666777-agent-9-123456789";

    private final WorkspaceSandbox sandbox = new WorkspaceSandbox(TEST_ROOT.toString());
    private final TrialProperties properties = new TrialProperties();
    private final ToolResultOffloadStore offload = new ToolResultOffloadStore(sandbox, properties);
    private final PromptInjectionStats stats = new PromptInjectionStats();

    @AfterEach
    void cleanup() {
        deleteQuietly(TEST_ROOT.resolve("trial-555666777"));
    }

    /**
     * 超阈值：回灌只剩摘要 + 路径，全文在文件里。
     */
    @Test
    void oversizeResultIsReplacedByHandle() {
        String body = "网页正文开始\n" + "内容".repeat(2000);
        AgentTool tool = new FileMarkerAgentTool(new FakeTool("web_fetch", body), null,
                offload, 2000, stats, ToolResultOffloadPolicy.READ_FILE_TOOL);

        ToolResultBlock result = tool.callAsync(param(SESSION, "web_fetch")).block();
        String text = firstText(result);

        assertFalse(text.contains("内容".repeat(500)), "回灌里不得再有原全文（只允许开头摘录）");
        assertTrue(text.length() < body.length() / 2, "回灌正文必须显著短于原文");
        assertTrue(text.contains("已保存到工作区文件"), text);
        assertTrue(text.contains("workspaceReadFile"), "必须给出继续读的方式");
        // 落盘的正文必须完整（不能因为回灌截断而丢内容）
        Path stored = findStoredFile();
        assertTrue(Files.isRegularFile(stored));
        assertEquals(body, readRaw(stored));
        // 统计要能证明省了多少
        assertEquals(1, stats.getToolResultsOffloaded());
        assertTrue(stats.getToolResultsOffloadedChars() >= body.length());
        assertTrue(stats.breakdown().contains("工具句柄"));
    }

    /**
     * 未超阈值：原样回灌，不产生文件。
     */
    @Test
    void shortResultPassesThroughUnchanged() {
        String body = "晴，26℃";
        AgentTool tool = new FileMarkerAgentTool(new FakeTool("queryWeather", body), null,
                offload, 2000, stats, ToolResultOffloadPolicy.READ_FILE_TOOL);

        ToolResultBlock result = tool.callAsync(param(SESSION, "web_fetch")).block();
        assertEquals(body, firstText(result));
        assertFalse(Files.exists(TEST_ROOT.resolve("trial-555666777")));
        assertEquals(0, stats.getToolResultsOffloaded());
    }

    /**
     * 工作区工具超长也不落盘：它返回的就是文件内容。
     */
    @Test
    void workspaceToolIsNotOffloaded() {
        String body = "行 | 内容\n".repeat(2000);
        AgentTool tool = new FileMarkerAgentTool(new FakeTool("workspaceReadFile", body), null,
                offload, 2000, stats, ToolResultOffloadPolicy.READ_FILE_TOOL);

        ToolResultBlock result = tool.callAsync(param(SESSION, "workspaceReadFile")).block();
        assertEquals(body, firstText(result), "工作区工具结果应原样回灌");
        assertEquals(0, stats.getToolResultsOffloaded());
    }

    /**
     * 失败结果不落盘。
     */
    @Test
    void failedResultIsNotOffloaded() {
        String body = "超时".repeat(2000);
        AgentTool tool = new FileMarkerAgentTool(
                new FakeTool("web_fetch", body, io.agentscope.core.message.ToolResultState.ERROR), null,
                offload, 2000, stats, ToolResultOffloadPolicy.READ_FILE_TOOL);

        ToolResultBlock result = tool.callAsync(param(SESSION, "web_fetch")).block();
        assertEquals(body, firstText(result));
        assertEquals(0, stats.getToolResultsOffloaded());
    }

    /**
     * 缺 sessionId：落不了盘就退回原行为，不能变成错误，也不能丢内容。
     */
    @Test
    void missingSessionFallsBackToOriginal() {
        String body = "内容".repeat(3000);
        AgentTool tool = new FileMarkerAgentTool(new FakeTool("web_fetch", body), null,
                offload, 2000, stats, ToolResultOffloadPolicy.READ_FILE_TOOL);

        ToolResultBlock result = tool.callAsync(param(null, "web_fetch")).block();
        assertEquals(body, firstText(result), "落不了盘时必须原样回灌");
        assertEquals(0, stats.getToolResultsOffloaded());
    }

    /**
     * 阈值配 0 表示关闭句柄化。
     */
    @Test
    void zeroThresholdKeepsEverythingInline() {
        String body = "内容".repeat(3000);
        AgentTool tool = new FileMarkerAgentTool(new FakeTool("web_fetch", body), null,
                offload, 0, stats, ToolResultOffloadPolicy.READ_FILE_TOOL);

        ToolResultBlock result = tool.callAsync(param(SESSION, "web_fetch")).block();
        assertEquals(body, firstText(result));
        assertEquals(0, stats.getToolResultsOffloaded());
    }

    /**
     * 落盘文件名带 `tool-results/` 目录，与模型自写文件分开。
     */
    @Test
    void handleLandsInToolResultsDirectory() {
        String body = "内容".repeat(3000);
        AgentTool tool = new FileMarkerAgentTool(new FakeTool("web_fetch", body), null,
                offload, 2000, stats, ToolResultOffloadPolicy.READ_FILE_TOOL);
        tool.callAsync(param(SESSION, "web_fetch")).block();

        Path stored = findStoredFile();
        assertTrue(stored.toString().replace('\\', '/').contains("/tool-results/"), stored.toString());
        assertTrue(stored.getFileName().toString().startsWith("web_fetch-"), stored.getFileName().toString());
    }

    /**
     * 检索专家只绑了检索工具（没有读文件工具）：超长结果<b>不落盘</b>，正文原样交给
     * 回灌截断，提示里绝不能出现任何工具名。
     * <p>
     * 这是本刀的核心回归：早期实现无条件写「请调用 workspaceReadFile」，专家照做→工具不存在
     * →再次检索→连败 3 次把自己熔断。
     */
    @Test
    void searchOnlyExpertKeepsBodyInlineAndNamesNoTool() {
        String body = "知识库命中：制度第 3 条\n" + "条款正文".repeat(2000);
        AgentTool tool = new FileMarkerAgentTool(new FakeTool("searchKnowledgeBase", body), null,
                offload, 2000, stats, null);

        ToolResultBlock result = tool.callAsync(param(SESSION, "searchKnowledgeBase")).block();
        String text = firstText(result);

        assertEquals(body, text, "没有读文件工具时必须原样回灌，交给回灌截断");
        assertFalse(text.contains("workspaceReadFile"), "不得点名当前专家没有的工具");
        assertEquals(0, stats.getToolResultsOffloaded());
        assertFalse(Files.exists(TEST_ROOT.resolve("trial-555666777")), "不该产生工作区文件");
    }

    // ---------------- 夹具 ----------------

    private static RuntimeContext.Builder baseContext() {
        return RuntimeContext.builder();
    }

    private static ToolCallParam param(String sessionId, String toolName) {
        RuntimeContext.Builder builder = baseContext();
        if (sessionId != null) {
            builder.sessionId(sessionId);
        }
        return ToolCallParam.builder()
                .toolUseBlock(io.agentscope.core.message.ToolUseBlock.builder()
                        .id("call-1")
                        .name(toolName)
                        .input(Map.of())
                        .build())
                .input(Map.of())
                .runtimeContext(builder.build())
                .build();
    }

    private static String firstText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }

    private Path findStoredFile() {
        Path dir = TEST_ROOT.resolve("trial-555666777").resolve("tool-results");
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).findFirst().orElseThrow();
        } catch (IOException ex) {
            throw new IllegalStateException("没有落盘文件", ex);
        }
    }

    private static String readRaw(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void deleteQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 占用中的文件留给系统临时目录清理
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /**
     * 受控的工具替身：固定返回给定正文。
     */
    private static final class FakeTool implements AgentTool {
        private final String name;
        private final String body;
        private final io.agentscope.core.message.ToolResultState state;

        private FakeTool(String name, String body) {
            this(name, body, null);
        }

        private FakeTool(String name, String body, io.agentscope.core.message.ToolResultState state) {
            this.name = name;
            this.body = body;
            this.state = state;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "测试工具";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of();
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            // 与引擎一致：工具方法返回值里 id / name 为 null，只有 output
            return Mono.just(new ToolResultBlock(null, null,
                    List.of(TextBlock.builder().text(body).build()), null, state));
        }
    }
}

