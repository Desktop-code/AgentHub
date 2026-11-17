package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 表格汇总工具（{@code tableSummary}）。所属域：runtime 的 infra。
 *
 * <p>职责：读会话工作区里的 CSV 或 XLSX / XLS，按指定数值列做「合计 + 可选分组」，输出结构化结果。
 * 只做确定性加总，不让模型自己读表心算——这是「先调工具再写文件、不要手算」的落点。
 *
 * <p>为什么入参用「列名」而不是列序号：模型读表头比数列号更可靠，人看结果也能对上；
 * 同一份表换个顺序不会算错。列名找不到时返回可用的列名清单，让模型一次就能改对。
 *
 * <p>边界：
 * <ul>
 *   <li>文件必须已过沙箱校验，只读不写；</li>
 *   <li>行数 / 列数有硬顶（{@link #MAX_ROWS} / {@link #MAX_COLS}），超限明确拒绝；</li>
 *   <li>数值列允许千分位逗号与空白；百分号、货币符号以外的非数字按「空值」处理并计数，
 *       不静默当成 0（否则合计会悄悄偏小）；</li>
 *   <li>分组键缺失时归入「（空）」并在结果里点明，不丢行。</li>
 * </ul>
 *
 * <p>线程安全：无状态；{@link DataFormatter} 每次调用新建，POI 的 Workbook 不跨调用共享。
 */
@Component
public class TableSummaryTool {

    private static final Logger log = LoggerFactory.getLogger(TableSummaryTool.class);

    /** 参与汇总的最大数据行数（不含表头） */
    private static final int MAX_ROWS = 5_000;
    /** 参与汇总的最大列数 */
    private static final int MAX_COLS = 64;
    /** 结果里最多列出多少个分组 */
    private static final int MAX_GROUPS = 60;
    /** 空分组键的展示名 */
    private static final String EMPTY_KEY = "（空）";

    private final WorkspaceFileTool files;

    /**
     * @param files 会话工作区文件工具（只借用沙箱解析与后缀校验）
     */
    public TableSummaryTool(WorkspaceFileTool files) {
        this.files = files;
    }

    /**
     * 对一个数值列做合计，并按分组列给出分组小计。
     *
     * @param fileName    工作区内的 csv / xlsx / xls 相对文件名
     * @param valueColumn 要汇总的数值列名（表头原文，大小写与首尾空白不敏感）
     * @param groupColumn 分组列名；留空则只出总计
     * @param context     引擎注入的会话上下文
     * @return 中文汇总结果；失败时以「汇总失败：」开头
     */
    @Tool(
            name = "tableSummary",
            description = "读取会话工作区里的 CSV / XLSX / XLS，按指定数值列求合计，可按分组列给出分组小计。"
                    + "valueColumn 与 groupColumn 填表头名称（不是列序号）；groupColumn 可留空。"
                    + "只做确定性加总，禁止自己读表心算。第一行必须是表头。",
            readOnly = true)
    public String summary(
            @ToolParam(name = "fileName", description = "工作区内的表格文件名，如 本周数据.csv；支持 .csv / .xlsx / .xls") String fileName,
            @ToolParam(name = "valueColumn", description = "要汇总的数值列表头，如 金额") String valueColumn,
            @ToolParam(name = "groupColumn", description = "分组列表头，如 部门；留空则只输出总计", required = false)
            String groupColumn,
            RuntimeContext context
    ) {
        if (fileName == null || fileName.isBlank()) {
            return "汇总失败：请给出工作区内的表格文件名，如 本周数据.csv。";
        }
        if (valueColumn == null || valueColumn.isBlank()) {
            return "汇总失败：请给出要汇总的数值列名（表头原文），如 金额。";
        }
        String name = fileName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            return "汇总失败：只支持 .csv / .xlsx / .xls，当前给的是「" + name + "」。";
        }
        try {
            Path path = files.resolveForTool(context, name);
            // 后缀闸门同样走沙箱：即便名字伪装成 .csv，可执行体也不许读
            files.sandbox().rejectExecutable(path.getFileName().toString());
            if (!Files.isRegularFile(path)) {
                return "汇总失败：文件不存在 /workspace/" + name + "，请先用 workspaceListFiles 确认。";
            }
            Table table = lower.endsWith(".csv") ? readCsv(path) : readExcel(path);
            return summarize(name, table, valueColumn.trim(), groupColumn == null ? "" : groupColumn.trim());
        } catch (WorkspaceAccessException rejected) {
            return "汇总失败：" + rejected.getMessage();
        } catch (Exception ex) {
            // POI / IO 的原始异常含本机路径与包名，只进日志
            log.warn("[Agent][tableSummary] 读取失败 file={} err={}", name, ex.getMessage(), ex);
            return "汇总失败：表格无法解析，请确认是标准 CSV 或 Excel 文件，且第一行是表头。";
        }
    }

    /**
     * 按列名定位并统计。
     *
     * @param fileName    文件名（回显用）
     * @param table       已读入的表
     * @param valueColumn 数值列名
     * @param groupColumn 分组列名，可空
     * @return 中文汇总正文
     */
    private static String summarize(String fileName, Table table, String valueColumn, String groupColumn) {
        if (table.header.isEmpty()) {
            return "汇总失败：文件里没有表头行，无法按列名汇总。";
        }
        int valueIndex = table.indexOf(valueColumn);
        if (valueIndex < 0) {
            return "汇总失败：表里找不到列「" + valueColumn + "」。可用列名："
                    + String.join("、", table.header) + "。";
        }
        int groupIndex = -1;
        if (!groupColumn.isEmpty()) {
            groupIndex = table.indexOf(groupColumn);
            if (groupIndex < 0) {
                return "汇总失败：表里找不到分组列「" + groupColumn + "」。可用列名："
                        + String.join("、", table.header) + "。";
            }
        }
        double total = 0d;
        int counted = 0;
        int skipped = 0;
        // LinkedHashMap 保持首次出现顺序，分组结果读起来与表内顺序一致
        Map<String, double[]> groups = new LinkedHashMap<>();
        Map<String, Integer> groupCounts = new LinkedHashMap<>();
        for (List<String> row : table.rows) {
            Double value = WorkshopFiles.number(cell(row, valueIndex));
            if (value == null) {
                // 空值与「非数字」都不参与合计，但分别计数：非数字通常意味着表结构或口径有问题
                String raw = cell(row, valueIndex);
                if (raw != null && !raw.isBlank()) {
                    skipped++;
                }
                continue;
            }
            total += value;
            counted++;
            if (groupIndex >= 0) {
                String key = cell(row, groupIndex);
                String label = key == null || key.isBlank() ? EMPTY_KEY : key.trim();
                groups.computeIfAbsent(label, ignored -> new double[1])[0] += value;
                groupCounts.merge(label, 1, Integer::sum);
            }
        }
        StringBuilder body = new StringBuilder();
        body.append("表格汇总：").append(fileName).append('\n');
        body.append("- 数据行数：").append(table.rows.size()).append("（参与汇总 ").append(counted).append(" 行");
        if (skipped > 0) {
            body.append("；列「").append(valueColumn).append("」有 ").append(skipped).append(" 行不是数字，已跳过");
        }
        body.append("）\n");
        body.append("- 汇总列：").append(valueColumn);
        if (groupIndex >= 0) {
            body.append("；分组列：").append(groupColumn);
        }
        body.append('\n');
        body.append("- 合计：").append(WorkshopFiles.num(total)).append('\n');
        if (groupIndex >= 0) {
            if (groups.isEmpty()) {
                body.append("- 分组小计：没有可用于分组的行\n");
            } else {
                body.append("- 分组小计（共 ").append(groups.size()).append(" 组）：\n");
                int shown = 0;
                for (Map.Entry<String, double[]> entry : groups.entrySet()) {
                    if (shown >= MAX_GROUPS) {
                        body.append("  …（还有 ").append(groups.size() - shown).append(" 组未列出）\n");
                        break;
                    }
                    shown++;
                    body.append("  · ").append(entry.getKey()).append("：")
                            .append(WorkshopFiles.num(entry.getValue()[0]))
                            .append("（").append(groupCounts.getOrDefault(entry.getKey(), 0)).append(" 行）\n");
                }
            }
        }
        return body.toString().trim();
    }

    /**
     * 取一行里的某列，越界按空串处理（表格常见长短不一的行）。
     *
     * @param row  行
     * @param index 列下标
     * @return 单元格文本；越界为空串
     */
    private static String cell(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) {
            return "";
        }
        String value = row.get(index);
        return value == null ? "" : value;
    }

    /**
     * 读 CSV：第一行为表头，支持双引号包裹与转义引号，跳过全空行。
     *
     * @param path 已校验的文件路径
     * @return 表结构与数据行
     * @throws Exception 读文件失败
     */
    private static Table readCsv(Path path) throws Exception {
        String text = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
        // 去掉 UTF-8 BOM，否则第一列表头会带一个不可见字符导致列名匹配失败
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<List<String>> rows = parseCsv(text);
        return Table.of(rows);
    }

    /**
     * 最小 RFC4180 解析：逗号分隔、双引号包裹、"" 表示一个引号、允许引号内换行。
     *
     * @param text 全文
     * @return 解析后的行（每行是单元格列表）
     */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    // 连续两个引号是转义；单个引号结束引用段
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                addRow(rows, row);
                row = new ArrayList<>();
            } else if (ch != '\r') {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            addRow(rows, row);
        }
        return rows;
    }

    /**
     * 只收非全空的行，避免表格尾部的空行被当成数据。
     *
     * @param rows 收集器
     * @param row  一行
     */
    private static void addRow(List<List<String>> rows, List<String> row) {
        if (row.size() > MAX_COLS) {
            throw new IllegalArgumentException("列数超过 " + MAX_COLS);
        }
        boolean blank = row.stream().allMatch(item -> item == null || item.isBlank());
        if (!blank) {
            rows.add(row);
        }
    }

    /**
     * 读 Excel：只取第一个工作表，公式单元格按「格式化后的显示值」读取。
     *
     * @param path 已校验的文件路径
     * @return 表结构与数据行
     * @throws Exception POI 解析失败
     */
    private static Table readExcel(Path path) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        // DataFormatter 让数字按单元格格式呈现（如日期、两位小数），与人在 Excel 里看到的一致
        DataFormatter formatter = new DataFormatter();
        try (InputStream in = Files.newInputStream(path);
             Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) {
                return Table.of(rows);
            }
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            for (int i = 0; i <= lastRow && rows.size() <= MAX_ROWS; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                int lastCell = Math.min(row.getLastCellNum(), MAX_COLS);
                List<String> values = new ArrayList<>();
                for (int c = 0; c < lastCell; c++) {
                    Cell cell = row.getCell(c);
                    values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                addRow(rows, values);
            }
        }
        return Table.of(rows);
    }

    /**
     * 读入后的表：第一行表头 + 数据行。
     *
     * @param header 表头
     * @param rows   数据行
     */
    private record Table(List<String> header, List<List<String>> rows) {

        /**
         * 从行列表构造表；超过行数硬顶时拒绝。
         *
         * @param rows 原始行
         * @return 表
         */
        static Table of(List<List<String>> rows) {
            if (rows.isEmpty()) {
                return new Table(List.of(), List.of());
            }
            if (rows.size() - 1 > MAX_ROWS) {
                throw new IllegalArgumentException("数据行超过 " + MAX_ROWS);
            }
            List<String> header = rows.get(0).stream()
                    .map(item -> item == null ? "" : item.trim())
                    .toList();
            return new Table(header, rows.subList(1, rows.size()));
        }

        /**
         * 按表头名找列下标；大小写与首尾空白不敏感，取第一个命中。
         *
         * @param name 表头名
         * @return 列下标；找不到为 -1
         */
        int indexOf(String name) {
            if (name == null || name.isBlank()) {
                return -1;
            }
            String wanted = name.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < header.size(); i++) {
                String value = header.get(i) == null ? "" : header.get(i).trim();
                if (value.toLowerCase(Locale.ROOT).equals(wanted)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
