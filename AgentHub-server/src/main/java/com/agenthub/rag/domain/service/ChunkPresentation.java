package com.agenthub.rag.domain.service;

import com.agenthub.rag.domain.service.ir.ChunkStrategyKind;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 切片展示元数据与原文偏移的组装规则。所属域：rag。
 * <p>
 * 只做「从 IR 元数据读出可展示字段」和「把切片正文映射回解析文本偏移」，
 * 不参与切分判定，也不改切片正文；入库落库、重切预览、接口响应共用同一套口径。
 * <p>
 * 旧切片字段为空时一律返回 null / 不写偏移，禁止编造策略或章节路径。
 */
public final class ChunkPresentation {

    /** 连续空白（含换行）；切片正文与解析文本常只差段落空行，定位前先折叠 */
    private static final Pattern BLANK_RUN = Pattern.compile("\\s+");
    /** 行首 Markdown 记号：标题井号、无序列表符号、有序列表序号 */
    private static final Pattern LINE_MARKER =
            Pattern.compile("(?m)^[ \\t]*(?:#{1,6}[ \\t]+|[-*+][ \\t]+|\\d+[.、)][ \\t]+)");
    /** Markdown 标题行前缀（# ~ ######） */
    private static final Pattern MARKDOWN_HASH = Pattern.compile("#{1,6}");
    /** 超长再切给标题追加的 (i/n) 后缀，原文里没有 */
    private static final Pattern PART_SUFFIX = Pattern.compile("\\s*\\(\\d+/\\d+\\)\\s*$");

    private ChunkPresentation() {
    }

    /**
     * 落库用的策略编码；旧数据没有 strategy 时从 source_type 兜底（两者同名）。
     *
     * @param chunk 切片
     * @return HIERARCHICAL / ENTRY_BASED / TABULAR / NARRATIVE；无法判定时为 null
     */
    public static String strategyCode(DocumentChunk chunk) {
        if (chunk == null) {
            return null;
        }
        String code = trimToNull(chunk.metaString("strategy"));
        if (code == null) {
            code = trimToNull(chunk.metaString("source_type"));
        }
        if (code == null) {
            return null;
        }
        String upper = code.toUpperCase(java.util.Locale.ROOT);
        for (ChunkStrategyKind kind : ChunkStrategyKind.values()) {
            if (kind.name().equals(upper)) {
                return upper;
            }
        }
        return null;
    }

    /**
     * 展示用章节路径。
     * <p>
     * 优先级：条目（含字段）→ 表头 → 文章/节标题；仅当专篇层级确实高于当前节时才拼「专篇 / 节」，
     * 避免把平级标题误判为父子关系。
     *
     * @param chunk 切片
     * @return 章节路径；确实没有时为 null（旧数据不补）
     */
    public static String sectionPath(DocumentChunk chunk) {
        if (chunk == null) {
            return null;
        }
        String entry = trimToNull(chunk.metaString("entry_name"));
        String field = trimToNull(chunk.metaString("field_type"));
        if (entry != null) {
            // 字段子片的正文本身就是「条目 + 字段」，路径拼到字段这一层更利于定位
            return field != null && !field.equals(entry) ? entry + " / " + field : entry;
        }
        String tableHeader = trimToNull(chunk.metaString("table_header"));
        if (tableHeader != null) {
            return tableHeader;
        }
        String headerPath = trimToNull(chunk.metaString("header_path"));
        String title = trimToNull(chunk.getSectionTitle());
        String base = headerPath != null ? headerPath : title;
        if (base == null) {
            return null;
        }
        String article = trimToNull(chunk.metaString("article_title"));
        int articleLevel = metaInt(chunk, "article_level", -1);
        int headerLevel = metaInt(chunk, "header_level", -1);
        if (article != null && !article.equals(base) && !base.startsWith(article)
                && articleLevel > 0 && headerLevel > 0 && articleLevel < headerLevel) {
            return article + " / " + base;
        }
        return base;
    }

    /**
     * 入库时写入 rag_chunk.content 的正文：保留 IR 正文，有章节名时前置一行标题。
     * <p>
     * 与历史口径一致：正文已以标题开头则不重复前置，避免检索命中后看到重复标题。
     *
     * @param chunk 切片
     * @return 完整正文
     */
    public static String storedContent(DocumentChunk chunk) {
        if (chunk == null) {
            return "";
        }
        String body = chunk.getContent() == null ? "" : chunk.getContent().trim();
        String title = chunk.getSectionTitle() == null ? "" : chunk.getSectionTitle().trim();
        if (title.isEmpty() || body.startsWith(title)) {
            return body;
        }
        return title + "\n\n" + body;
    }

    /**
     * 把切片整片正文映射回解析文本的字符区间，写入 char_start / char_end 元数据。
     * <p>
     * 口径（文档页左右对照按这个口径画高亮）：
     * <ol>
     *   <li>按<b>整片正文</b>在解析文本中定位；解析文本段落间有空行、切片正文是同段单换行拼接，
     *       所以比对前先折叠连续空白，避免整片匹配失败；</li>
     *   <li>该片以自己的章节标题开头时，把标题行一并纳入范围（标题 + 该片正文），但不越到下一节；</li>
     *   <li>表格片首行「表头：…」、条目片前两行「条目名 / 字段名」是策略合成前缀，原文没有，定位时跳过；</li>
     *   <li>正文定位不到就不写偏移——前端退化为不高亮，禁止只标一行标题冒充整片范围。</li>
     * </ol>
     *
     * @param chunks 已清洗的切片（按入库顺序）
     * @param text   本轮解析文本（换行已统一为 \n）
     */
    public static void assignOffsets(List<DocumentChunk> chunks, String text) {
        if (chunks == null || chunks.isEmpty() || !StringUtils.hasText(text)) {
            return;
        }
        // 折叠空白的归一化视图：同时保留回原文本的下标，命中后能还原精确的起止字符
        OffsetIndex index = OffsetIndex.of(text);
        int cursor = 0;
        for (DocumentChunk chunk : chunks) {
            String body = chunk.getContent() == null ? "" : chunk.getContent().trim();
            if (body.isEmpty()) {
                continue;
            }
            int[] hit = index.find(chunk, body, cursor);
            if (hit == null) {
                // 定位不到：不写偏移，由前端按正文再试；禁止退回标题长度当整片范围
                continue;
            }
            int start = index.originalStart(hit[0]);
            int end = index.originalEnd(hit[1]);
            // 标题 + 正文：范围从该节标题行开始，避免正文首行单独被点亮
            start = extendToHeading(text, start, chunk);
            chunk.withMeta("char_start", start);
            chunk.withMeta("char_end", end);
            cursor = hit[1];
        }
    }

    /**
     * 可用来在原文定位的正文候选：整片优先，其次去掉策略合成的非原文前缀。
     *
     * @param chunk 切片
     * @param body  切片正文
     * @return 候选正文，按优先级排列
     */
    private static List<String> matchCandidates(DocumentChunk chunk, String body) {
        List<String> candidates = new ArrayList<>(2);
        candidates.add(body);
        String[] lines = body.split("\\R", -1);
        int drop = 0;
        String entry = trimToNull(chunk.metaString("entry_name"));
        String field = trimToNull(chunk.metaString("field_type"));
        if (body.startsWith("表头：") && lines.length > 1) {
            // 表格片：首行是「表头：…」，原文只有后面的数据行
            drop = 1;
        } else if (entry != null && lines.length > 2
                && lines[0].trim().equals(entry)
                && (field == null || lines[1].trim().equals(field))) {
            // 条目片：正文由「条目名 + 字段名 + 内容」拼成，原文没有前两行
            drop = field == null ? 1 : 2;
        } else if (lines.length > 1 && trimToNull(chunk.getSectionTitle()) != null
                && stripPartSuffix(lines[0]).equals(chunk.getSectionTitle().trim())) {
            // 防御：正文被前置了章节标题时先去掉，避免把标题当成正文定位
            drop = 1;
        }
        if (drop > 0 && drop < lines.length) {
            String rest = String.join("\n", Arrays.copyOfRange(lines, drop, lines.length)).trim();
            // 过短残片容易被定位到无关位置，宁可不标偏移
            if (rest.length() >= 4) {
                candidates.add(rest);
            }
        }
        return candidates;
    }

    /**
     * 把正文起点前移到该节标题行行首，让高亮覆盖「标题 + 正文」。
     * <p>
     * 只在「标题独占一行、且标题行与正文之间只有空行」时前移；否则保持正文起点，
     * 避免把上一节的标题或正文里的同名串带进选区。
     *
     * @param text      解析文本
     * @param bodyStart 正文起点（原文本下标）
     * @param chunk     切片，用于取章节标题
     * @return 前移后的起点；不该前移时原样返回
     */
    private static int extendToHeading(String text, int bodyStart, DocumentChunk chunk) {
        String heading = headingLineText(chunk);
        if (heading == null) {
            return bodyStart;
        }
        // 正文本身从标题文字开始（正文被前置标题）时从当前起点算，否则找紧邻正文的上一处标题
        boolean startsWithHeading = text.startsWith(heading, bodyStart);
        int at = startsWithHeading
                ? bodyStart
                : text.lastIndexOf(heading, Math.max(0, bodyStart - 1));
        if (at < 0) {
            return bodyStart;
        }
        int lineStart = text.lastIndexOf('\n', at - 1) + 1;
        int lineEnd = text.indexOf('\n', at + heading.length());
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        // 标题必须独占该行：标题之后到换行之间只剩空白
        if (!text.substring(at + heading.length(), lineEnd).isBlank()) {
            return bodyStart;
        }
        // 标题在正文之前时，两者之间只允许空行与该片首行的列表记号；
        // 正文从标题本身开始时无需此校验（标题行之后就是该片正文）
        if (!startsWithHeading && (lineEnd >= bodyStart
                || !LINE_MARKER.matcher(text.substring(lineEnd, bodyStart)).replaceAll("").isBlank())) {
            return bodyStart;
        }
        // 行首只允许 Markdown 井号，否则命中可能是正文里的同名串
        String prefix = text.substring(lineStart, at).trim();
        if (!prefix.isEmpty() && !MARKDOWN_HASH.matcher(prefix).matches()) {
            return bodyStart;
        }
        return lineStart;
    }

    /**
     * 章节标题的可定位文本：sectionTitle 优先，缺省取 header_path 最后一段。
     * 超长再切追加的 (i/n) 后缀在原文里不存在，去掉后再找。
     *
     * @param chunk 切片
     * @return 标题文本；没有标题为 null
     */
    private static String headingLineText(DocumentChunk chunk) {
        String title = trimToNull(chunk.getSectionTitle());
        if (title == null) {
            String path = trimToNull(chunk.metaString("header_path"));
            if (path != null) {
                title = path.contains(" / ") ? path.substring(path.lastIndexOf(" / ") + 3).trim() : path;
            }
        }
        if (title == null) {
            return null;
        }
        String bare = PART_SUFFIX.matcher(title).replaceAll("").trim();
        return bare.isEmpty() ? null : bare;
    }

    /**
     * 折叠连续空白并去掉行首 Markdown 记号（标题井号、列表符号、序号），供跨格式差异的正文比对。
     * <p>
     * 结构识别会把有序列表序号、「####」级小标题从正文里抹掉，原文仍保留；
     * 两侧都按同一规则归一化，才能对「同一段正文」做等价比较。
     *
     * @param text 待归一化文本
     * @return 归一化文本；null 视为空串
     */
    private static String collapse(String text) {
        if (text == null) {
            return "";
        }
        String bare = LINE_MARKER.matcher(text).replaceAll("");
        return BLANK_RUN.matcher(bare.trim()).replaceAll(" ");
    }

    /**
     * 去掉超长再切追加的 (i/n) 后缀，用于和章节标题比对。
     *
     * @param line 一行文本
     * @return 去掉后缀后的文本
     */
    private static String stripPartSuffix(String line) {
        return line == null ? "" : PART_SUFFIX.matcher(line.trim()).replaceAll("").trim();
    }

    /**
     * 行首 Markdown 记号的长度（只含记号本身，不含其后的空白）。
     * <p>
     * 支持标题井号 1～6 个、无序列表的短横线 / 星号 / 加号、有序列表的「1.」「1、」「1)」。
     *
     * @param text 原文本
     * @param i    行首下标
     * @return 需要跳过的字符数；不是记号返回 0
     */
    private static int markerLength(String text, int i) {
        char first = text.charAt(i);
        if (first == '#') {
            int n = 0;
            while (i + n < text.length() && text.charAt(i + n) == '#') {
                n++;
            }
            int next = i + n;
            boolean headingLike = n <= 6 && (next >= text.length() || Character.isWhitespace(text.charAt(next)));
            return headingLike ? n : 0;
        }
        if (first == '-' || first == '*' || first == '+') {
            return i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1)) ? 1 : 0;
        }
        if (Character.isDigit(first)) {
            int n = 0;
            while (i + n < text.length() && Character.isDigit(text.charAt(i + n))) {
                n++;
            }
            int punct = i + n;
            if (punct < text.length() && ".、)".indexOf(text.charAt(punct)) >= 0
                    && punct + 1 < text.length() && Character.isWhitespace(text.charAt(punct + 1))) {
                return n + 1;
            }
        }
        return 0;
    }

    /**
     * 折叠空白的解析文本视图，并保留归一化下标 → 原文本下标映射。
     * <p>
     * 只读、无状态；一次入库/预览只构建一次，供所有切片定位复用。
     */
    private static final class OffsetIndex {

        private final String normalized;
        /** origin[k] = 归一化第 k 个字符在原文本中的下标；末位 origin[len] = 原文本长度 */
        private final int[] origin;

        private OffsetIndex(String normalized, int[] origin) {
            this.normalized = normalized;
            this.origin = origin;
        }

        /**
         * 构建归一化视图：连续空白折叠为单空格，并记录每个字符回原文本的下标。
         *
         * @param text 解析文本
         * @return 可定位的索引视图
         */
        static OffsetIndex of(String text) {
            StringBuilder sb = new StringBuilder(text.length());
            int[] origin = new int[text.length() + 1];
            int pendingSpaceAt = -1;
            boolean lineStart = true;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    lineStart = true;
                }
                if (lineStart) {
                    // 行首 Markdown 记号在切片正文里可能已被抹掉，比对时跳过，避免整片匹配落空
                    int marker = markerLength(text, i);
                    if (marker > 0) {
                        i += marker - 1;
                        continue;
                    }
                }
                if (Character.isWhitespace(c)) {
                    if (sb.length() > 0 && pendingSpaceAt < 0) {
                        pendingSpaceAt = i;
                    }
                    continue;
                }
                lineStart = false;
                if (pendingSpaceAt >= 0) {
                    origin[sb.length()] = pendingSpaceAt;
                    sb.append(' ');
                    pendingSpaceAt = -1;
                }
                origin[sb.length()] = i;
                sb.append(c);
            }
            origin[sb.length()] = text.length();
            return new OffsetIndex(sb.toString(), origin);
        }

        /**
         * 定位整片正文在归一化文本中的区间。
         * <p>
         * 先按入库顺序从游标处找，保证相邻片不互相抢位置；相邻片有重叠时正文起点可能早于游标，
         * 再允许一次全文回退。候选按「原样正文 → 去掉策略合成前缀」逐级降级。
         *
         * @param chunk 切片（用于识别合成前缀）
         * @param body  切片正文
         * @param from  归一化文本中的搜索起点
         * @return 归一化区间 [start, end)；定位不到为 null
         */
        int[] find(DocumentChunk chunk, String body, int from) {
            for (String candidate : matchCandidates(chunk, body)) {
                String needle = collapse(candidate);
                // 过短正文容易命中无关位置，宁可不标偏移
                if (needle.length() < 4) {
                    continue;
                }
                int at = normalized.indexOf(needle, Math.max(0, from));
                if (at < 0 && from > 0) {
                    at = normalized.indexOf(needle);
                }
                if (at >= 0) {
                    return new int[]{at, at + needle.length()};
                }
            }
            return null;
        }

        /** 归一化起点 → 原文本起点 */
        int originalStart(int normalizedStart) {
            return origin[normalizedStart];
        }

        /** 归一化终点（不含）→ 原文本终点（不含），取最后一个命中字符的下一位 */
        int originalEnd(int normalizedEnd) {
            return normalizedEnd <= 0 ? 0 : origin[normalizedEnd - 1] + 1;
        }
    }

    private static int metaInt(DocumentChunk chunk, String key, int fallback) {
        Object value = chunk.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
