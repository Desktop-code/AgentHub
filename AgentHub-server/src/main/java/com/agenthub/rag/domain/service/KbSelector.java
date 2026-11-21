package com.agenthub.rag.domain.service;

import com.agenthub.rag.domain.model.RagKnowledgeBase;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 检索前的「按描述选库」。所属域：rag 的 domain。
 * <p>
 * 为什么需要：一个 Agent 可绑多个库，但绝大多数问句只与其中一两个相关。
 * 无差别全搜既浪费向量召回与精排配额，也会把无关库的碎片挤进上下文。
 * <p>
 * 能力来源刻意选<b>零出站</b>的轻量文本匹配（字符二元组覆盖率 + Dice），不新接改写模型、
 * 不改入口意图识别、不让模型在工具里点名库 id：
 * <ul>
 *   <li>问句与各库 description 做规范化（去标点空白、转小写）后比较；</li>
 *   <li>score 取「问句二元组被描述覆盖的比例」与「Dice 相似度」的较大者，
 *       长描述不会因为分母变大而把短问句的相关性稀释掉；</li>
 *   <li>常见疑问二元组（怎么/什么/如何…）先从问句里剔除，避免「怎么」两字就让所有库都算相关。</li>
 * </ul>
 * <p>
 * 兜底（都必须走，避免一次都搜不到）：
 * <ol>
 *   <li>只绑了一个库：不做筛选，直接用它；</li>
 *   <li>所有绑定库描述都为空：按当前绑定范围全搜；</li>
 *   <li>有描述但一个都不像：仍按当前绑定范围全搜。</li>
 * </ol>
 * 空描述<b>永远不被当作相关</b>：它既不能得分，也不会被单独选中。
 * <p>
 * 纯静态、无依赖、不发出站调用，可直接单测；调用方必须传入已做过权限过滤的库列表。
 */
public final class KbSelector {

    /** 选中阈值：问句至少有 1/4 的二元组被描述覆盖才算相关（0~1） */
    public static final double MIN_SCORE = 0.25d;

    /** 疑问/语气类二元组：剔除后能让「怎么/什么」不再把所有库拉成相关 */
    private static final Set<String> STOP_GRAMS = Set.of(
            "怎么", "什么", "如何", "哪些", "哪个", "是否", "可以", "需要", "请问", "一下",
            "为什", "么样", "多少", "哪里", "有没", "没有", "我们", "你们", "这个", "那个");

    /** 选择结果的原因码，用于 Trace 展示与排障 */
    public enum Outcome {
        /** 只绑了一个库，无需筛选 */
        SINGLE_KB("single_kb"),
        /** 命中了部分库，只搜这些 */
        MATCHED("matched"),
        /** 绑定库描述全为空，按绑定范围全搜 */
        NO_DESCRIPTION("no_description"),
        /** 有描述但都不像，按绑定范围全搜 */
        NO_MATCH("no_match");

        private final String code;

        Outcome(String code) {
            this.code = code;
        }

        /**
         * @return 机器可读原因码
         */
        public String code() {
            return code;
        }
    }

    /**
     * 一次选库的结果。
     *
     * @param selected      实际要检索的库；永不为空（调用方已保证入参非空）
     * @param matchedIds    被判为相关的库 id；兜底或未筛选时为空
     * @param describedCount 绑定的库里有描述的个数，用于区分「没配描述」与「配了但不相关」
     * @param outcome       原因码
     */
    public record Selection(List<RagKnowledgeBase> selected, List<Long> matchedIds,
                            int describedCount, Outcome outcome) {
    }

    private KbSelector() {
    }

    /**
     * 按问句与库描述挑选本次要检索的库。
     *
     * @param query 检索句；可空，空则直接全搜
     * @param kbs   已通过权限过滤的绑定库；可空
     * @return 选择结果；入参为空时返回空选择
     */
    public static Selection select(String query, List<RagKnowledgeBase> kbs) {
        List<RagKnowledgeBase> all = kbs == null ? List.of() : kbs;
        if (all.isEmpty()) {
            return new Selection(List.of(), List.of(), 0, Outcome.NO_MATCH);
        }
        // 单库没有「选谁」的问题，不做筛选也不额外打分
        if (all.size() == 1) {
            int described = isDescribed(all.get(0)) ? 1 : 0;
            return new Selection(all, List.of(), described, Outcome.SINGLE_KB);
        }
        if (query == null || query.isBlank()) {
            return new Selection(all, List.of(), countDescribed(all), Outcome.NO_MATCH);
        }
        int described = countDescribed(all);
        // 全都没描述：无从判断相关性，退回当前绑定范围（空描述不得被当作相关）
        if (described == 0) {
            return new Selection(all, List.of(), 0, Outcome.NO_DESCRIPTION);
        }
        List<RagKnowledgeBase> matched = new ArrayList<>();
        List<Long> matchedIds = new ArrayList<>();
        for (RagKnowledgeBase kb : all) {
            if (kb == null || !isDescribed(kb)) {
                continue;
            }
            if (score(query, kb.getDescription()) >= MIN_SCORE) {
                matched.add(kb);
                if (kb.getId() != null) {
                    matchedIds.add(kb.getId());
                }
            }
        }
        // 一个都不像：宁可多搜也不要出现「有库却一条都查不到」
        if (matched.isEmpty()) {
            return new Selection(all, List.of(), described, Outcome.NO_MATCH);
        }
        return new Selection(matched, matchedIds, described, Outcome.MATCHED);
    }

    /**
     * 问句与描述的匹配分（0~1）。空描述恒为 0。
     * <p>
     * 描述通常是「退货政策、换货流程」这样的关键词串，因此先按标点切成词条逐个比：
     * 取「词条被问句覆盖的比例」最大值，避免长描述把单个强相关词稀释成低分。
     * 再叠加整句包含与全局 Dice 作为补充信号。
     *
     * @param query       检索句
     * @param description 知识库描述
     * @return 0~1；越大越相关
     */
    static double score(String query, String description) {
        String q = normalize(query);
        String d = normalize(description);
        if (q.isEmpty() || d.isEmpty()) {
            return 0d;
        }
        // 整句被包含是最强信号（描述往往就是「退货政策、换货流程」这样的关键词串）
        if (d.contains(q)) {
            return 1d;
        }
        Set<String> queryGrams = meaningfulGrams(q);
        if (queryGrams.isEmpty()) {
            return 0d;
        }
        // 词条级覆盖：某个描述词条的大部分二元组都出现在问句里，就是强相关
        // 注意用原始描述切词：规范化会抹掉分隔符，切不出「退货政策 / 换货流程」两条
        double bestTerm = 0d;
        for (String term : terms(description)) {
            Set<String> termGrams = bigrams(term);
            if (termGrams.isEmpty()) {
                continue;
            }
            int hit = 0;
            for (String gram : termGrams) {
                if (queryGrams.contains(gram)) {
                    hit++;
                }
            }
            if (hit == 0) {
                continue;
            }
            bestTerm = Math.max(bestTerm, hit / (double) termGrams.size());
        }
        Set<String> docGrams = bigrams(d);
        int overlap = 0;
        for (String gram : queryGrams) {
            if (docGrams.contains(gram)) {
                overlap++;
            }
        }
        if (overlap == 0) {
            return bestTerm;
        }
        double coverage = overlap / (double) queryGrams.size();
        double dice = 2.0d * overlap / (queryGrams.size() + docGrams.size());
        return Math.max(bestTerm, Math.max(coverage, dice));
    }

    /**
     * 把描述按非字母数字切成词条；单字词条无区分度，直接丢弃。
     * <p>
     * 入参应已规范化（只含字母数字），因此这里只需处理小写字母与数字的连续段。
     * 由于 normalize 已抹掉分隔符，切词改为按原始描述进行，以保证「退货政策、换货流程」被切成两条。
     */
    private static List<String> terms(String description) {
        List<String> out = new ArrayList<>();
        if (description == null) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < description.length(); i++) {
            char ch = description.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                current.append(Character.toLowerCase(ch));
            } else if (current.length() > 0) {
                if (current.length() >= 2) {
                    out.add(current.toString());
                }
                current.setLength(0);
            }
        }
        if (current.length() >= 2) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * 去掉标点、空白并转小写，保证中英文混排时二元组可比。
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /**
     * 相邻字符二元组。
     */
    private static Set<String> bigrams(String text) {
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 1 < text.length(); i++) {
            grams.add(text.substring(i, i + 2));
        }
        return grams;
    }

    /**
     * 问句二元组，剔除疑问/语气类后剩下的才参与打分；全被剔除时回退原始二元组。
     */
    private static Set<String> meaningfulGrams(String text) {
        Set<String> raw = bigrams(text);
        Set<String> kept = new LinkedHashSet<>();
        for (String gram : raw) {
            if (!STOP_GRAMS.contains(gram)) {
                kept.add(gram);
            }
        }
        return kept.isEmpty() ? raw : kept;
    }

    private static boolean isDescribed(RagKnowledgeBase kb) {
        return kb != null && kb.getDescription() != null && !kb.getDescription().isBlank();
    }

    private static int countDescribed(List<RagKnowledgeBase> kbs) {
        int count = 0;
        for (RagKnowledgeBase kb : kbs) {
            if (isDescribed(kb)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 供日志与 Trace 使用的一句话说明。
     *
     * @param selection 选择结果
     * @return 人话说明
     */
    public static String describe(Selection selection) {
        if (selection == null) {
            return "";
        }
        return switch (selection.outcome()) {
            case SINGLE_KB -> "只绑定 1 个知识库，直接检索";
            case MATCHED -> "按描述选中 " + selection.matchedIds().size() + " 个知识库："
                    + selection.matchedIds();
            case NO_DESCRIPTION -> "绑定库均未填写描述，按绑定范围检索（未按描述筛选）";
            case NO_MATCH -> "问句与各库描述均不匹配，按绑定范围检索";
        };
    }

    /**
     * 供单测断言阈值与原因码稳定。
     *
     * @param raw 原始描述
     * @return 规范化后的文本
     */
    static String normalizeForTest(String raw) {
        return normalize(raw).toLowerCase(Locale.ROOT);
    }
}
