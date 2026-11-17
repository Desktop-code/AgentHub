package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.service.TrialTexts;

import java.util.List;

/**
 * 多问并行结束后给用户看的整轮收口。所属域：runtime。
 *
 * <p>不调模型，也不把各专家的 {@code ###} 拼接稿再贴一遍冒充汇总。
 * 每个方案只留自己的结论要点：方案真汇总过就用那段汇总，没有就取第一句实质内容。
 * 产物文件不写进这段字，由投影挂到同一张收口卡上，文件名才能下载。
 */
public final class RoundCloseout {

    /** 方案真汇总写进收口时的长度硬顶：整篇仍在该方案框里，这里只留要点 */
    private static final int SUMMARY_LIMIT = 480;

    /** 没有真汇总时，第一句要点的长度硬顶 */
    private static final int POINT_LIMIT = 120;

    private RoundCloseout() {
    }

    /**
     * 按派发顺序拼各方案要点。
     *
     * <p>标题用参与者展示名，不用 {@code ###}，避免投影把这段字拆回各专家卡、
     * 收口上只剩「正文在上面」。
     *
     * @param outcomes 各问的执行结果；可空
     * @return 收口正文；没有结果时为空串
     */
    public static String textOf(List<ChildOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChildOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            String point = pointOf(outcome);
            if (point.isBlank()) {
                continue;
            }
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(RouterTexts.concatHeading(outcome)).append('\n').append(point);
        }
        return text.toString();
    }

    /**
     * 一个方案在收口里的那一句。
     *
     * <p>真汇总（{@code conclusionName} 有值，且正文不是拼接稿）整段收成要点。
     * 拼接稿只留第一段实质内容的第一句，不把各位专家的全文再写一遍。
     *
     * @param outcome 一个车间或专家的结果
     * @return 要点；没有可展示的内容时给一句终态
     */
    static String pointOf(ChildOutcome outcome) {
        if (outcome.skipped()) {
            return finishLine("未执行。", outcome.error());
        }
        if (outcome.error() != null) {
            return finishLine("未完成。", outcome.error());
        }
        String raw = outcome.turn() == null ? "" : outcome.turn().getAssistantText();
        raw = raw == null ? "" : raw.trim();
        if (outcome.conclusionName() != null && !raw.isBlank() && !isConcatDraft(raw)) {
            return TrialTexts.clip(stripHeadingMarks(raw), SUMMARY_LIMIT);
        }
        String sentence = firstSentence(raw);
        if (!sentence.isBlank()) {
            return sentence;
        }
        return "已完成。";
    }

    /**
     * 拼接稿以 {@code ###} 章节开头。这种正文不是方案自己的汇总。
     *
     * @param text 终答正文
     * @return true 表示整段是按人拼接的稿
     */
    private static boolean isConcatDraft(String text) {
        return text.startsWith("###");
    }

    /**
     * 跳过章节标题，取第一段实质内容的第一句。
     *
     * @param raw 终答正文；可空
     * @return 一句要点；没有实质内容时为空串
     */
    private static String firstSentence(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder paragraph = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("###")) {
                continue;
            }
            if (trimmed.isEmpty()) {
                if (paragraph.length() > 0) {
                    break;
                }
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
            if (paragraph.length() >= POINT_LIMIT) {
                break;
            }
        }
        String text = paragraph.toString().trim();
        if (text.isEmpty()) {
            return "";
        }
        int end = sentenceEnd(text);
        String sentence = end > 0 ? text.substring(0, end) : text;
        return TrialTexts.clip(sentence, POINT_LIMIT);
    }

    /**
     * 去掉行首的 {@code ###}，避免收口正文被当成拼接稿拆走。
     *
     * @param raw 方案真汇总
     * @return 去掉章节标记后的正文
     */
    private static String stripHeadingMarks(String raw) {
        StringBuilder text = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("###")) {
                trimmed = trimmed.substring(3).trim();
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(trimmed);
        }
        return text.toString();
    }

    /**
     * 第一句的结束位置（含句号）。没有句号时返回 -1，调用方再按长度截断。
     *
     * @param text 已经去掉章节标题的一段
     * @return 句末下标之后的位置；没有句末时为 -1
     */
    private static int sentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * 未执行 / 未完成的一句。说明紧跟在状态后面，空说明不另起一句。
     *
     * @param status 状态
     * @param detail 原因；可空
     * @return 一句终态
     */
    private static String finishLine(String status, String detail) {
        String extra = detail == null ? "" : detail.trim();
        if (extra.isEmpty()) {
            return status;
        }
        return status + extra;
    }
}
