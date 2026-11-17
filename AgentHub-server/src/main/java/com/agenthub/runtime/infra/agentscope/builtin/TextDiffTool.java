package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本比对工具（{@code textDiffReport}）。所属域：runtime 的 infra。
 *
 * <p>职责：读两份会话工作区文件，按行做最长公共子序列比对，输出「新增 / 删除 / 修改」三类条目。
 * 只做确定性文本比较，不做语义改写，也不调用模型——这样「有没有差异」不依赖模型判断。
 *
 * <p>为什么按行而不是按字符：车间样例都是短文 / 清单 / 表格转写，按行比对的条目对人类可读，
 * 也便于后续把条目直接写进《文件车间结果.md》。按字符比对会把一处改动拆成十几条噪音。
 *
 * <p>边界：
 * <ul>
 *   <li>两份文件都必须已通过沙箱校验（经由 {@link WorkspaceFileTool#readWholeText}）；</li>
 *   <li>行数超过 {@link #MAX_DIFF_LINES} 时拒绝比对，避免 O(n²) 的 DP 把线程拖死；</li>
 *   <li>返回条目数受 {@code maxEntries} 限制，超出时明确写出「还有 N 条未列出」。</li>
 * </ul>
 *
 * <p>线程安全：无状态（仅持有只读依赖），可并发调用。
 */
@Component
public class TextDiffTool {

    private static final Logger log = LoggerFactory.getLogger(TextDiffTool.class);

    /** 参与 DP 的单侧行数硬顶：400×400 的整型矩阵足够小，再大就请用户先拆文件 */
    private static final int MAX_DIFF_LINES = 400;
    /** 默认返回条数 */
    private static final int DEFAULT_MAX_ENTRIES = 50;
    /** 条目返回条数硬顶 */
    private static final int MAX_ENTRIES_CAP = 200;
    /** 单条差异正文的展示字符上限 */
    private static final int BRIEF_CHARS = 120;

    private final WorkspaceFileTool files;

    /**
     * @param files 会话工作区文件工具，用来复用同一套沙箱与整文件读取闸门
     */
    public TextDiffTool(WorkspaceFileTool files) {
        this.files = files;
    }

    /**
     * 比对两份文件的文字差异。
     *
     * @param fileA      旧稿相对文件名，如 {@code 原稿A.md}
     * @param fileB      新稿相对文件名，如 {@code 原稿B.md}
     * @param maxEntries 最多返回多少条差异，缺省 50、上限 200
     * @param context    引擎注入的会话上下文（决定读哪个会话目录，不进 schema）
     * @return 中文差异清单；失败时以「比对失败：」开头，不抛异常给引擎
     */
    @Tool(
            name = "textDiffReport",
            description = "比对两份文本文件的逐行差异，输出「新增 / 删除 / 修改」条目及行号。"
                    + "适用于原稿与改稿、清单前后版本、模板与成稿的对照。"
                    + "两个文件都必须在当前会话工作区内（相对路径，如 原稿A.md）。"
                    + "只做确定性逐行比较，不做语义判断；行数过多会被拒绝，请先拆分文件。",
            readOnly = true)
    public String diff(
            @ToolParam(name = "fileA", description = "旧稿相对文件名，如 原稿A.md；不要以 / 开头") String fileA,
            @ToolParam(name = "fileB", description = "新稿相对文件名，如 原稿B.md；不要以 / 开头") String fileB,
            @ToolParam(name = "maxEntries", description = "最多返回多少条差异，缺省 50，上限 200", required = false)
            @BuiltinParam(minimum = 1, maximum = MAX_ENTRIES_CAP, defaultValue = "" + DEFAULT_MAX_ENTRIES)
            Integer maxEntries,
            RuntimeContext context
    ) {
        if (fileA == null || fileA.isBlank() || fileB == null || fileB.isBlank()) {
            return "比对失败：请同时给出两份文件的相对路径（fileA 为旧稿、fileB 为新稿）。";
        }
        try {
            String left = files.readWholeText(context, fileA.trim());
            String right = files.readWholeText(context, fileB.trim());
            String[] oldLines = WorkshopFiles.lines(left);
            String[] newLines = WorkshopFiles.lines(right);
            if (oldLines.length > MAX_DIFF_LINES || newLines.length > MAX_DIFF_LINES) {
                return "比对失败：单份文件超过 " + MAX_DIFF_LINES + " 行（当前 "
                        + oldLines.length + " / " + newLines.length
                        + " 行），为避免比对过程过慢，请先按章节拆分成更小的文件。";
            }
            List<Entry> entries = diffOf(oldLines, newLines);
            int limit = maxEntries == null ? DEFAULT_MAX_ENTRIES : Math.max(1, Math.min(maxEntries, MAX_ENTRIES_CAP));
            return render(fileA.trim(), fileB.trim(), oldLines.length, newLines.length, entries, limit);
        } catch (WorkspaceAccessException rejected) {
            return "比对失败：" + rejected.getMessage();
        } catch (RuntimeException ex) {
            // 比对是纯内存计算，走到这里多半是脏输入（极长单行等）；原始异常只进日志
            log.warn("[Agent][textDiffReport] 比对异常 a={} b={} err={}", fileA, fileB, ex.getMessage(), ex);
            return "比对失败：文件内容无法解析为文本，请确认是 UTF-8 文本文件。";
        }
    }

    /**
     * 逐行 LCS 比对，并把紧邻的「删除 + 新增」合并成一条「修改」。
     *
     * @param oldLines 旧稿行
     * @param newLines 新稿行
     * @return 按出现顺序排列的差异条目
     */
    private static List<Entry> diffOf(String[] oldLines, String[] newLines) {
        int n = oldLines.length;
        int m = newLines.length;
        // dp[i][j] = oldLines[i..] 与 newLines[j..] 的最长公共子序列长度
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = oldLines[i].equals(newLines[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<Entry> entries = new ArrayList<>();
        // pendingDelete / pendingInsert 暂存紧邻的删除与新增，用来合成「修改」
        List<Step> pendingDelete = new ArrayList<>();
        List<Step> pendingInsert = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (oldLines[i].equals(newLines[j])) {
                flush(entries, pendingDelete, pendingInsert);
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                pendingDelete.add(new Step(i + 1, oldLines[i]));
                i++;
            } else {
                pendingInsert.add(new Step(j + 1, newLines[j]));
                j++;
            }
        }
        while (i < n) {
            pendingDelete.add(new Step(i + 1, oldLines[i]));
            i++;
        }
        while (j < m) {
            pendingInsert.add(new Step(j + 1, newLines[j]));
            j++;
        }
        flush(entries, pendingDelete, pendingInsert);
        return entries;
    }

    /**
     * 把暂存的删除与新增落成条目：成对的部分记为「修改」，多出来的分别记为「删除 / 新增」。
     *
     * @param entries 结果收集器
     * @param deletes 暂存删除行
     * @param inserts 暂存新增行
     */
    private static void flush(List<Entry> entries, List<Step> deletes, List<Step> inserts) {
        int pairs = Math.min(deletes.size(), inserts.size());
        for (int k = 0; k < pairs; k++) {
            Step deleted = deletes.get(k);
            Step inserted = inserts.get(k);
            entries.add(new Entry(Kind.MODIFY, deleted.lineNo, inserted.lineNo, deleted.text, inserted.text));
        }
        for (int k = pairs; k < deletes.size(); k++) {
            Step deleted = deletes.get(k);
            entries.add(new Entry(Kind.DELETE, deleted.lineNo, 0, deleted.text, null));
        }
        for (int k = pairs; k < inserts.size(); k++) {
            Step inserted = inserts.get(k);
            entries.add(new Entry(Kind.INSERT, 0, inserted.lineNo, null, inserted.text));
        }
        deletes.clear();
        inserts.clear();
    }

    /**
     * 渲染成模型与人都能直接引用的中文清单。
     *
     * @param nameA    旧稿文件名
     * @param nameB    新稿文件名
     * @param linesA   旧稿行数
     * @param linesB   新稿行数
     * @param entries  全部差异
     * @param limit    本次最多列出多少条
     * @return 差异清单正文
     */
    private static String render(String nameA, String nameB, int linesA, int linesB,
                                 List<Entry> entries, int limit) {
        StringBuilder body = new StringBuilder();
        body.append("文本比对：").append(nameA).append("（旧稿 ").append(linesA).append(" 行） vs ")
                .append(nameB).append("（新稿 ").append(linesB).append(" 行）\n");
        if (entries.isEmpty()) {
            return body.append("结论：两份文件逐行完全一致，没有差异条目。").toString();
        }
        int modified = count(entries, Kind.MODIFY);
        int inserted = count(entries, Kind.INSERT);
        int deleted = count(entries, Kind.DELETE);
        body.append("差异共 ").append(entries.size()).append(" 处：修改 ").append(modified)
                .append("、新增 ").append(inserted).append("、删除 ").append(deleted).append("\n\n");
        int shown = 0;
        for (Entry entry : entries) {
            if (shown >= limit) {
                break;
            }
            shown++;
            body.append(shown).append(". ").append(entry.describe()).append('\n');
        }
        if (entries.size() > shown) {
            body.append("…（还有 ").append(entries.size() - shown)
                    .append(" 条差异未列出，可提高 maxEntries 或先缩小文件范围）");
        }
        return body.toString().trim();
    }

    private static int count(List<Entry> entries, Kind kind) {
        return (int) entries.stream().filter(item -> item.kind() == kind).count();
    }

    /** 差异类型 */
    private enum Kind {
        /** 同一位置内容被改写 */
        MODIFY,
        /** 新稿多出来的行 */
        INSERT,
        /** 旧稿有、新稿没有的行 */
        DELETE
    }

    /**
     * 一条差异。
     *
     * @param kind     类型
     * @param oldLine  旧稿行号（从 1 开始；新增时为 0）
     * @param newLine  新稿行号（从 1 开始；删除时为 0）
     * @param oldText  旧稿原文，可空
     * @param newText  新稿原文，可空
     */
    private record Entry(Kind kind, int oldLine, int newLine, String oldText, String newText) {

        /**
         * @return 供人阅读的一行（修改带新旧两行）
         */
        String describe() {
            return switch (kind) {
                case MODIFY -> "修改 第 " + oldLine + " 行 → 第 " + newLine + " 行\n   - 旧："
                        + WorkshopFiles.brief(oldText(), BRIEF_CHARS) + "\n   + 新："
                        + WorkshopFiles.brief(newText(), BRIEF_CHARS);
                case INSERT -> "新增 新稿第 " + newLine + " 行："
                        + WorkshopFiles.brief(newText(), BRIEF_CHARS);
                case DELETE -> "删除 旧稿第 " + oldLine + " 行："
                        + WorkshopFiles.brief(oldText(), BRIEF_CHARS);
            };
        }
    }

    /**
     * 比对过程中暂存的一行。
     *
     * @param lineNo 行号（从 1 开始）
     * @param text   原文
     */
    private record Step(int lineNo, String text) {
    }
}
