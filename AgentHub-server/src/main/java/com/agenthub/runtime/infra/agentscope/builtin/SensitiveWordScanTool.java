package com.agenthub.runtime.infra.agentscope.builtin;

import com.agenthub.runtime.domain.service.WorkspaceAccessException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 敏感词扫描工具（{@code sensitiveWordScan}）。所属域：runtime 的 infra。
 *
 * <p>职责：读一份待检文件 + 一份词表文件（每行一个词，或逗号分隔），输出命中位置（行号、列号、上下文）。
 * 只做<b>确定性字面匹配</b>（大小写不敏感），不做同义词、拼音、正则推断——合规底稿需要可复核的命中，
 * 而不是模型的「感觉像」。
 *
 * <p>为什么位置要精确到列：底稿专家需要写「第 3 行第 12 列命中『逾期』」，
 * 只给行号会让复核人多查一整个段落。
 *
 * <p>边界：
 * <ul>
 *   <li>词表为空或全是空行时明确报错，不返回「0 命中」的假成功；</li>
 *   <li>单个文件最多 {@link #MAX_SOURCE_CHARS} 字符、词表最多 {@link #MAX_WORDS} 个词；</li>
 *   <li>命中条数受 {@code maxHits} 限制，超出时点明还有多少未列出。</li>
 * </ul>
 *
 * <p>线程安全：无状态，可并发调用。
 */
@Component
public class SensitiveWordScanTool {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordScanTool.class);

    /** 待检正文最大字符数 */
    private static final int MAX_SOURCE_CHARS = 200_000;
    /** 词表最大词数 */
    private static final int MAX_WORDS = 2_000;
    /** 默认返回命中条数 */
    private static final int DEFAULT_MAX_HITS = 100;
    /** 命中条数硬顶 */
    private static final int MAX_HITS_CAP = 500;
    /** 命中行上下文左右各取多少字符 */
    private static final int CONTEXT_RADIUS = 24;

    private final WorkspaceFileTool files;

    /**
     * @param files 会话工作区文件工具
     */
    public SensitiveWordScanTool(WorkspaceFileTool files) {
        this.files = files;
    }

    /**
     * 扫描一份文件里是否命中词表中的词。
     *
     * @param fileName 待检文件相对路径，如 通知草稿.md
     * @param wordFile 词表文件相对路径，如 敏感词.txt（每行一个词，或逗号分隔）
     * @param maxHits  最多返回多少条命中，缺省 100、上限 500
     * @param context  引擎注入的会话上下文
     * @return 中文命中清单；失败时以「扫描失败：」开头
     */
    @Tool(
            name = "sensitiveWordScan",
            description = "用词表扫描文件里的敏感词，输出每个命中的行号、列号与上下文。"
                    + "wordFile 是工作区内的词表文件（每行一个词，或逗号分隔）；fileName 是待检文件。"
                    + "只做大小写不敏感的字面匹配，不做同义词或正则推断。词表为空会直接报错。",
            readOnly = true)
    public String scan(
            @ToolParam(name = "fileName", description = "待检文件相对路径，如 通知草稿.md") String fileName,
            @ToolParam(name = "wordFile", description = "词表文件相对路径，如 敏感词.txt") String wordFile,
            @ToolParam(name = "maxHits", description = "最多返回多少条命中，缺省 100，上限 500", required = false)
            @BuiltinParam(minimum = 1, maximum = MAX_HITS_CAP, defaultValue = "" + DEFAULT_MAX_HITS)
            Integer maxHits,
            RuntimeContext context
    ) {
        if (fileName == null || fileName.isBlank()) {
            return "扫描失败：请给出待检文件的相对路径。";
        }
        if (wordFile == null || wordFile.isBlank()) {
            return "扫描失败：请给出词表文件的相对路径，如 敏感词.txt。";
        }
        try {
            String source = files.readWholeText(context, fileName.trim());
            String wordText = files.readWholeText(context, wordFile.trim());
            List<String> words = parseWords(wordText);
            if (words.isEmpty()) {
                return "扫描失败：词表 /workspace/" + wordFile.trim()
                        + " 里没有任何有效词条（每行一个词，或逗号分隔）；请先补词表再扫描。";
            }
            if (words.size() > MAX_WORDS) {
                return "扫描失败：词表超过 " + MAX_WORDS + " 个词（当前 " + words.size() + " 个），请先精简。";
            }
            if (source.length() > MAX_SOURCE_CHARS) {
                return "扫描失败：待检文件超过 " + MAX_SOURCE_CHARS + " 字符，请先拆分。";
            }
            int limit = maxHits == null ? DEFAULT_MAX_HITS : Math.max(1, Math.min(maxHits, MAX_HITS_CAP));
            return render(fileName.trim(), wordFile.trim(), words, source, limit);
        } catch (WorkspaceAccessException rejected) {
            return "扫描失败：" + rejected.getMessage();
        } catch (RuntimeException ex) {
            log.warn("[Agent][sensitiveWordScan] 扫描异常 file={} wordFile={} err={}",
                    fileName, wordFile, ex.getMessage(), ex);
            return "扫描失败：文件内容无法按文本解析，请确认是 UTF-8 文本文件。";
        }
    }

    /**
     * 逐词逐行查找，输出行号 / 列号 / 上下文。
     *
     * @param fileName 待检文件名
     * @param wordFile 词表文件名
     * @param words    词表
     * @param source   待检正文
     * @param limit    最多列出的命中条数
     * @return 中文结果正文
     */
    private static String render(String fileName, String wordFile, List<String> words, String source, int limit) {
        String[] lines = WorkshopFiles.lines(source);
        // 逐行匹配：行内可以出现多个不同词，命中的行号天然正确（引号内换行的 CSV 不适用于扫描场景）
        List<Hit> hits = new ArrayList<>();
        int totalHits = 0;
        Set<String> hitWords = new LinkedHashSet<>();
        for (int lineNo = 0; lineNo < lines.length; lineNo++) {
            String line = lines[lineNo];
            if (line.isEmpty()) {
                continue;
            }
            String lineLower = line.toLowerCase(Locale.ROOT);
            for (String word : words) {
                int from = 0;
                while (true) {
                    int at = lineLower.indexOf(word, from);
                    if (at < 0) {
                        break;
                    }
                    totalHits++;
                    hitWords.add(word);
                    if (hits.size() < limit) {
                        hits.add(new Hit(lineNo + 1, at + 1, word, contextOf(line, at, word.length())));
                    }
                    // 从命中位置之后继续，避免同一个词在同行重复出现时死循环
                    from = at + Math.max(1, word.length());
                }
            }
        }
        StringBuilder body = new StringBuilder();
        body.append("敏感词扫描：").append(fileName).append("（词表 ").append(wordFile)
                .append("，共 ").append(words.size()).append(" 个词）\n");
        if (totalHits == 0) {
            // 明确写出「查了哪些词」，避免被误读成「没有词表」或「没扫」
            return body.append("结论：未命中任何敏感词。\n- 已检查词：")
                    .append(String.join("、", words)).append('\n')
                    .append("- 文件共 ").append(lines.length).append(" 行").toString();
        }
        body.append("命中 ").append(totalHits).append(" 处，涉及 ").append(hitWords.size()).append(" 个词：")
                .append(String.join("、", hitWords)).append("\n\n");
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            body.append(i + 1).append(". 第 ").append(hit.line).append(" 行第 ").append(hit.column)
                    .append(" 列 命中「").append(hit.word).append("」：").append(hit.context).append('\n');
        }
        if (totalHits > hits.size()) {
            body.append("…（还有 ").append(totalHits - hits.size())
                    .append(" 处未列出，可提高 maxHits 或先缩小待检文件）");
        }
        return body.toString().trim();
    }

    /**
     * 取命中位置的上下文片段，左边界补「…」表示前面还有内容。
     *
     * @param line   命中所在行
     * @param at     命中起点（0 基）
     * @param length 命中长度
     * @return 带省略号的短句
     */
    private static String contextOf(String line, int at, int length) {
        int start = Math.max(0, at - CONTEXT_RADIUS);
        int end = Math.min(line.length(), at + length + CONTEXT_RADIUS);
        String snippet = line.substring(start, end).trim();
        if (snippet.isEmpty()) {
            return "（空行）";
        }
        return (start > 0 ? "…" : "") + snippet + (end < line.length() ? "…" : "");
    }

    /**
     * 解析词表：按换行与中英文逗号 / 顿号 / 分号切分，去重并丢弃空项。
     *
     * @param text 词表原文
     * @return 有序词表
     */
    private static List<String> parseWords(String text) {
        List<String> words = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return words;
        }
        for (String piece : text.split("[\\r\\n,，、;；]+")) {
            String word = piece.trim();
            if (word.isEmpty()) {
                continue;
            }
            // 词表里可能出现「# 注释」行；不把注释当词，避免把整句注释当敏感词
            if (word.startsWith("#")) {
                continue;
            }
            if (seen.add(word)) {
                words.add(word);
            }
        }
        return words;
    }

    /**
     * 一条命中。
     *
     * @param line    行号（从 1 开始）
     * @param column  列号（从 1 开始，按字符计）
     * @param word    命中的词
     * @param context 上下文片段
     */
    private record Hit(int line, int column, String word, String context) {
    }
}
