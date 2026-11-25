package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车间类内置工具（比对 / 汇总 / 去重 / 扫描 / 填空 / 清单 / 日期 / 编号）的行为窄测。所属域：runtime。
 *
 * <p>只操作系统临时目录里的本测试专用根，不连数据库、不建 Spring 容器、不调用模型。
 * 钉住的关键契约：
 * <ul>
 *   <li>工具名与 {@code tool_item.name} 一致（装配时按名字找实现）；</li>
 *   <li>结果必须是确定性的，不依赖模型判断；</li>
 *   <li>写盘类工具默认不覆盖，避免跨车间冲掉产出。</li>
 * </ul>
 */
class WorkshopToolsTest {

    private static final String SESSION = "trial-424242-agent-1-111";

    private static final Path TEST_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "agenthub_workshop_test");

    private final WorkspaceFileTool files = new WorkspaceFileTool(new WorkspaceSandbox(TEST_ROOT.toString()));

    private final TextDiffTool diff = new TextDiffTool(files);
    private final TableSummaryTool summary = new TableSummaryTool(files);
    private final ListDedupTool dedup = new ListDedupTool(files);
    private final SensitiveWordScanTool scan = new SensitiveWordScanTool(files);
    private final TemplateFillTool fill = new TemplateFillTool(files);
    private final WorkspaceInventoryTool inventory = new WorkspaceInventoryTool(files);
    private final DateOffsetTool dates = new DateOffsetTool();
    private final SerialNumberTool serials = new SerialNumberTool();

    @AfterEach
    void cleanup() {
        deleteQuietly(TEST_ROOT.resolve("trial-424242"));
    }

    /**
     * 比对要能识别修改与新增，且行号对得上；紧邻的删改会合成一条「修改」（这是有意的口径）。
     */
    @Test
    void diffReportsModifyAndInsert() {
        write("原稿A.md", "第一行\n第二行\n第三行\n");
        write("原稿B.md", "第一行\n第二行（改）\n第三行\n第四行\n");

        String result = diff.diff("原稿A.md", "原稿B.md", null, context());

        assertTrue(result.contains("修改 第 2 行 → 第 2 行"), result);
        assertTrue(result.contains("新增 新稿第 4 行"), result);
        assertTrue(result.contains("修改 1、新增 1、删除 0"), result);
    }

    /**
     * 只有删除时不能报成修改：删掉整段末尾两行应得到两条删除条目。
     */
    @Test
    void diffReportsDeletesWhenNewFileIsShorter() {
        write("长稿.md", "第一行\n第二行\n第三行\n");
        write("短稿.md", "第一行\n");

        String result = diff.diff("长稿.md", "短稿.md", null, context());

        assertTrue(result.contains("删除 旧稿第 2 行"), result);
        assertTrue(result.contains("删除 旧稿第 3 行"), result);
        assertTrue(result.contains("修改 0、新增 0、删除 2"), result);
    }

    /**
     * 两份完全一致时要给明确结论，而不是空正文。
     */
    @Test
    void diffReportsNoDifference() {
        write("a.md", "同一行\n");
        write("b.md", "同一行\n");
        assertTrue(diff.diff("a.md", "b.md", null, context()).contains("没有差异条目"));
    }

    /**
     * 表格汇总按列名求和并分组；非数字行要计数提示，不能当成 0 静默吞掉。
     */
    @Test
    void tableSummarySumsAndGroups() {
        write("本周数据.csv", "部门,金额\n甲部,100\n甲部,50\n乙部,30\n丙部,待定\n");

        String result = summary.summary("本周数据.csv", "金额", "部门", context());

        assertTrue(result.contains("合计：180"), result);
        assertTrue(result.contains("甲部：150"), result);
        assertTrue(result.contains("乙部：30"), result);
        assertTrue(result.contains("有 1 行不是数字，已跳过"), result);
    }

    /**
     * 列名写错时要列出可用列名，模型一次就能改对。
     */
    @Test
    void tableSummaryListsColumnsOnMismatch() {
        write("t.csv", "部门,金额\n甲部,1\n");
        String result = summary.summary("t.csv", "钱", null, context());
        assertTrue(result.contains("找不到列「钱」"), result);
        assertTrue(result.contains("部门"), result);
        assertTrue(result.contains("金额"), result);
    }

    /**
     * 去重要保留首次出现顺序，并把重复次数算对；引号包裹的名字视为同一个。
     */
    @Test
    void dedupKeepsOrderAndCountsDuplicates() {
        write("名单.txt", "张三\n李四\n\"张三\"\n王五\n李四\n\n");

        String result = dedup.dedup("名单.txt", context());

        assertTrue(result.contains("去重后：3 条"), result);
        assertTrue(result.contains("重复 2 条"), result);
        assertTrue(result.contains("张三：出现 2 次"), result);
        // 顺序：张三 → 李四 → 王五
        int zhang = result.indexOf("- 张三");
        int wang = result.indexOf("- 王五");
        assertTrue(zhang > 0 && wang > zhang, result);
    }

    /**
     * 扫描要给出精确行列与上下文；词表为空必须报错，不能返回「0 命中」的假成功。
     */
    @Test
    void scanReportsPositionsAndRejectsEmptyDictionary() {
        write("通知草稿.md", "请各部门按时提交。\n逾期未交将通报批评。\n");
        write("敏感词.txt", "逾期\n通报\n");

        String result = scan.scan("通知草稿.md", "敏感词.txt", null, context());
        assertTrue(result.contains("命中 2 处"), result);
        assertTrue(result.contains("第 2 行"), result);

        write("空词表.txt", "\n\n");
        assertTrue(scan.scan("通知草稿.md", "空词表.txt", null, context()).startsWith("扫描失败"));
    }

    /**
     * 模板填空只替换占位；未给值的占位要保留并提示；输出不能覆盖模板本身。
     */
    @Test
    void templateFillReplacesAndKeepsUnknownPlaceholders() {
        write("通知模板.md", "# 通知\n\n部门：{{部门}}\n日期：{{日期}}\n签发：{{签发人}}\n");

        String result = fill.fill("通知模板.md", "通知成稿.md",
                "{\"部门\":\"运营部\",\"日期\":\"2026-11-20\"}", null, context());

        assertTrue(result.contains("已替换 2 处"), result);
        assertTrue(result.contains("未提供取值"), result);
        assertTrue(result.contains("签发人"), result);
        assertTrue(read("通知成稿.md").contains("部门：运营部"));
        assertTrue(read("通知成稿.md").contains("{{签发人}}"), "未提供的占位必须原样保留");
        assertTrue(result.startsWith("填充未完成"), result);
        assertFalse(result.contains("模板填充完成"), result);
    }

    /**
     * 值里的工作日规则要在落盘前换成日期，不能把 T+2工作日 原样写进文件。
     */
    @Test
    void templateFillResolvesWorkdayRule() {
        write("请假模板.md", "截止日期：{{截止日期}}\n");
        String expected = DateOffsetTool.isoOf(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")), "T+2工作日");

        String result = fill.fill("请假模板.md", "请假成稿.md",
                "{\"截止日期\":\"T+2工作日\"}", null, context());

        assertTrue(result.startsWith("模板填充完成"), result);
        String written = read("请假成稿.md");
        assertTrue(written.contains(expected), written);
        assertFalse(written.contains("T+2"), written);
    }

    /**
     * 输出与模板同名时必须拒绝，否则会把模板改掉。
     */
    @Test
    void templateFillRejectsSameName() {
        write("通知模板.md", "{{部门}}\n");
        assertTrue(fill.fill("通知模板.md", "通知模板.md", "{\"部门\":\"甲\"}", null, context())
                .startsWith("填充失败"));
    }

    /**
     * 清单要列出文件名、大小与修改时间（表格格式，便于直接抄进 Markdown）。
     */
    @Test
    void inventoryListsSizeAndTime() {
        write("a.md", "hello");
        String result = inventory.inventory(null, context());
        assertTrue(result.contains("/workspace/a.md"), result);
        assertTrue(result.contains("| 文件 | 大小 | 修改时间 |"), result);
        assertTrue(result.contains("5 B"), result);
    }

    /**
     * 日期推算：T+2 跨周末时，自然日与工作日结果必须不同，且说明要写清口径。
     */
    @Test
    void dateOffsetNaturalVsWorkday() {
        // 2026-11-13 是周五
        String natural = dates.offset("2026-11-13", "T+2", null, null);
        assertTrue(natural.contains("= 2026-11-15"), natural);
        assertTrue(natural.contains("自然日"), natural);

        String workday = dates.offset("2026-11-13", "T+2工作日", null, null);
        assertTrue(workday.contains("= 2026-11-17"), workday);

        String backward = dates.offset("2026-11-16", "T-1", null, null);
        assertTrue(backward.contains("= 2026-11-15"), backward);
    }

    /**
     * 放假日要能被跳过，调休日要能补回成工作日。
     */
    @Test
    void dateOffsetHonorsHolidayAndExtraWorkday() {
        // 2026-11-16 周一 + 1 工作日，但 17 号放假 → 落到 18 号
        assertTrue(dates.offset("2026-11-16", "T+1工作日", "2026-11-17", null)
                .contains("= 2026-11-18"));
        // 2026-11-14 周六 + 1 工作日，把周六当调休上班 → 落到 14 号
        assertTrue(dates.offset("2026-11-13", "T+1工作日", null, "2026-11-14")
                .contains("= 2026-11-14"));
    }

    /**
     * 无法识别的规则要给出可读原因，而不是猜一个日期。
     */
    @Test
    void dateOffsetRejectsUnknownRule() {
        String result = dates.offset("2026-11-16", "下周三", null, null);
        assertTrue(result.startsWith("日期推算失败"), result);
        assertTrue(result.contains("T+2"), result);
    }

    /**
     * 编号生成要位数一致、首尾正确；序号超出位数时自动放宽并在结果里点明。
     */
    @Test
    void serialNumbersArePaddedAndConsistent() {
        String result = serials.generate("HT-2026-", 1, 10, 4, "NONE");
        assertTrue(result.contains("HT-2026-0001"), result);
        assertTrue(result.contains("HT-2026-0010"), result);

        String widened = serials.generate("NO-", 999, 3, 3, "NONE");
        assertTrue(widened.contains("已自动放宽到 4 位"), widened);
        assertTrue(widened.contains("NO-0999"), widened);
        assertFalse(widened.contains("NO-999 "), widened);
    }

    /**
     * 编号生成明确声明不查库，避免模型误以为天然唯一。
     */
    @Test
    void serialNumbersDeclareNoUniquenessCheck() {
        assertTrue(serials.generate("X-", 1, 2, 3, "NONE").contains("未查库"));
    }

    /**
     * 不存在的文件要给出可行动的中文提示，不能抛异常、也不能泄漏本机路径。
     */
    @Test
    void missingFileGivesActionableMessage() {
        String result = diff.diff("不存在.md", "也不存在.md", null, context());
        assertTrue(result.startsWith("比对失败"), result);
        assertFalse(result.contains(TEST_ROOT.toString()), result);
    }

    private RuntimeContext context() {
        return RuntimeContext.builder().sessionId(SESSION).build();
    }

    private void write(String name, String content) {
        String result = files.writeFile(name, content, false, context());
        assertTrue(result.contains("文件已成功写入"), result);
    }

    private String read(String name) {
        return files.readWholeText(context(), name);
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
