package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作空间样例能否得出预期演示结论。所属域：runtime。
 *
 * <p>为什么值得单独一条测试：样例文件是「手点演示」的唯一素材，如果它们算不出可辨识的结论
 * （差异太少、没有重复、扫不到词、占位对不上），演示就会变成「看起来都成功但什么也没证明」。
 * 本测试直接读仓库里的 {@code samples/workspace-samples-2026-11-16/}，把 6 个文件写进临时沙箱，
 * 逐个跑车间工具并钉住预期数字。
 *
 * <p>样例目录缺失时跳过（干净克隆若不带 samples 不应因此挂掉）。
 */
class WorkshopSampleSmokeTest {

    private static final Path SAMPLES = Path.of("..", "samples", "workspace-samples-2026-11-16");

    private static final Path TEST_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "agenthub_sample_smoke");

    private final WorkspaceFileTool files = new WorkspaceFileTool(new WorkspaceSandbox(TEST_ROOT.toString()));

    @AfterEach
    void cleanup() {
        deleteQuietly(TEST_ROOT.resolve("trial-606060"));
    }

    /**
     * 比对：原稿A 与 原稿B 必须能看出「改了哪一句、加了哪一句、加了一个敏感表述」。
     */
    @Test
    void diffSamplesShowRecognizableChanges() throws IOException {
        if (!prepare()) {
            return;
        }
        String result = new TextDiffTool(files).diff("原稿A.md", "原稿B.md", null, context());
        // 时间与地点那句被改写：应出现「修改」
        assertTrue(result.contains("修改"), result);
        // 改稿加了「本材料为内部资料」一句：应出现「新增」
        assertTrue(result.contains("新增"), result);
        assertTrue(result.contains("内部资料"), result);
        // 旧稿独有的「请于会议前一天提交材料」被替换：应出现「删除」
        assertTrue(result.contains("删除"), result);
    }

    /**
     * 汇总：金额合计 43000.75，三组小计正确，且「待定」那一行被跳过并计数。
     */
    @Test
    void tableSampleSumsWithSkippedRow() throws IOException {
        if (!prepare()) {
            return;
        }
        String result = new TableSummaryTool(files).summary("本周数据.csv", "金额", "部门", context());
        assertTrue(result.contains("合计：43000.75"), result);
        assertTrue(result.contains("销售部：17000.5"), result);
        assertTrue(result.contains("市场部：10700"), result);
        assertTrue(result.contains("技术部：15300.25"), result);
        // 技术部有一行「待定」：必须被计数而不是当 0
        assertTrue(result.contains("有 1 行不是数字，已跳过"), result);
    }

    /**
     * 去重：9 行里 张三 / 李四 / 王五 各重复一次，去重后 6 条，重复 3 条。
     */
    @Test
    void listSampleDeduplicatesToSix() throws IOException {
        if (!prepare()) {
            return;
        }
        String result = new ListDedupTool(files).dedup("名单.txt", context());
        assertTrue(result.contains("去重后：6 条"), result);
        assertTrue(result.contains("重复 3 条"), result);
        // 带引号的 "张三" 与裸张三算同一个
        assertTrue(result.contains("张三：出现 2 次"), result);
    }

    /**
     * 扫描：改稿里命中「逾期」与「内部资料」两处；词表里其余四个词不应误报。
     */
    @Test
    void scanSampleHitsTwoWords() throws IOException {
        if (!prepare()) {
            return;
        }
        String result = new SensitiveWordScanTool(files).scan("原稿B.md", "敏感词.txt", null, context());
        assertTrue(result.contains("命中 2 处"), result);
        assertTrue(result.contains("逾期"), result);
        assertTrue(result.contains("内部资料"), result);
        // 未出现过的词不该被报成命中
        assertTrue(!result.contains("命中「罚息」"), result);
    }

    /**
     * 填空：模板 8 个占位，给 5 个值时应替换 5 处、保留 3 个占位并逐个列出。
     */
    @Test
    void templateSampleFillsAndKeepsMissing() throws IOException {
        if (!prepare()) {
            return;
        }
        String values = "{\"标题\":\"关于周例会调整的通知\",\"部门\":\"行政部\",\"日期\":\"2026-11-16\","
                + "\"编号\":\"XZ-2026-0001\",\"正文\":\"本周例会调整至周五上午十点。\"}";
        String result = new TemplateFillTool(files).fill("通知模板.md", "通知成稿.md", values, null, context());
        assertTrue(result.contains("已替换 5 处"), result);
        // 未给值的三个占位必须原样列出
        assertTrue(result.contains("截止日期"), result);
        assertTrue(result.contains("联系人"), result);
        assertTrue(result.contains("签发人"), result);

        String filled = files.readWholeText(context(), "通知成稿.md");
        assertTrue(filled.contains("# 关于周例会调整的通知"), filled);
        assertTrue(filled.contains("XZ-2026-0001"), filled);
        // 除占位外一个字都不该变：模板里的固定句必须还在
        assertTrue(filled.contains("本通知自发布之日起执行"), filled);
        assertTrue(filled.contains("{{签发人}}"), filled);
    }

    /**
     * 清单：样例目录里的 6 个文件都应能被列出来，且带大小与修改时间列。
     */
    @Test
    void inventoryListsAllSampleFiles() throws IOException {
        if (!prepare()) {
            return;
        }
        String result = new WorkspaceInventoryTool(files).inventory(null, context());
        for (String name : new String[]{"原稿A.md", "原稿B.md", "本周数据.csv", "名单.txt", "敏感词.txt", "通知模板.md"}) {
            assertTrue(result.contains("/workspace/" + name), name + " 未出现在清单里：" + result);
        }
        assertTrue(result.contains("| 文件 | 大小 | 修改时间 |"), result);
    }

    /**
     * 日期：以样例落款的 2026-11-10 为基准，T+2 自然日与 T+3 工作日必须给出不同结果。
     */
    @Test
    void dateSamplesCoverNaturalAndWorkday() {
        DateOffsetTool dates = new DateOffsetTool();
        // 2026-11-10 是周二
        assertTrue(dates.offset("2026-11-10", "T+2", null, null).contains("= 2026-11-12"));
        // 周四 +3 工作日跨过周末 → 下周二
        assertTrue(dates.offset("2026-11-10", "T+3工作日", null, null).contains("= 2026-11-13"));
    }

    /**
     * 编号：与样例模板里的「编号」占位配套，四位补零、同批位数一致。
     */
    @Test
    void serialSampleMatchesTemplatePlaceholder() {
        String result = new SerialNumberTool().generate("XZ-2026-", 1, 3, 4, "NONE");
        assertTrue(result.contains("XZ-2026-0001"), result);
        assertTrue(result.contains("XZ-2026-0003"), result);
    }

    /**
     * 把样例文件写进临时沙箱。
     *
     * @return true 表示样例目录存在且已写好；false 表示应跳过本用例
     * @throws IOException 读样例失败
     */
    private boolean prepare() throws IOException {
        if (!Files.isDirectory(SAMPLES)) {
            return false;
        }
        try (var list = Files.list(SAMPLES)) {
            for (Path source : list.filter(Files::isRegularFile).toList()) {
                String name = source.getFileName().toString();
                if (name.endsWith(".md") && name.equals("README.md")) {
                    continue;
                }
                String content = Files.readString(source, StandardCharsets.UTF_8);
                String written = files.writeFile(name, content, false, context());
                assertEquals(true, written.contains("文件已成功写入"), name + " 写入失败：" + written);
            }
        }
        return true;
    }

    private RuntimeContext context() {
        return RuntimeContext.builder().sessionId("trial-606060-agent-1-1").build();
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
                    // 占用中的文件留给系统清理
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
