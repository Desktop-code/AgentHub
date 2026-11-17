package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 名单去重工具（{@code listDedup}）。所属域：runtime 的 infra。
 *
 * <p>职责：读一份名单文件（每行一个名称，或第一列是名称的 CSV），输出去重后的名单与重复项。
 * 只做确定性比对，不猜「相似即同一人」。
 *
 * <p>归一规则（写死，避免不同车间得出不同结论）：
 * <ul>
 *   <li>去掉首尾空白、全角空格与常见包裹符号（引号、书名号）；</li>
 *   <li>连续空白压成一个空格；</li>
 *   <li>大小写不敏感（英文名 / 工号更稳）；</li>
 *   <li><b>不</b>做拼音、去姓氏、同音字等模糊匹配——那会误删不同的人。</li>
 * </ul>
 *
 * <p>带 CSV 时只取第一列作为名单主体，其它列一并保留在「保留行」里，
 * 这样下游写文件时仍是完整的一行，不会丢掉部门之类的信息。
 *
 * <p>线程安全：无状态，可并发调用。
 */
@Component
public class ListDedupTool {

    private static final Logger log = LoggerFactory.getLogger(ListDedupTool.class);

    /** 参与去重的最大行数：再大就应该走数据库而不是聊天工具 */
    private static final int MAX_LINES = 20_000;
    /** 重复项最多列出多少条 */
    private static final int MAX_DUP_ENTRIES = 200;

    private final WorkspaceFileTool files;

    /**
     * @param files 会话工作区文件工具
     */
    public ListDedupTool(WorkspaceFileTool files) {
        this.files = files;
    }

    /**
     * 对名单文件去重。
     *
     * @param fileName 工作区内的名单文件相对路径，如 名单.txt；也支持 .csv
     * @param context  引擎注入的会话上下文
     * @return 中文去重结果；失败时以「去重失败：」开头
     */
    @Tool(
            name = "listDedup",
            description = "读取会话工作区里的名单文件（每行一个名称，或 CSV 第一列是名称），"
                    + "输出去重后的名单与重复项。归一规则：去首尾空白与包裹符号、空白压缩、大小写不敏感；"
                    + "不做拼音或相似度匹配。只做确定性比对，禁止自己肉眼去重。",
            readOnly = true)
    public String dedup(
            @ToolParam(name = "fileName", description = "工作区内的名单文件相对路径，如 名单.txt；也支持 .csv") String fileName,
            RuntimeContext context
    ) {
        if (fileName == null || fileName.isBlank()) {
            return "去重失败：请给出名单文件的相对路径，如 名单.txt。";
        }
        String name = fileName.trim();
        try {
            String text = files.readWholeText(context, name);
            String[] lines = WorkshopFiles.lines(text);
            if (lines.length > MAX_LINES) {
                return "去重失败：名单超过 " + MAX_LINES + " 行（当前 " + lines.length
                        + " 行），请先拆分文件或改用数据库去重。";
            }
            return render(name, lines);
        } catch (WorkspaceAccessException rejected) {
            return "去重失败：" + rejected.getMessage();
        } catch (RuntimeException ex) {
            log.warn("[Agent][listDedup] 处理异常 file={} err={}", name, ex.getMessage(), ex);
            return "去重失败：文件内容无法按文本解析，请确认是 UTF-8 文本文件。";
        }
    }

    /**
     * 逐行归一后去重，并统计每个原值的重复次数。
     *
     * @param fileName 文件名（回显用）
     * @param lines    原始行（不含表头判断）
     * @return 中文结果正文
     */
    private static String render(String fileName, String[] lines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        // 归一键 → 首次出现的原值（回显用原名，不改写用户的数据）
        Map<String, String> display = new LinkedHashMap<>();
        int blankLines = 0;
        // 是否 CSV：首行含逗号即按列处理，只拿第一列当名单主体
        boolean csv = lines.length > 0 && lines[0].indexOf(',') >= 0;
        List<String> keptRows = new ArrayList<>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            if (line.isBlank()) {
                blankLines++;
                continue;
            }
            String subject = csv ? firstColumn(line) : line;
            String normalized = normalize(subject);
            if (normalized.isEmpty()) {
                blankLines++;
                continue;
            }
            counts.merge(normalized, 1, Integer::sum);
            display.putIfAbsent(normalized, subject.trim());
            if (counts.get(normalized) == 1) {
                // 首次出现才进保留名单：原样保留整行（CSV 时含其它列），下游写文件不丢信息
                keptRows.add(line.trim());
            }
        }
        int duplicates = counts.values().stream().mapToInt(value -> value - 1).sum();
        StringBuilder body = new StringBuilder();
        body.append("名单去重：").append(fileName).append('\n');
        body.append("- 原始行数：").append(lines.length);
        if (blankLines > 0) {
            body.append("（其中空行 ").append(blankLines).append(" 行已忽略）");
        }
        body.append('\n');
        body.append("- 去重后：").append(keptRows.size()).append(" 条；重复 ").append(duplicates)
                .append(" 条（涉及 ").append((int) counts.values().stream().filter(v -> v > 1).count())
                .append(" 个名称）\n\n");
        body.append("【去重后名单】\n");
        for (String row : keptRows) {
            body.append("- ").append(row).append('\n');
        }
        if (duplicates > 0) {
            body.append("\n【重复项】\n");
            int shown = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() <= 1) {
                    continue;
                }
                if (shown >= MAX_DUP_ENTRIES) {
                    body.append("…（还有重复项未列出）\n");
                    break;
                }
                shown++;
                body.append("- ").append(display.get(entry.getKey()))
                        .append("：出现 ").append(entry.getValue()).append(" 次\n");
            }
        } else {
            body.append("\n【重复项】\n- 无重复\n");
        }
        return body.toString().trim();
    }

    /**
     * 取 CSV 行的第一列（支持双引号包裹）。
     *
     * @param line 一行原文
     * @return 第一列文本
     */
    private static String firstColumn(String line) {
        String text = line.trim();
        if (text.startsWith("\"")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        out.append('"');
                        i++;
                    } else {
                        break;
                    }
                } else {
                    out.append(ch);
                }
            }
            return out.toString();
        }
        int comma = text.indexOf(',');
        return comma < 0 ? text : text.substring(0, comma);
    }

    /**
     * 归一：去包裹符号与空白、压空白、转小写。
     *
     * @param raw 原始值
     * @return 归一键；全空白时为空串
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace('\u3000', ' ').trim();
        // 只剥一层成对的包裹符号；列表里的「"张三"」「《张三》」都算同一个名字
        while (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            boolean paired = (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '“' && last == '”')
                    || (first == '‘' && last == '’')
                    || (first == '《' && last == '》')
                    || (first == '「' && last == '」');
            if (!paired) {
                break;
            }
            text = text.substring(1, text.length() - 1).trim();
        }
        text = text.replaceAll("\\s+", " ");
        return text.toLowerCase(Locale.ROOT);
    }
}
