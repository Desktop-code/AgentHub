package com.agenthub.rag.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain 风格递归字符切分：按分隔符优先级切开，小段合并到 chunkSize，超长则用下一档分隔符。
 * <p>
 * 硬切时保护英文字母/数字边界，并保证相邻两片满足 overlap 前缀对齐。
 */
final class RecursiveCharacterTextSplitter {

    /**
     * @param text       原文
     * @param chunkSize  目标长度（字符）
     * @param overlap    块间重叠
     * @param separators 从粗到细，最后一档可为空串表示按字符硬切
     * @return 非空切片
     */
    List<String> split(String text, int chunkSize, int overlap, List<String> separators) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int size = Math.max(50, chunkSize);
        int ov = Math.max(0, Math.min(overlap, size / 2));
        List<String> seps = separators == null || separators.isEmpty()
                ? List.of("\n\n", "\n", "。", " ")
                : separators;
        return splitRecursive(text, size, ov, seps).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private List<String> splitRecursive(String text, int chunkSize, int overlap, List<String> separators) {
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

    static List<String> splitKeepDelimiter(String text, String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            List<String> chars = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                chars.add(String.valueOf(text.charAt(i)));
            }
            return chars;
        }
        if (keepDelimiterAtStart(delimiter)) {
            return splitKeepAtStart(text, delimiter);
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

    private static List<String> splitKeepAtStart(String text, String delimiter) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int idx = text.indexOf(delimiter);
        while (idx >= 0) {
            if (idx > start) {
                parts.add(text.substring(start, idx));
            }
            start = idx;
            idx = text.indexOf(delimiter, start + delimiter.length());
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    static boolean keepDelimiterAtStart(String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) {
            return false;
        }
        return delimiter.indexOf('#') >= 0 || "\f".equals(delimiter);
    }

    /**
     * 小段直接拼接；溢出时新块开头必须带上一段结尾的 overlap 字符。
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
                // 强制 overlap：后一片开头必须带前一片结尾的 overlap 字符（词边界对齐）
                if (overlap > 0 && prev.length() > 0) {
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
     * 硬切：切分点避开字母/数字中间；下一块起点 = end - overlap，保证 overlap 前缀对齐。
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
            // 强制 overlap：下一块从 end-ov 起，并回退到词首，避免 "QL 的下载"
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
     * 取末尾 overlap 字符，并将起点对齐到词边界（优先回退到词首）。
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
     * 若 idealEnd 落在字母/数字中间，优先向前退到边界；退不动再向后。
     */
    static int snapCutEnd(String text, int start, int idealEnd) {
        if (idealEnd >= text.length() || idealEnd <= start) {
            return idealEnd;
        }
        if (!isAlnum(text.charAt(idealEnd - 1)) || !isAlnum(text.charAt(idealEnd))) {
            return idealEnd;
        }
        // 向前找空白/标点
        for (int i = idealEnd - 1; i > start; i--) {
            if (!isAlnum(text.charAt(i))) {
                return i + 1;
            }
        }
        // 向后找
        for (int i = idealEnd; i < text.length(); i++) {
            if (!isAlnum(text.charAt(i))) {
                return i;
            }
        }
        return idealEnd;
    }

    /**
     * 起点若在字母/数字中间：优先<strong>回退</strong>到词首（保证后一片以完整单词开头）；
     * 回退距离过大（&gt;48）时再向前跳到下一词首。
     */
    static int snapCutStart(String text, int idealStart, int endExclusive) {
        if (idealStart <= 0 || idealStart >= text.length()) {
            return Math.max(0, idealStart);
        }
        if (!(idealStart > 0 && isAlnum(text.charAt(idealStart - 1)) && isAlnum(text.charAt(idealStart)))) {
            return idealStart;
        }
        // 回退到词首
        int back = idealStart;
        while (back > 0 && isAlnum(text.charAt(back - 1))) {
            back--;
        }
        if (idealStart - back <= 48) {
            return back;
        }
        // 词过长：向前跳到空白后
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

    static boolean isAlnum(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }
}
