package com.agenthub.runtime.infra.agentscope.builtin;

import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车间工具的调用名契约。所属域：runtime。
 *
 * <p>为什么单独一条测试：这些工具名同时出现在
 * <ul>
 *   <li>Java {@code @Tool(name)}（运行时按名字找实现）；</li>
 *   <li>{@code tool_item.name}（目录种子）；</li>
 *   <li>{@code agent_tool_bind} 的绑定（按 name 反查）。</li>
 * </ul>
 * 三处只要有一处改名，装配期就会报「没有 @Tool(name) 与之相同的实现」。
 * 本测试锁死这 8 个名字，改名必须同时改种子与本类。
 */
class WorkshopToolNamesTest {

    /**
     * 工具名 → 实现类。与 sql/temp/data-init-workshop-tools-2026-11-16.sql 逐条对应。
     */
    private static final Map<String, Class<?>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put( "textDiffReport", TextDiffTool.class);
        EXPECTED.put("tableSummary", TableSummaryTool.class);
        EXPECTED.put("listDedup", ListDedupTool.class);
        EXPECTED.put("dateOffset", DateOffsetTool.class);
        EXPECTED.put("serialNumberGenerate", SerialNumberTool.class);
        EXPECTED.put("sensitiveWordScan", SensitiveWordScanTool.class);
        EXPECTED.put("workspaceInventory", WorkspaceInventoryTool.class);
        EXPECTED.put("templateFill", TemplateFillTool.class);
    }

    /**
     * 每个类必须声明且只声明一个同名的 @Tool 方法。
     */
    @Test
    void eachClassDeclaresExactlyOneMatchingTool() {
        EXPECTED.forEach((name, type) -> {
            Method matched = null;
            int count = 0;
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    continue;
                }
                count++;
                if (name.equals(tool.name())) {
                    matched = method;
                }
            }
            assertEquals(1, count, type.getSimpleName() + " 应只声明一个 @Tool 方法");
            assertNotNull(matched, type.getSimpleName() + " 的 @Tool(name) 必须是 " + name);
        });
    }

    /**
     * 只有模板填空会写文件（readOnly=false），其余 7 条必须是只读——
     * 只读声明会影响引擎的并发与重试判断，写错会让只读工具被当成副作用工具串行。
     */
    @Test
    void onlyTemplateFillIsWritable() {
        EXPECTED.forEach((name, type) -> {
            for (Method method : type.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null || !name.equals(tool.name())) {
                    continue;
                }
                boolean writable = !tool.readOnly();
                assertEquals("templateFill".equals(name), writable,
                        name + " 的 readOnly 声明与设计不一致（只有 templateFill 会写盘）");
            }
        });
    }

    /**
     * 工具 description 必须是中文且点明关键纪律，避免模型用它去做不该做的事。
     */
    @Test
    void descriptionsCarryKeyDiscipline() {
        assertTrue(descriptionOf(TextDiffTool.class, "textDiffReport").contains("逐行"));
        assertTrue(descriptionOf(TableSummaryTool.class, "tableSummary").contains("表头"));
        assertTrue(descriptionOf(ListDedupTool.class, "listDedup").contains("不做拼音"));
        assertTrue(descriptionOf(DateOffsetTool.class, "dateOffset").contains("工作日"));
        assertTrue(descriptionOf(SerialNumberTool.class, "serialNumberGenerate").contains("不查库"));
        assertTrue(descriptionOf(SensitiveWordScanTool.class, "sensitiveWordScan").contains("词表"));
        assertTrue(descriptionOf(WorkspaceInventoryTool.class, "workspaceInventory").contains("修改时间"));
        assertTrue(descriptionOf(TemplateFillTool.class, "templateFill").contains("占位"));
    }

    /**
     * 8 条工具的 Java schema 都必须能生成，且必填项与目录种子一致。
     *
     * <p>为什么要单独验：装配时模型看到的 parameters 全部由注解生成（{@code BuiltinToolSchemas}），
     * 库表的 schema 只是退路。若某个 {@code @BuiltinParam} 写成非编译期常量，
     * 生成会拿到 null 而静默少一个 default，模型就会猜参数值。
     */
    @Test
    void javaSchemasAreGeneratedWithExpectedRequired() {
        assertEquals(java.util.List.of("fileA", "fileB"), requiredOf(TextDiffTool.class, "textDiffReport"));
        assertEquals(java.util.List.of("fileName", "valueColumn"), requiredOf(TableSummaryTool.class, "tableSummary"));
        assertEquals(java.util.List.of("fileName"), requiredOf(ListDedupTool.class, "listDedup"));
        assertEquals(java.util.List.of("baseDate", "rule"), requiredOf(DateOffsetTool.class, "dateOffset"));
        // 编号生成全部可选：模型常只给「10 个编号」这种口语，缺省值必须能兜住
        assertEquals(java.util.List.of(), requiredOf(SerialNumberTool.class, "serialNumberGenerate"));
        assertEquals(java.util.List.of("fileName", "wordFile"),
                requiredOf(SensitiveWordScanTool.class, "sensitiveWordScan"));
        assertEquals(java.util.List.of(), requiredOf(WorkspaceInventoryTool.class, "workspaceInventory"));
        assertEquals(java.util.List.of("templateFile", "outputFile", "valuesJson"),
                requiredOf(TemplateFillTool.class, "templateFill"));
    }

    /**
     * 取某工具 Java schema 的 required 列表。
     *
     * @param type 实现类
     * @param name 调用名
     * @return required；没有则空列表
     */
    private static java.util.List<?> requiredOf(Class<?> type, String name) {
        String json = com.agenthub.runtime.infra.agentscope.BuiltinToolSchemas.jsonOf(type, name);
        assertNotNull(json, name + " 的 Java schema 生成失败");
        Map<String, Object> parameters =
                com.agenthub.runtime.infra.agentscope.BuiltinToolSchemas.parametersOf(type, name);
        assertNotNull(parameters, name + " 的 parameters 为空");
        return com.agenthub.runtime.domain.service.RequiredToolArgs.requiredOf(parameters);
    }

    /**
     * 种子 SQL 里的 input_schema_json 必须与 Java 注解生成结果逐字一致。
     *
     * <p>为什么要锁：库表 schema 只供控制台展示（运行时以注解为准），但它也是
     * {@code BuiltinSchemaBackfill} 的退路与人工排查的依据。两边漂移时没人会发现——
     * 控制台显示能传 7 天，模型实际只拿到 7 天，看着都对，排查时却对不上。
     * 本测试直接读种子文件比对，改名或改参数约束时两边必须一起改。
     *
     * <p>注意：读的是仓库内的 {@code sql/temp} 文件，不连数据库。文件缺失时跳过（干净克隆不含 temp）。
     */
    @Test
    void seedSqlSchemasMatchJavaAnnotations() throws Exception {
        java.nio.file.Path seed = java.nio.file.Path.of("..", "sql", "temp",
                "data-init-workshop-tools-2026-11-16.sql");
        if (!java.nio.file.Files.isRegularFile(seed)) {
            return;
        }
        String text = java.nio.file.Files.readString(seed, java.nio.charset.StandardCharsets.UTF_8);
        // 逐条抓 '{"type":"object"...}', 的 SQL 单引号字面量
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("'(\\{\"type\":\"object\".*?\\})',", java.util.regex.Pattern.DOTALL)
                .matcher(text);
        List<String> fromSql = new java.util.ArrayList<>();
        while (matcher.find()) {
            // MySQL 的单引号字面量里只把 \" 还原成 "，\\ 保持原样
            fromSql.add(matcher.group(1).replace("\\\"", "\""));
        }
        List<String> fromJava = new java.util.ArrayList<>();
        EXPECTED.forEach((name, type) -> fromJava.add(
                com.agenthub.runtime.infra.agentscope.BuiltinToolSchemas.jsonOf(type, name)));
        assertEquals(fromJava, fromSql,
                "种子 SQL 的 input_schema_json 必须与 Java 注解生成结果逐字一致（顺序：比对/汇总/去重/日期/编号/扫描/清单/填空）");
    }

    private static String descriptionOf(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && name.equals(tool.name())) {
                return tool.description();
            }
        }
        return "";
    }
}
