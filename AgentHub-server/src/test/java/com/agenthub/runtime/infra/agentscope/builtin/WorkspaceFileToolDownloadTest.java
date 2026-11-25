package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下载侧的沙箱行为。所属域：runtime。
 *
 * <p>这是产物下载的<b>唯一安全边界</b>，重点验证：
 * 路径穿越、绝对路径、盘符、符号链接越界都必须被拒；正常相对路径必须能找到文件。
 * 只操作系统临时目录下的测试专用根，不连数据库、不建 Spring 容器。
 */
class WorkspaceFileToolDownloadTest {

    /** 测试专用工作区根，避免污染仓库 */
    private static final Path TEST_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "agenthub_dl_test");

    private final WorkspaceFileTool tool = new WorkspaceFileTool(new WorkspaceSandbox(TEST_ROOT.toString()));

    /**
     * 清掉本测试造的沙箱，避免临时目录越积越多。
     */
    @AfterEach
    void cleanup() {
        deleteQuietly(TEST_ROOT);
    }

    /**
     * 工具正常写入的文件，下载侧必须能按同一会话键找到——这是「生成即下载」的前提。
     */
    @Test
    void resolvesFileWrittenByTool() {
        // 引擎侧 sessionId 带尾部纳秒，下载侧只有数字主键：两者必须归一到同一目录
        tool.writeFile("report.md", "内容", false, context("trial-42-agent-9-123456"));

        Path resolved = tool.resolveForDownload("42", "report.md");

        assertTrue(Files.isRegularFile(resolved), "下载侧应能解析到工具刚写入的文件");
    }

    /**
     * 子目录里的文件也能下载，且 /workspace/ 前缀被正确剥离。
     */
    @Test
    void resolvesNestedFileAndVirtualPrefix() {
        tool.writeFile("notes/plan.md", "计划", false, context("trial-43-agent-1-1"));

        assertTrue(Files.isRegularFile(tool.resolveForDownload("43", "notes/plan.md")));
        assertTrue(Files.isRegularFile(tool.resolveForDownload("43", "/workspace/notes/plan.md")));
    }

    /**
     * 路径穿越必须被拒：这是下载接口最大的风险面。
     */
    @Test
    void rejectsTraversal() {
        assertThrows(WorkspaceAccessException.class, () -> tool.resolveForDownload("44", "../../../../etc/passwd"));
        assertThrows(WorkspaceAccessException.class, () -> tool.resolveForDownload("44", "../secret.txt"));
        assertThrows(WorkspaceAccessException.class, () -> tool.resolveForDownload("44", "a/../../b.txt"));
        // 绝对路径与盘符（Windows 上尤其危险）同样拒绝
        assertThrows(WorkspaceAccessException.class, () -> tool.resolveForDownload("44", "/etc/passwd"));
        assertThrows(WorkspaceAccessException.class, () -> tool.resolveForDownload("44", "C:/Windows/win.ini"));
        // NTFS 备用数据流入口
        assertThrows(WorkspaceAccessException.class, () -> tool.resolveForDownload("44", "a.txt:stream"));
    }

    /**
     * 空文件名与越界会话标识都不该解析出任何路径。
     */
    @Test
    void rejectsBlankNameAndSuspiciousSession() {
        assertThrows(WorkspaceAccessException.class, () -> tool.resolveForDownload("45", "  "));
        // 会话标识里的 .. 与斜杠会被白名单化成下划线，最终落在一个不存在的目录里；
        // 关键是它绝不能解析到工作区根之外
        Path resolved = tool.resolveForDownload("../46/../..", "a.md");
        assertTrue(resolved.normalize().startsWith(TEST_ROOT.toAbsolutePath().normalize()),
                "会话标识归一后仍必须落在工作区根之内，实际 " + resolved);
    }

    /**
     * 不同会话目录互相隔离：A 会话解析不到 B 会话的文件。
     */
    @Test
    void isolatesSessions() {
        tool.writeFile("a.md", "A 的文件", false, context("trial-100-agent-1-1"));

        Path other = tool.resolveForDownload("101", "a.md");

        assertTrue(!Files.exists(other), "别的会话不应解析到本会话的文件");
        assertEquals("trial-101", other.getParent().getFileName().toString());
    }

    /**
     * 用引擎同款 sessionId 格式构造上下文。
     *
     * @param sessionId 会话标识；null 表示不注入
     * @return 引擎上下文
     */
    private static RuntimeContext context(String sessionId) {
        RuntimeContext.Builder builder = RuntimeContext.builder();
        if (sessionId != null) {
            builder.sessionId(sessionId);
        }
        return builder.build();
    }

    /**
     * 递归删除目录，失败不影响断言。
     *
     * @param root 要删的根
     */
    private static void deleteQuietly(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 占用中的文件留给系统清理
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
