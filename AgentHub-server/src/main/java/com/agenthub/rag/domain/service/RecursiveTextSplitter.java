package com.agenthub.rag.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain 风格递归字符切分。所属域：rag。
 * <p>
 * 参考 mall-swarm {@code RecursiveCharacterTextSplitter} 的思路（按分隔符优先级递归切开、小段合并、强制 overlap 前缀对齐、
 * 硬切避开字母/数字中间），重写为无业务耦合的纯算法：
 * <ul>
 *   <li>按「段落 → 句读 → 换行 → 逗号 → 字符」由粗到细尝试，粗档能放下就不进细档；</li>
 *   <li>合并时若溢出，新片开头带上一片结尾的 overlap 字符，保证相邻片有上下文重叠；</li>
 *   <li>硬切时切点落在非字母数字处，避免把英文单词/数字切开。</li>
 * </ul>
 * 无状态、纯函数，可直接单测。
 */
public final class RecursiveTextSplitter {

    /** 中文正文的默认分隔符优先级：先段落，再句读，最后换行/逗号 */
    private static final List<String> DEFAULT_SEPARATORS = List.of("\n\n", "。", "！", "？", "；", "\n", "，", " ");

    private RecursiveTextSplitter() {
    }

    /**
     * 切分文本。
     *
     * @param text       原文，空则返回空列表
     * @param chunkSize  目标片长（字符数），最小按 50 处理
     * @param overlap    相邻片重叠字符数，自动上限为 chunkSize/2
     * @return 非空切片，已去首尾空白
     */
    public static List<String> split(String text, int chunkSize, int overlap) {
        return split(text, chunkSize, overlap, DEFAULT_SEPARATORS);
    }

    /**
     * 切分文本，指定分隔符优先级。
     *
     * @param text       原文
     * @param chunkSize  目标片长
     * @param overlap    重叠字符数
     * @param separators 从粗到细的分隔符；最后可空串表示按字符硬切
     * @return 非空切片
     */
    public static List<String> split(String text, int chunkSize, int overlap, List<String> separators) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = Math.max(50, chunkSize);
        int ov = Math.max(0, Math.min(overlap, size / 2));
        List<String> seps = separators == null || separators.isEmpty() ? DEFAULT_SEPARATORS : separators;
        return splitRecursive(text, size, ov, seps).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 递归切分：找到第一个在文本中出现的分隔符，按它切开，再对超长片段进下一档。
     */
    private static List<String> splitRecursive(String text, int chunkSize, int overlap, List<String> separators) {
        String separator = separators.get(separators.size() - 1);
        List<String> next = List.of();
        for (int i = 0; i < separators.size(); i++) {
            String candidate = separators.get(i);
            if (candidate.isEmpty() || text.contains(candidate)) {
                separator = candidate;
                next = i + 1 < separators.size() ? separators.subList(i + 1, separators.size()) : List.of();
                break;
            }
        }
        List<String> pieces = splitKeepDelimiter(text, separator);
        List<String> good = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (String piece : pieces) {
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.length() <= chunkSize) {
                pending.add(piece);
                continue;
            }
            good.addAll(merge(pending, chunkSize, overlap));
            pending.clear();
            if (!next.isEmpty()) {
                good.addAll(splitRecursive(piece, chunkSize, overlap, next));
            } else {
                good.addAll(hardCut(piece, chunkSize, overlap));
            }
        }
        good.addAll(merge(pending, chunkSize, overlap));
        return good;
    }

    /**
     * 按分隔符切开并保留分隔符（中文句号切在句末更自然）。
     *
     * @param text      原文
     * @param delimiter 分隔符，空串表示按字符拆
     * @return 片段
     */
    static List<String> splitKeepDelimiter(String text, String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            List<String> chars = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                chars.add(String.valueOf(text.charAt(i)));
            }
            return chars;
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int idx = text.indexOf(delimiter, start);
            if (idx < 0) {
                parts.add(text.substring(start));
                break;
            }
            parts.add(text.substring(start, idx + delimiter.length()));
            start = idx + delimiter.length();
        }
        return parts;
    }

    /**
     * 小段合并到不超过 chunkSize；溢出时新片带上一片结尾的 overlap。
     */
    static List<String> merge(List<String> pieces, int chunkSize, int overlap) {
        if (pieces == null || pieces.isEmpty()) {
            return List.of();
        }
        List<String> docs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + piece.length() > chunkSize) {
                String prev = current.toString();
                docs.add(prev.trim());
                current.setLength(0);
                // 强制 overlap：后一片开头必须带前一片结尾，避免上下文被腰斩
                if (overlap > 0) {
                    current.append(overlapTail(prev, overlap));
                }
            }
            current.append(piece);
        }
        if (current.length() > 0) {
            docs.add(current.toString().trim());
        }
        return docs;
    }

    /**
     * 硬切：切点避开字母/数字中间，下一片从 end-overlap 回退到词首开始。
     */
    static List<String> hardCut(String text, int chunkSize, int overlap) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        int ov = Math.max(0, Math.min(overlap, chunkSize / 2));
        while (start < text.length()) {
            int idealEnd = Math.min(text.length(), start + chunkSize);
            int end = snapCutEnd(text, start, idealEnd);
            if (end <= start) {
                end = Math.min(text.length(), start + Math.max(1, chunkSize / 4));
            }
            parts.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            int nextStart = Math.max(0, end - ov);
            nextStart = snapCutStart(text, nextStart, end);
            if (nextStart <= start) {
                nextStart = start + 1;
            }
            start = nextStart;
        }
        return parts;
    }

    /**
     * 取末尾 overlap 字符，并把起点对齐到词边界。
     */
    static String overlapTail(String prev, int overlap) {
        if (prev == null || prev.isEmpty() || overlap <= 0) {
            return "";
        }
        int from = Math.max(0, prev.length() - overlap);
        from = snapCutStart(prev, from, prev.length());
        return prev.substring(from);
    }

    /**
     * idealEnd 落在字母/数字中间时，优先向前退到边界，退不动再向后。
     */
    static int snapCutEnd(String text, int start, int idealEnd) {
        if (idealEnd >= text.length() || idealEnd <= start) {
            return idealEnd;
        }
        if (!isAlnum(text.charAt(idealEnd - 1)) || !isAlnum(text.charAt(idealEnd))) {
            return idealEnd;
        }
        for (int i = idealEnd - 1; i > start; i--) {
            if (!isAlnum(text.charAt(i))) {
                return i + 1;
            }
        }
        for (int i = idealEnd; i < text.length(); i++) {
            if (!isAlnum(text.charAt(i))) {
                return i;
            }
        }
        return idealEnd;
    }

    /**
     * 起点落在字母/数字中间时回退到词首；回退过远（>48）则跳到下一词首。
     */
    static int snapCutStart(String text, int idealStart, int endExclusive) {
        if (idealStart <= 0 || idealStart >= text.length()) {
            return Math.max(0, idealStart);
        }
        if (!(isAlnum(text.charAt(idealStart - 1)) && isAlnum(text.charAt(idealStart)))) {
            return idealStart;
        }
        int back = idealStart;
        while (back > 0 && isAlnum(text.charAt(back - 1))) {
            back--;
        }
        if (idealStart - back <= 48) {
            return back;
        }
        for (int i = idealStart; i < endExclusive; i++) {
            if (!isAlnum(text.charAt(i))) {
                int j = i;
                while (j < endExclusive && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                return j < endExclusive ? j : idealStart;
            }
        }
        return idealStart;
    }

    private static boolean isAlnum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_';
    }
}
