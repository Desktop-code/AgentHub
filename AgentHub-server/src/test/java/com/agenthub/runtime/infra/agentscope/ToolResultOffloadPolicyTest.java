package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.ToolResultOffloadPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具结果落盘策略。所属域：runtime 的领域单测。
 * <p>
 * 锁死三件事：只有超阈值的成功结果才落盘；工作区工具不落盘（否则文件套文件）；
 * 回灌摘要必须显式给出路径与读取指令（模型最常见的错误是「看不到全文就当没有」）。
 */
class ToolResultOffloadPolicyTest {

    private static final int LIMIT = 2000;

    @Test
    void underThresholdStaysInline() {
        assertFalse(ToolResultOffloadPolicy.shouldOffload("web_fetch", "短结果", true, LIMIT));
    }

    /**
     * 恰好等于阈值也不落盘：阈值语义是「超过」。
     */
    @Test
    void exactlyAtThresholdStaysInline() {
        String body = "a".repeat(LIMIT);
        assertFalse(ToolResultOffloadPolicy.shouldOffload("web_fetch", body, true, LIMIT));
    }

    @Test
    void overThresholdIsOffloaded() {
        String body = "a".repeat(LIMIT + 1);
        assertTrue(ToolResultOffloadPolicy.shouldOffload("web_fetch", body, true, LIMIT));
    }

    /**
     * 失败结果不落盘：错误正文通常很短，且模型要看到完整原因才能改参数。
     */
    @Test
    void failedResultIsNeverOffloaded() {
        String body = "a".repeat(LIMIT * 2);
        assertFalse(ToolResultOffloadPolicy.shouldOffload("web_fetch", body, false, LIMIT));
    }

    /**
     * 工作区工具返回的就是文件内容，再落一份会形成「文件套文件」。
     */
    @Test
    void workspaceToolsAreExempt() {
        String body = "a".repeat(LIMIT * 2);
        assertFalse(ToolResultOffloadPolicy.shouldOffload("workspaceReadFile", body, true, LIMIT));
        assertFalse(ToolResultOffloadPolicy.shouldOffload("workspaceListFiles", body, true, LIMIT));
        assertFalse(ToolResultOffloadPolicy.shouldOffload("WorkspaceWriteFile", body, true, LIMIT));
    }

    /**
     * 阈值 ≤0 表示关闭句柄化。
     */
    @Test
    void zeroThresholdDisablesOffload() {
        String body = "a".repeat(100_000);
        assertFalse(ToolResultOffloadPolicy.shouldOffload("web_fetch", body, true, 0));
        assertFalse(ToolResultOffloadPolicy.shouldOffload("web_fetch", body, true, -1));
    }

    /**
     * 空正文不该被落盘：没有内容的文件对模型没有价值。
     */
    @Test
    void emptyBodyIsNotOffloaded() {
        assertFalse(ToolResultOffloadPolicy.shouldOffload("web_fetch", null, true, LIMIT));
        assertFalse(ToolResultOffloadPolicy.shouldOffload("web_fetch", "", true, LIMIT));
    }

    /**
     * 回灌摘要必须带：总长说明、虚拟路径、可直接使用的相对文件名、读取指引、头部摘录。
     */
    @Test
    void previewCarriesPathAndReadingHint() {
        String body = "标题：北京天气\n温度：26℃\n" + "正文".repeat(500);
        String preview = ToolResultOffloadPolicy.previewOf(
                "web_fetch", body, "/workspace/tool-results/web_fetch-123.txt", 600,
                ToolResultOffloadPolicy.READ_FILE_TOOL);

        assertTrue(preview.contains(String.valueOf(body.length())), "必须说明正文总长");
        assertTrue(preview.contains("/workspace/tool-results/web_fetch-123.txt"), "必须给出虚拟路径");
        assertTrue(preview.contains("workspaceReadFile"), "必须告诉模型怎么继续读");
        // 相对文件名要能直接传给工具：前缀不能留在参数里
        assertTrue(preview.contains("fileName=\"tool-results/web_fetch-123.txt\""), preview);
        assertTrue(preview.contains("标题：北京天气"), "必须保留开头摘录");
        assertTrue(preview.contains("摘录结束"), "必须说明摘录被截断");
        // 全文绝不能整段回灌，否则落盘就没意义了
        assertTrue(preview.length() < body.length());
    }

    /**
     * 没有读文件工具时，摘要不得点名任何工具，也不能诱导「再搜一次」。
     * <p>
     * 这是「知识专家被逼调不存在工具→反复重搜→自我熔断」的展示层防线。
     */
    @Test
    void previewWithoutReadToolNamesNoTool() {
        String body = "知识库命中\n" + "条款".repeat(500);
        String preview = ToolResultOffloadPolicy.previewOf(
                "searchKnowledgeBase", body, "/workspace/tool-results/search-1.txt", 200, null);

        assertFalse(preview.contains("workspaceReadFile"), "不能点名未绑定的工具");
        assertFalse(preview.contains("maxLines"), "没有读文件工具时不该出现任何读文件参数指引");
        assertTrue(preview.contains("/workspace/tool-results/search-1.txt"), "路径仍要如实给出");
        assertTrue(preview.contains("知识库命中"), "开头摘录必须保留");
    }

    /**
     * 读文件工具只认「真实注册」的名字：标准名优先，同义名兜底，没有则 null。
     */
    @Test
    void readToolResolvedFromRegisteredNames() {
        assertEquals(ToolResultOffloadPolicy.READ_FILE_TOOL,
                ToolResultOffloadPolicy.readToolIn(java.util.List.of("searchKnowledgeBase", "workspaceReadFile")));
        assertEquals(ToolResultOffloadPolicy.READ_FILE_TOOL,
                ToolResultOffloadPolicy.readToolIn(java.util.List.of("WORKSPACEREADFILE")));
        assertEquals("workspaceReadText",
                ToolResultOffloadPolicy.readToolIn(java.util.List.of("workspaceReadText", "searchKnowledgeBase")));
        assertTrue(ToolResultOffloadPolicy.readToolIn(java.util.List.of("searchKnowledgeBase")) == null);
        assertTrue(ToolResultOffloadPolicy.readToolIn(java.util.List.of()) == null);
    }

    /**
     * 正文短于摘要阈值时不加「摘录结束」尾巴。
     */
    @Test
    void shortBodyPreviewHasNoTruncationNote() {
        String body = "只有一个结论：晴。";
        String preview = ToolResultOffloadPolicy.previewOf("web_fetch", body, "/workspace/x.txt", 600,
                ToolResultOffloadPolicy.READ_FILE_TOOL);
        assertTrue(preview.contains("只有一个结论：晴。"));
        assertFalse(preview.contains("摘录结束"));
    }
}
