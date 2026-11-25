package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话工作区文件工具的沙箱与截断行为。所属域：runtime。
 * 只操作系统临时目录下的本测试专用根目录，不连数据库、不建 Spring 容器。
 */
class WorkspaceFileToolTest {

    private static final String SESSION = "trial-987654321-agent-7-123456789";

    /** 测试专用工作区根，指向系统临时目录，避免污染仓库与并行构建 */
    private static final Path TEST_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "agenthub_workspace_test");

    private final WorkspaceFileTool tool = new WorkspaceFileTool(new WorkspaceSandbox(TEST_ROOT.toString()));

    /**
     * 每个用例结束清掉自己造的沙箱目录，避免临时目录越积越多。
     */
    @AfterEach
    void cleanup() {
        // 只删本测试会话目录，避开并行执行的其他用例
        deleteQuietly(TEST_ROOT.resolve("trial-987654321"));
    }

    /**
     * 尾部纳秒不同的两轮调用必须落进同一个会话目录，否则上一轮的文件下一轮就找不到。
     */
    @Test
    void sameSessionDifferentTurnSharesDirectory() {
        String first = tool.writeFile("a.md", "第一轮", false, context("trial-987654321-agent-7-111"));
        String second = tool.writeFile("a.md", "第二轮", true, context("trial-987654321-agent-7-222"));

        assertTrue(first.contains("/workspace/a.md"), first);
        assertTrue(second.contains("/workspace/a.md"), second);
        // 追加成功说明两轮确实写的同一个文件；否则第二轮会是覆盖且内容只有一份
        assertTrue(tool.readFile("a.md", 1, 100, context("trial-987654321-agent-8-333")).contains("第一轮"));
    }

    /**
     * 目录穿越、绝对路径、盘符与脚本后缀都必须被拒，且不把本机绝对路径回给模型。
     */
    @Test
    void rejectsEscapeAndExecutable() {
        RuntimeContext ctx = context(SESSION);
        assertTrue(tool.writeFile("../evil.txt", "x", false, ctx).startsWith("写入失败"));
        assertTrue(tool.writeFile("../../evil.txt", "x", false, ctx).startsWith("写入失败"));
        assertTrue(tool.writeFile("a/../../evil.txt", "x", false, ctx).startsWith("写入失败"));
        assertTrue(tool.writeFile("/etc/passwd", "x", false, ctx).startsWith("写入失败"));
        assertTrue(tool.writeFile("C:/Windows/evil.txt", "x", false, ctx).startsWith("写入失败"));
        assertTrue(tool.writeFile("run.sh", "x", false, ctx).startsWith("写入失败"));
        assertTrue(tool.writeFile("run.BAT", "x", false, ctx).startsWith("写入失败"));
        assertTrue(tool.writeFile("tool.exe.", "x", false, ctx).startsWith("写入失败"));
        assertTrue(tool.writeFile("", "x", false, ctx).startsWith("写入失败"));
        // 重复前缀剥不干净时按越界拒绝，不能靠层层剥离蒙混到沙箱内
        assertTrue(tool.writeFile("/workspace//workspace/x.txt", "x", false, ctx).startsWith("写入失败"));
    }

    /**
     * 写入结果里的 /workspace/xxx 常被模型原样回传，必须能直接读回，否则会绕成死循环。
     */
    @Test
    void acceptsVirtualPathEchoedBack() {
        RuntimeContext ctx = context(SESSION);
        String written = tool.writeFile("notes/a.md", "内容", false, ctx);
        assertTrue(written.contains("/workspace/notes/a.md"), written);

        assertTrue(tool.readFile("/workspace/notes/a.md", null, null, ctx).contains("1 | 内容"));
        assertTrue(tool.readFile("workspace/notes/a.md", null, null, ctx).contains("1 | 内容"));
    }

    /**
     * 缺 sessionId 时宁可不可用，也不落到某个公共目录里串文件。
     */
    @Test
    void rejectsMissingSession() {
        assertTrue(tool.writeFile("a.md", "x", false, null).startsWith("写入失败"));
        assertTrue(tool.writeFile("a.md", "x", false, context(null)).startsWith("写入失败"));
        assertTrue(tool.listFiles(context(null)).startsWith("列出文件失败"));
    }

    /**
     * 读取带行号；超过 100 行或 2000 字符时截断，并给出可续读的 start_line。
     */
    @Test
    void truncatesLongRead() {
        RuntimeContext ctx = context(SESSION);
        StringBuilder longText = new StringBuilder();
        for (int i = 1; i <= 250; i++) {
            longText.append("第").append(i).append("行的内容\n");
        }
        tool.writeFile("long.md", longText.toString(), false, ctx);

        String first = tool.readFile("long.md", null, null, ctx);
        assertTrue(first.contains("1 | 第1行"), first);
        assertTrue(first.contains("内容过长已截断，请使用 start_line="), first);

        String resumed = tool.readFile("long.md", 101, 100, ctx);
        assertTrue(resumed.contains("101 | 第101行"), resumed);
        assertFalse(resumed.contains("1 | 第1行"), resumed);
    }

    /**
     * 列目录返回虚拟路径与大小；空目录给出明确提示而不是空串。
     */
    @Test
    void listsFilesWithSize() {
        RuntimeContext ctx = context(SESSION);
        assertTrue(tool.listFiles(ctx).contains("还没有任何文件"));

        tool.writeFile("notes/plan.md", "hello", false, ctx);
        String listed = tool.listFiles(ctx);
        assertTrue(listed.contains("/workspace/notes/plan.md"), listed);
        assertTrue(listed.contains("B"), listed);
    }

    /**
     * 配置了 root-path 时，文件必须真的落在该目录下，而不是系统临时目录。
     */
    @Test
    void writesUnderConfiguredRoot() {
        RuntimeContext ctx = context(SESSION);
        tool.writeFile("a.md", "落盘位置", false, ctx);

        // 配置值原样生效：会话目录直接建在 TEST_ROOT 下
        assertTrue(Files.isRegularFile(TEST_ROOT.resolve("trial-987654321").resolve("a.md")),
                "文件应写在配置的工作区根目录下");
    }

    /**
     * 留空 root-path 时回落到系统临时目录的 agenthub_workspace，保持旧行为。
     */
    @Test
    void blankRootPathFallsBackToTempDir() {
        WorkspaceFileTool fallback = new WorkspaceFileTool(new WorkspaceSandbox("  "));
        Path expected = Path.of(System.getProperty("java.io.tmpdir"), "agenthub_workspace", "trial-123");
        try {
            assertTrue(fallback.writeFile("b.md", "x", false, context("trial-123")).contains("/workspace/b.md"));
            assertTrue(Files.isRegularFile(expected.resolve("b.md")));
        } finally {
            // 只删本次会话目录，不动共享的 agenthub_workspace 父目录
            deleteQuietly(expected);
        }
    }

    /**
     * 用引擎同款 sessionId 格式构造上下文，覆盖「尾部纳秒」这一真实形态。
     */
    private static RuntimeContext context(String sessionId) {
        // sessionId 为空时不调用 sessionId()，模拟引擎未注入上下文的场景
        RuntimeContext.Builder builder = RuntimeContext.builder();
        if (sessionId != null) {
            builder.sessionId(sessionId);
        }
        return builder.build();
    }

    /**
     * 递归删除目录，失败不影响断言（临时目录清理只是收尾）。
     *
     * @param root 要删除的根目录
     */
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
     * 仅供 IDE 手动排查：确认写入内容确实是 UTF-8。
     */
    static String readRaw(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
