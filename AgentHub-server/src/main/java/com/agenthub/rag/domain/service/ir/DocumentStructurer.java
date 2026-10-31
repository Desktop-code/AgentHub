package com.agenthub.rag.domain.service.ir;

import com.agenthub.rag.domain.service.TocNoiseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IR 构建：去噪 + 按行识别块边界，输出 {@link StructuredDocument}（docType 待识别）。
 * <p>
 * HEADER 判定增加三层校验：局部特征、上下文、全文统计（重复标题 / 标题密度），
 * 避免配置样例、日志行被当成章节标题。
 * <p>
 * 构建标记 {@link #BUILD_TAG}：入库日志含此串则可确认新代码已加载。
 */
public class DocumentStructurer {

    private static final Logger log = LoggerFactory.getLogger(DocumentStructurer.class);

    /** 用于确认运行时是否加载了本节修修补丁 */
    public static final String BUILD_TAG = "ir-fix-20261024d";

    static final Pattern MD_HEADER = Pattern.compile("^(#{1,6})\\s+(.+)$");
    /** 旧式：1. / 1.2. （末段带点） */
    static final Pattern NUMBERED = Pattern.compile("^((?:\\d+\\.){1,4})\\s+(.+)$");
    /** 常见：1 / 1.2 / 1.2.3 标题（末段可不带点） */
    static final Pattern NUMBERED_FLEX = Pattern.compile("^(\\d+(?:\\.\\d+){0,3})\\s+(.+)$");
    /** 中文章节：第1章 / 第十二节 / 第3篇 … */
    static final Pattern CN_CHAPTER = Pattern.compile(
            "^第[零〇一二三四五六七八九十百千万两\\d]+[章节篇部][\\s\\u3000]*.{0,36}$");
    static final Pattern FAQ_Q = Pattern.compile("^(?:Q[:：]|问[:：]|【问】)\\s*(.+)$");
    static final Pattern FAQ_A = Pattern.compile("^(?:A[:：]|答[:：]|【答】)\\s*(.*)$");
    static final Pattern ENTRY_REF = Pattern.compile("^(.{2,40})《([^》]+)》\\s*$");
    static final Pattern FIELD = Pattern.compile(
            "^(别名|功效应用|用量用法|用法用量|性味归经|性味|归经|主治|注意事项|来源|出处|炮制|化学成分|药理作用|禁忌)"
                    + "[:：\\s]*(.*)$");
    static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)、])\\s+.+$");

    /** 纯符号 / 数字行（假标题） */
    private static final Pattern PURE_SYMBOL = Pattern.compile("^[-=<>\\[\\]\\d\\s]+$");
    /** 下一行 my.cnf 风格 key=value */
    private static final Pattern NEXT_KV = Pattern.compile("^\\w+\\s*=\\s*.+");
    /** 下一行 [section] */
    private static final Pattern NEXT_INI_SECTION = Pattern.compile("^\\[\\w+]$");
    /** 下一行日志字段 Query_time: xxx */
    private static final Pattern NEXT_LOG_FIELD = Pattern.compile("^\\w+_\\w+:.*");
    /** 下一行纯符号分隔 */
    private static final Pattern NEXT_PURE_SEP = Pattern.compile("^[-=<>\\[\\]]+$");

    /** wget / curl 进度与 DNS 解析噪声 */
    private static final Pattern NOISE_WGET_LOG = Pattern.compile(
            "(?i).*(?:\\bwget\\b|resolving|\\beta\\b|awaiting\\s+response).*");
    /** 时间戳下载日志：--12:00:00-- */
    private static final Pattern NOISE_WGET_TS = Pattern.compile("^\\s*--\\d{2}:\\d{2}:\\d{2}--.*");
    /** 网络连接日志 */
    private static final Pattern NOISE_CONNECTING = Pattern.compile("(?i).*Connecting to.*");
    private static final Pattern NOISE_FROM_HTTP = Pattern.compile("(?i).*from/http.*");
    /** ASCII 框线 / 进度条：====>、====+>、----+（注意 \\+ 为字面加号） */
    private static final Pattern NOISE_ASCII_BOX = Pattern.compile(".*(?:={2,}>|={2,}\\+>|\\-{2,}\\+).*");
    /** 用户指定进度条形态 ====+> */
    private static final Pattern NOISE_PROGRESS_BAR = Pattern.compile(".*====\\+>.*");
    /** 整行 ASCII 框线：==== / ---- / **** / #### */
    private static final Pattern NOISE_ASCII_RULE = Pattern.compile("^[\\-=+*#]{4,}$");
    /** 纯 URL 行 */
    private static final Pattern NOISE_PURE_URL = Pattern.compile("^https?://\\S+$");
    /** 纯数字行 */
    private static final Pattern NOISE_PURE_DIGITS = Pattern.compile("^\\s*\\d+\\s*$");
    /** Found Location: 跳转提示 */
    private static final Pattern NOISE_FOUND_LOCATION = Pattern.compile("(?i).*Found\\s+Location:.*");
    /** Linux 终端提示符：[user@host dir]$ / [root@host dir]# */
    private static final Pattern NOISE_SHELL_PROMPT = Pattern.compile("^\\[.*@.*].*[$#]\\s*.*");
    /** 常见 Linux 安装/管理命令行 */
    private static final Pattern NOISE_LINUX_CMD = Pattern.compile(
            "(?i).*(?:mysqladmin|rpm\\s+-ivh|tar\\s+-zxvf|yum\\s+install).*");
    /** RPM/安装进度条：#### [100%] */
    private static final Pattern NOISE_PCT_PROGRESS = Pattern.compile(".*[#=\\-]{4,}.*\\[\\d+%].*");
    /** 纯密码 / 命令参数碎片（如 new-password、Password:） */
    private static final Pattern NOISE_PASSWORD_LINE = Pattern.compile(
            "(?i)^(?:password\\s*:.*|['\"]?new-password['\"]?)\\s*$");
    /** 孤立 ftp/内网 URL 残片（缺 scheme 的 //host/ 或纯 host 路径） */
    private static final Pattern NOISE_BARE_HOST_URL = Pattern.compile(
            "^(?://[\\w.-]+/\\S*|ftp://\\S+)$");

    /** 同一标题出现超过该次数则降级为段落 */
    private static final int REPEAT_TITLE_LIMIT = 3;
    /** 滑动窗口内 HEADER 占比超过该阈值则整窗降级 */
    private static final double TITLE_DENSITY_LIMIT = 0.5d;
    private static final int TITLE_DENSITY_WINDOW = 10;

    /**
     * @param fileName  原始文件名
     * @param text      解析层输出的纯文本
     * @param chunkSize 后续切片目标长度
     * @param overlap   切片重叠
     */
    public StructuredDocument structure(String fileName, String text, int chunkSize, int overlap) {
        return structure(fileName, text, chunkSize, overlap, 150);
    }

    /**
     * @param minChunkSize 最小块长度（写入 metadata，供层级切片合并用）
     */
    public StructuredDocument structure(String fileName, String text, int chunkSize, int overlap, int minChunkSize) {
        StructuredDocument doc = new StructuredDocument();
        doc.setFileName(fileName == null ? "" : fileName);
        doc.getMetadata().put("chunkSize", Math.max(80, chunkSize));
        doc.getMetadata().put("chunkOverlap", Math.max(0, overlap));
        doc.getMetadata().put("minChunkSize", Math.max(0, minChunkSize));
        if (!StringUtils.hasText(text)) {
            return doc;
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        normalized = TocNoiseFilter.stripBeforeChunk(normalized);
        if (!StringUtils.hasText(normalized)) {
            return doc;
        }
        // 请求级统计，禁止用单例字段（DocumentStructurer 是 Spring 单例）
        TitleStats stats = new TitleStats();
        String[] pages = normalized.split("\\f", -1);
        int seq = 0;
        for (int p = 0; p < pages.length; p++) {
            String page = pages[p];
            if (!StringUtils.hasText(page) || TocNoiseFilter.isTocPage(page.trim())) {
                continue;
            }
            int pageNum = pages.length > 1 ? p + 1 : 0;
            seq = scanPage(doc, page, pageNum, seq, stats);
        }
        filterByStatistics(doc.getBlocks(), stats);
        int headerFinal = 0;
        for (StructuredBlock b : doc.getBlocks()) {
            if (b.getBlockType() == BlockType.HEADER) {
                headerFinal++;
                log.info("[IR][{}] HEADER最终保留 title='{}' page={} level={}",
                        BUILD_TAG, abbreviate(b.getContent(), 60), b.getPageNum(), b.getLevel());
            }
        }
        doc.getMetadata().put("noiseHits", stats.noiseHits.get());
        doc.getMetadata().put("headerCount", headerFinal);
        doc.getMetadata().put("irBuild", BUILD_TAG);
        log.info("[IR][{}] structure完成 file={} pages={} blocks={} headers={} noiseHits={} rejectedTitles={}",
                BUILD_TAG, doc.getFileName(), pages.length, doc.getBlocks().size(),
                headerFinal, stats.noiseHits.get(), stats.rejectedTitles);
        return doc;
    }

    private int scanPage(StructuredDocument doc, String page, int pageNum, int seq, TitleStats stats) {
        String[] lines = page.split("\\n", -1);
        String currentEntryId = null;
        StringBuilder para = new StringBuilder();
        int paraPage = pageNum;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i] == null ? "" : lines[i];
            String line = raw.trim();
            if (!StringUtils.hasText(line) || isNoise(line, stats) || TocNoiseFilter.isTocLine(line)) {
                seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                continue;
            }
            String prevLine = i > 0 ? lines[i - 1] : "";
            String nextLine = peek(lines, i + 1);

            Matcher md = MD_HEADER.matcher(line);
            if (md.matches()) {
                seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                String titleText = md.group(2).trim();
                if (TocNoiseFilter.isTocTitle(titleText)) {
                    continue;
                }
                // MD 标题：不强制「前一行空白」（MinerU 常紧贴正文），只做局部+下一行校验
                if (isValidTitle(titleText, prevLine, nextLine, false)) {
                    StructuredBlock h = block("h-" + (++seq), BlockType.HEADER, titleText, md.group(1).length(), pageNum);
                    doc.getBlocks().add(h);
                    stats.titleCount.merge(titleText, 1, Integer::sum);
                    log.debug("[IR][{}] MD_HEADER接受 '{}' page={}", BUILD_TAG, abbreviate(titleText, 40), pageNum);
                } else {
                    stats.rejectedTitles++;
                    log.info("[IR][{}] MD_HEADER拒绝→段落 '{}' prev='{}' next='{}'",
                            BUILD_TAG, abbreviate(titleText, 40),
                            abbreviate(prevLine, 30), abbreviate(nextLine, 30));
                    StructuredBlock p = block("p-" + (++seq), BlockType.PARAGRAPH, titleText, 1, pageNum);
                    doc.getBlocks().add(p);
                }
                currentEntryId = null;
                continue;
            }
            Matcher cn = CN_CHAPTER.matcher(line);
            if (cn.matches()) {
                seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                String titleText = line.trim();
                if (!TocNoiseFilter.isTocTitle(titleText)
                        && isValidTitle(titleText, prevLine, nextLine, false)) {
                    StructuredBlock h = block("h-" + (++seq), BlockType.HEADER, titleText, 1, pageNum);
                    h.putMeta("number", "cn");
                    doc.getBlocks().add(h);
                    stats.titleCount.merge(titleText, 1, Integer::sum);
                    log.debug("[IR][{}] CN_CHAPTER接受 '{}' page={}", BUILD_TAG, abbreviate(titleText, 40), pageNum);
                } else {
                    stats.rejectedTitles++;
                    log.info("[IR][{}] CN_CHAPTER拒绝→段落 '{}'", BUILD_TAG, abbreviate(titleText, 40));
                    if (para.length() > 0) {
                        para.append('\n');
                    }
                    para.append(line);
                }
                currentEntryId = null;
                continue;
            }
            Matcher num = NUMBERED.matcher(line);
            if (!num.matches()) {
                num = NUMBERED_FLEX.matcher(line);
            }
            if (num.matches()) {
                String titleText = num.group(2).trim();
                // 弱编号标题仍要求前一行空白，降低配置样例误判；中文章节已在上方分支
                boolean requireBlankPrev = !isChineseChapterTitle(titleText);
                if (isValidTitle(titleText, prevLine, nextLine, requireBlankPrev)) {
                    seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                    int level = Math.max(1, num.group(1).split("\\.").length);
                    StructuredBlock h = block("h-" + (++seq), BlockType.HEADER, titleText, level, pageNum);
                    h.putMeta("number", num.group(1));
                    doc.getBlocks().add(h);
                    stats.titleCount.merge(titleText, 1, Integer::sum);
                    log.debug("[IR][{}] NUMBERED接受 '{}' page={}", BUILD_TAG, abbreviate(titleText, 40), pageNum);
                    currentEntryId = null;
                    continue;
                }
                stats.rejectedTitles++;
                log.debug("[IR][{}] NUMBERED拒绝 '{}' prev='{}' next='{}'",
                        BUILD_TAG, abbreviate(titleText, 40),
                        abbreviate(prevLine, 30), abbreviate(nextLine, 30));
                // 编号行未通过标题校验：不单独成 HEADER，落入段落累积
            }
            if (isTableLine(line)) {
                // 表行整块吸入 TABLE，单元格不再单独跑 ENTRY_REF
                seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                List<String> tableLines = new ArrayList<>();
                tableLines.add(line);
                while (i + 1 < lines.length && isTableLine(lines[i + 1].trim())) {
                    i++;
                    tableLines.add(lines[i].trim());
                }
                StructuredBlock t = block("t-" + (++seq), BlockType.TABLE, String.join("\n", tableLines), 0, pageNum);
                doc.getBlocks().add(t);
                continue;
            }
            // REQ-01：LIST 优先于 ENTRY_REF，交叉引用列表绝不升格为 ENTRY
            if (LIST_ITEM.matcher(line).matches()) {
                seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                StructuredBlock li = block("l-" + (++seq), BlockType.LIST, line, 1, pageNum);
                li.setParentId(currentEntryId);
                doc.getBlocks().add(li);
                continue;
            }
            Matcher q = FAQ_Q.matcher(line);
            if (q.matches()) {
                seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                StructuredBlock e = block("e-" + (++seq), BlockType.ENTRY, q.group(1).trim(), 0, pageNum);
                e.putMeta("entry_name", q.group(1).trim());
                // REQ-02：显式问答 ENTRY 质量标记，供 DocumentTypeAnalyzer 计分
                e.putMeta("entry_kind", "faq");
                doc.getBlocks().add(e);
                currentEntryId = e.getBlockId();
                continue;
            }
            Matcher a = FAQ_A.matcher(line);
            if (a.matches() && currentEntryId != null) {
                seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                StructuredBlock f = block("f-" + (++seq), BlockType.FIELD, a.group(1) == null ? "" : a.group(1).trim(), 1, pageNum);
                f.setParentId(currentEntryId);
                f.putMeta("field_type", "答");
                doc.getBlocks().add(f);
                continue;
            }
            Matcher field = FIELD.matcher(line);
            if (field.matches()) {
                seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                String rest = field.group(2) == null ? "" : field.group(2).trim();
                StructuredBlock f = block("f-" + (++seq), BlockType.FIELD, rest, 1, pageNum);
                f.setParentId(currentEntryId);
                f.putMeta("field_type", field.group(1));
                doc.getBlocks().add(f);
                continue;
            }
            Matcher entry = ENTRY_REF.matcher(line);
            boolean entryRefHit = entry.matches();
            boolean entryTitleHit = !entryRefHit && isEntryTitle(line, peek(lines, i + 1));
            if (entryRefHit || entryTitleHit) {
                String prefix = entryRefHit ? entry.group(1).trim() : line.trim();
                String ref = entryRefHit ? entry.group(2).trim() : "";
                String name = entryRefHit ? resolveEntryName(prefix, ref, line) : line.trim();
                // 前缀以「见/详见」收尾 = 交叉引用，即使书名可用也不升格 ENTRY（落入段落）
                if (!endsWithSeeRef(prefix) && acceptEntryCandidate(line, name)) {
                    seq = flushPara(doc, para, paraPage, currentEntryId, seq);
                    StructuredBlock e = block("e-" + (++seq), BlockType.ENTRY, line, 0, pageNum);
                    e.putMeta("entry_name", name);
                    if (StringUtils.hasText(ref)) {
                        e.putMeta("source_ref", ref);
                    }
                    doc.getBlocks().add(e);
                    currentEntryId = e.getBlockId();
                    continue;
                }
                log.debug("[IR][{}] ENTRY_REF拒绝 line='{}' prefix='{}' name='{}'",
                        BUILD_TAG, abbreviate(line, 50), abbreviate(prefix, 30), abbreviate(name, 40));
            }
            if (para.isEmpty()) {
                paraPage = pageNum;
            }
            if (para.length() > 0) {
                para.append('\n');
            }
            para.append(line);
        }
        return flushPara(doc, para, paraPage, currentEntryId, seq);
    }

    /**
     * 标题校验（默认要求前一行空白，兼容旧调用）。
     */
    boolean isValidTitle(String title, String prev, String next) {
        return isValidTitle(title, prev, next, true);
    }

    /**
     * 标题三层校验：局部特征 + 上下文；全文统计在 {@link #filterByStatistics}。
     *
     * @param requireBlankPrev true 时前一行须空（或也是标题）；MD/中文章节应传 false，避免误杀
     */
    boolean isValidTitle(String title, String prev, String next, boolean requireBlankPrev) {
        if (!StringUtils.hasText(title) || title.length() > 40 || title.endsWith("。")) {
            return false;
        }
        if (PURE_SYMBOL.matcher(title).matches()) {
            return false;
        }
        // 明显日志 / 配置行本身
        if (title.matches("(?i).*(query_time|lock_time|rows_sent|end of log).*")) {
            return false;
        }
        if (next != null) {
            String n = next.trim();
            if (StringUtils.hasText(n)) {
                if (NEXT_KV.matcher(n).matches()) {
                    return false;
                }
                if (NEXT_INI_SECTION.matcher(n).matches()) {
                    return false;
                }
                if (NEXT_LOG_FIELD.matcher(n).matches()) {
                    return false;
                }
                if (NEXT_PURE_SEP.matcher(n).matches()) {
                    return false;
                }
            }
        }
        // 中文章节标题：不因「前一行非空」拒绝（正文后紧跟「第1章」很常见）
        if (isChineseChapterTitle(title)) {
            return true;
        }
        if (requireBlankPrev && prev != null && StringUtils.hasText(prev.trim())) {
            String p = prev.trim();
            if (!MD_HEADER.matcher(p).matches()
                    && !NUMBERED.matcher(p).matches()
                    && !NUMBERED_FLEX.matcher(p).matches()
                    && !CN_CHAPTER.matcher(p).matches()) {
                return false;
            }
        }
        return true;
    }

    static boolean isChineseChapterTitle(String title) {
        return StringUtils.hasText(title) && CN_CHAPTER.matcher(title.trim()).matches();
    }

    /**
     * 全文统计：重复标题降级；滑动窗口标题密度过高整体降级。
     */
    void filterByStatistics(List<StructuredBlock> blocks, TitleStats stats) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        int demotedRepeat = 0;
        for (StructuredBlock b : blocks) {
            if (b.getBlockType() != BlockType.HEADER) {
                continue;
            }
            String title = b.getContent() == null ? "" : b.getContent();
            // 中文章节不因重复降级（目录+正文可能各出现一次以上仍应保留）
            if (isChineseChapterTitle(title)) {
                continue;
            }
            if (stats.titleCount.getOrDefault(title, 0) > REPEAT_TITLE_LIMIT) {
                log.info("[IR][{}] 统计降级(重复>{}): '{}'", BUILD_TAG, REPEAT_TITLE_LIMIT, abbreviate(title, 40));
                b.setBlockType(BlockType.PARAGRAPH);
                b.setLevel(1);
                demotedRepeat++;
            }
        }
        // 滑动窗口：仅满窗（≥ TITLE_DENSITY_WINDOW）且 HEADER 占比 >50% 才整窗降级，
        // 避免短文档「3 个真标题 + 2 段正文」被误杀。中文章节不参与密度降级。
        int n = blocks.size();
        boolean[] demote = new boolean[n];
        for (int i = 0; i + TITLE_DENSITY_WINDOW <= n; i++) {
            int end = i + TITLE_DENSITY_WINDOW;
            int headers = 0;
            for (int j = i; j < end; j++) {
                if (blocks.get(j).getBlockType() == BlockType.HEADER) {
                    headers++;
                }
            }
            if (headers * 1.0d / TITLE_DENSITY_WINDOW > TITLE_DENSITY_LIMIT) {
                for (int j = i; j < end; j++) {
                    StructuredBlock b = blocks.get(j);
                    if (b.getBlockType() == BlockType.HEADER && !isChineseChapterTitle(b.getContent())) {
                        demote[j] = true;
                    }
                }
            }
        }
        int demotedDensity = 0;
        for (int i = 0; i < n; i++) {
            if (demote[i]) {
                StructuredBlock b = blocks.get(i);
                log.info("[IR][{}] 统计降级(密度): '{}'", BUILD_TAG, abbreviate(b.getContent(), 40));
                b.setBlockType(BlockType.PARAGRAPH);
                b.setLevel(1);
                demotedDensity++;
            }
        }
        if (demotedRepeat + demotedDensity > 0) {
            log.info("[IR][{}] filterByStatistics demotedRepeat={} demotedDensity={}",
                    BUILD_TAG, demotedRepeat, demotedDensity);
        }
    }

    private int flushPara(StructuredDocument doc, StringBuilder para, int pageNum, String parentId, int seq) {
        if (para.isEmpty()) {
            return seq;
        }
        String text = para.toString().trim();
        para.setLength(0);
        if (!StringUtils.hasText(text)) {
            return seq;
        }
        StructuredBlock p = block("p-" + (++seq), BlockType.PARAGRAPH, text, 1, pageNum);
        p.setParentId(parentId);
        doc.getBlocks().add(p);
        return seq;
    }

    private static StructuredBlock block(String id, BlockType type, String content, int level, int pageNum) {
        StructuredBlock b = new StructuredBlock();
        b.setBlockId(id);
        b.setBlockType(type);
        b.setContent(content);
        b.setLevel(level);
        b.setPageNum(pageNum);
        return b;
    }

    /**
     * 去噪：wget 日志、URL、ASCII 框线、纯数字、Found Location、版权、过短无字段行。
     */
    boolean isNoise(String line) {
        return isNoise(line, null);
    }

    boolean isNoise(String line, TitleStats stats) {
        if (!StringUtils.hasText(line)) {
            return true;
        }
        String t = line.trim();
        if (NOISE_WGET_LOG.matcher(t).matches()
                || NOISE_WGET_TS.matcher(t).matches()
                || NOISE_CONNECTING.matcher(t).matches()
                || NOISE_FROM_HTTP.matcher(t).matches()
                || NOISE_ASCII_BOX.matcher(t).matches()
                || NOISE_PROGRESS_BAR.matcher(t).matches()
                || NOISE_ASCII_RULE.matcher(t).matches()
                || NOISE_PCT_PROGRESS.matcher(t).matches()
                || NOISE_SHELL_PROMPT.matcher(t).matches()
                || NOISE_LINUX_CMD.matcher(t).matches()
                || NOISE_PASSWORD_LINE.matcher(t).matches()
                || NOISE_BARE_HOST_URL.matcher(t).matches()
                || NOISE_PURE_URL.matcher(t).matches()
                || NOISE_PURE_DIGITS.matcher(t).matches()
                || NOISE_FOUND_LOCATION.matcher(t).matches()) {
            if (stats != null) {
                stats.noiseHits.incrementAndGet();
            }
            return true;
        }
        if (t.contains("版权所有") || t.contains("软件工作室")) {
            if (stats != null) {
                stats.noiseHits.incrementAndGet();
            }
            return true;
        }
        if (t.contains("收录") && (t.contains("共") || t.contains("分别为"))) {
            if (stats != null) {
                stats.noiseHits.incrementAndGet();
            }
            return true;
        }
        if (t.length() < 10 && !FIELD.matcher(t).matches() && !ENTRY_REF.matcher(t).matches()
                && !FAQ_Q.matcher(t).matches() && !t.contains("《")) {
            if (!looksLikeHeading(t) && t.length() < 6) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    static boolean isTableLine(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        if (line.contains("|") && line.chars().filter(c -> c == '|').count() >= 2) {
            return true;
        }
        return line.contains("\t") && line.split("\t").length >= 3;
    }

    private static boolean isEntryTitle(String line, String next) {
        if (!StringUtils.hasText(line) || line.length() > 40) {
            return false;
        }
        if (line.endsWith("。") || line.endsWith("！") || line.endsWith("？")) {
            return false;
        }
        // REQ-01：列表/表行不得经 isEntryTitle 升格
        if (LIST_ITEM.matcher(line).matches() || isTableLine(line)) {
            return false;
        }
        if (startsWithListMarker(line) || endsWithSeeRef(line.trim())) {
            return false;
        }
        return StringUtils.hasText(next) && FIELD.matcher(next.trim()).matches();
    }

    /**
     * REQ-01：ENTRY_REF / isEntryTitle 门禁。
     * 列表行、表行、「见/详见」截断名、列表符开头的 title 一律拒绝。
     */
    static boolean acceptEntryCandidate(String line, String candidateName) {
        if (!StringUtils.hasText(line) || isTableLine(line) || LIST_ITEM.matcher(line).matches()) {
            return false;
        }
        String name = candidateName == null ? "" : candidateName.trim();
        if (!StringUtils.hasText(name)) {
            return false;
        }
        if (startsWithListMarker(name) || startsWithListMarker(line)) {
            return false;
        }
        if (endsWithSeeRef(name)) {
            return false;
        }
        return true;
    }

    /**
     * 解析 ENTRY_REF 的展示名：禁止留下「…见」截断；前缀以见/详见收尾时改用书名号内文本。
     */
    static String resolveEntryName(String prefix, String book, String fullLine) {
        String p = prefix == null ? "" : prefix.trim();
        String b = book == null ? "" : book.trim();
        if (endsWithSeeRef(p)) {
            return StringUtils.hasText(b) ? b : (fullLine == null ? p : fullLine.trim());
        }
        if (startsWithListMarker(p)) {
            return StringUtils.hasText(b) ? b : p.replaceFirst("^\\s*[-*•]\\s*", "").trim();
        }
        return StringUtils.hasText(p) ? p : b;
    }

    static boolean endsWithSeeRef(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String t = text.trim();
        return t.endsWith("详见") || t.endsWith("见");
    }

    static boolean startsWithListMarker(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String t = text.trim();
        return t.startsWith("-") || t.startsWith("*") || t.startsWith("•");
    }

    /** 保留给 isNoise 等短行启发式；正式 HEADER 走 {@link #isValidTitle}。 */
    private static boolean looksLikeHeading(String title) {
        if (!StringUtils.hasText(title) || title.length() > 40) {
            return false;
        }
        return !title.endsWith("。");
    }

    private static String peek(String[] lines, int idx) {
        if (idx < 0 || idx >= lines.length || lines[idx] == null) {
            return "";
        }
        return lines[idx].trim();
    }

    /** 单次 structure 调用的标题计数（线程安全：局部对象）。 */
    static final class TitleStats {
        final Map<String, Integer> titleCount = new HashMap<>();
        final AtomicInteger noiseHits = new AtomicInteger();
        int rejectedTitles;
    }
}
