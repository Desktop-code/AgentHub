package com.agenthub.runtime.application.router;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工作台入口：把「一条消息里的多个问题」切成彼此独立的问题。所属域：runtime。
 *
 * <p>为什么需要它：入口此前只把整条消息当成一个诉求，判路由最多落一个目标，
 * 于是用户一次问两件事时，后一问被前一个目标吞掉（典型：「…并起草一份报告。同时也按工作流跑一次」
 * 只跑了方案，工作流从未执行）。本类只负责<b>切问</b>，选谁仍由既有判路由 / 点名 / 关键字完成。
 *
 * <p>与「一问多项交付」的边界（硬性）：
 * <ul>
 *   <li>同一件事的多块交付（「整理知识要点，并起草一份报告」）<b>不切</b>：
 *       顿号、逗号、以及「并 / 且 / 以及」连接的并列成分都算同一问；
 *   <li>只有明确的第二件事才切：另起一句，或「另外 / 此外 / 同时 / 也 / 再 …」引出、
 *       且这一段带目标线索（点名某个候选，或写了「工作流 / 方案 / 专家 / 助手」）。
 * </ul>
 *
 * <p>不做的事：不判断谁更合适、不查库、不调模型；切不开时原样返回一条，
 * 单问与闲聊因此与未改造前完全一致。
 */
public final class EntryQuestionSplitter {

    /** 明确的「另一件事」引子：出现即认为开始新的一问（无需再找目标线索）。 */
    private static final List<String> STRONG_MARKERS = List.of(
            "另一个问题", "另外一个问题", "第二个问题", "再问一个", "另外", "此外", "再者", "此外还",
            "顺便问", "顺便再", "顺带问", "还有一个");

    /** 弱引子：只有后半段确实带了目标线索时才算新的一问。 */
    private static final List<String> WEAK_MARKERS = List.of("同时", "也", "再", "还有", "以及", "另外");

    /** 承接上一句的并列词：以它开头的一律不切，避免把「并起草一份报告」拆成第二问。 */
    private static final List<String> CONTINUATIONS = List.of("并", "并且", "且", "以及", "然后", "接着");

    /** 目标线索：出现这些词说明这一段在指向某条线路，而不是补充同一件事的交付物。 */
    private static final List<String> TARGET_CUES = List.of("工作流", "方案", "专家", "助手", "智能体");

    /** 判断「这一问是否只是让上一条问题换个线路再跑一次」时要剥掉的动作词。 */
    private static final List<String> ACTION_WORDS = List.of(
            "跑一次", "跑一遍", "执行一次", "执行一遍", "运行一次", "运行一遍", "再跑", "再执行", "再运行",
            "试一下", "试试", "看看", "一下", "一遍", "一次", "处理", "交给", "通过", "调用",
            "按", "用", "走", "请", "帮我", "帮忙", "帮", "麻烦", "让", "把", "给", "吧", "呗", "谢谢");

    /** 纯客套 / 应答：不构成一问，切出来后直接丢掉。 */
    private static final Set<String> PLEASANTRIES = new LinkedHashSet<>(List.of(
            "你好", "您好", "谢谢", "多谢", "好的", "好", "收到", "嗯", "嗯嗯", "ok", "okay", "thanks", "再见", "在吗"));

    /** 一段「只是换个线路再跑一次」的补充，最多这么长（剥掉动作词后的有效字数）。 */
    private static final int ANAPHORIC_RESIDUAL_MAX = 3;

    private EntryQuestionSplitter() {
    }

    /**
     * 一条切出来的问题。
     *
     * @param text    这一问的原文（用于判路由、关键字、点名方案）
     * @param subQuery 送给该目标的子问题；承接上一问的短句会补成上一问的完整内容
     */
    public record Question(String text, String subQuery) {
    }

    /**
     * 切问入口。
     *
     * @param userText    用户原话；可空
     * @param targetNames 入口本轮候选的展示名（方案 / 工作流 / 专家），用于识别「这一段在说另一条线路」
     * @return 至少一条；切不出第二问时只有原句一条
     */
    public static List<Question> split(String userText, Collection<String> targetNames) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> parts = cut(text, targetNames);
        if (parts.size() <= 1) {
            return List.of(new Question(text, text));
        }
        List<Question> questions = new ArrayList<>();
        for (String part : parts) {
            String previous = questions.isEmpty() ? null : questions.get(questions.size() - 1).subQuery();
            // 「同时也按工作流跑一次」这类短句自身没有内容：子问题沿用上一问，否则工作流会拿到一句空请求
            boolean anaphoric = previous != null && isAnaphoric(part, targetNames);
            String subQuery = anaphoric ? previous : part;
            questions.add(new Question(part, subQuery));
        }
        return questions;
    }

    /**
     * 按句末标点与「第二件事」引子切段。
     *
     * @param text        用户原话
     * @param targetNames 候选展示名
     * @return 顺序保持原话顺序的段；可能只有一段
     */
    private static List<String> cut(String text, Collection<String> targetNames) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // 先按句末标点切句：句号是最稳的「另一件事」边界，但新句是否真的另起一问由下面判断
        String[] sentences = text.split("[。！？!?\\n\\r]+");
        for (int s = 0; s < sentences.length; s++) {
            String sentence = sentences[s] == null ? "" : sentences[s].trim();
            if (sentence.isEmpty()) {
                continue;
            }
            String[] clauses = sentence.split("[，,]");
            for (int c = 0; c < clauses.length; c++) {
                String clause = clauses[c] == null ? "" : clauses[c].trim();
                if (clause.isEmpty()) {
                    continue;
                }
                if (startsNewQuestion(clause, s > 0, c == 0, targetNames)) {
                    flush(segments, current);
                }
                if (current.length() > 0) {
                    // 同一段内部把逗号 / 句号还原成可读分隔，避免把两句话粘成一句丢给判路由
                    current.append(c == 0 && s > 0 ? '。' : '，');
                }
                current.append(clause);
            }
        }
        flush(segments, current);
        return segments;
    }

    /**
     * 这一段是否开始新的一问。
     *
     * @param clause      当前小句
     * @param newSentence 是否处在句号之后的第一句
     * @param firstClause 是否是一句里的第一个小句
     * @param targetNames 候选展示名
     * @return true 表示从这里断开
     */
    private static boolean startsNewQuestion(String clause, boolean newSentence, boolean firstClause,
                                             Collection<String> targetNames) {
        if (startsWithAny(clause, CONTINUATIONS)) {
            // 「并 / 且 / 以及」承接上一句，属于同一件事的另一块交付
            return false;
        }
        boolean cue = hasTargetCue(clause, targetNames);
        if (!firstClause) {
            // 逗号之后的第二件事：强引子无条件切开；弱引子必须带目标线索
            return startsWithAny(clause, STRONG_MARKERS) || (startsWithAny(clause, WEAK_MARKERS) && cue);
        }
        if (!newSentence) {
            return false;
        }
        // 句号之后：强引子直接切开，弱引子与裸句子都要有目标线索，否则视为上一句的补充
        return startsWithAny(clause, STRONG_MARKERS) || (startsWithAny(clause, WEAK_MARKERS) && cue) || cue;
    }

    /** 把当前累积段落到结果里；空段或纯客套直接丢。 */
    private static void flush(List<String> segments, StringBuilder current) {
        String text = current.toString().trim();
        current.setLength(0);
        if (text.length() < 2) {
            return;
        }
        if (PLEASANTRIES.contains(text.toLowerCase(Locale.ROOT))) {
            return;
        }
        segments.add(text);
    }

    /**
     * 这一段是否带有目标线索（点名候选，或写了工作流 / 方案 / 专家 / 助手）。
     *
     * @param clause      小句
     * @param targetNames 候选展示名；可空
     * @return true 表示这一段指向某条线路
     */
    private static boolean hasTargetCue(String clause, Collection<String> targetNames) {
        String lower = clause.toLowerCase(Locale.ROOT);
        for (String cue : TARGET_CUES) {
            if (lower.contains(cue)) {
                return true;
            }
        }
        if (targetNames == null) {
            return false;
        }
        for (String name : targetNames) {
            String trimmed = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (trimmed.length() >= 2 && lower.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一段是否只是「换个线路，把上一问再跑一次」。
     *
     * <p>判据：剥掉引子、目标名与动作词后几乎不剩内容。只剩「出报告」这类极短补充也算承接，
     * 避免工作流拿到一句没有上下文的空请求。
     *
     * @param part        小段原文
     * @param targetNames 候选展示名
     * @return true 表示子问题应沿用上一问
     */
    private static boolean isAnaphoric(String part, Collection<String> targetNames) {
        String residual = part == null ? "" : part;
        List<String> strips = new ArrayList<>(STRONG_MARKERS);
        strips.addAll(WEAK_MARKERS);
        strips.addAll(CONTINUATIONS);
        strips.addAll(TARGET_CUES);
        strips.addAll(ACTION_WORDS);
        if (targetNames != null) {
            strips.addAll(targetNames);
        }
        for (String token : strips) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            residual = residual.replace(token, "");
        }
        int meaningful = 0;
        for (int i = 0; i < residual.length(); i++) {
            char ch = residual.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                meaningful++;
            }
        }
        return meaningful <= ANAPHORIC_RESIDUAL_MAX;
    }

    private static boolean startsWithAny(String text, List<String> markers) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && !marker.isEmpty() && text.startsWith(marker)) {
                return true;
            }
        }
        return false;
    }
}
