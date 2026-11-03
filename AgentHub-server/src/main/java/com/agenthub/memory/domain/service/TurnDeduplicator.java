package com.agenthub.memory.domain.service;

/**
 * 摘要与近轮的去重判据。所属域：memory。
 * <p>
 * <b>为什么需要它</b>：滚动摘要的输入就是被裁掉的旧轮。模型常把旧轮原文照抄进摘要，
 * 于是同一段话既出现在【会话摘要】里，又出现在【最近对话】里——同一份内容付两次 token，
 * 还会让模型误以为那件事发生了两遍。
 * <p>
 * 判据刻意选「去空白后子串包含」而不是相似度：
 * <ul>
 *   <li>摘要模型对原文的复制是<b>逐字</b>的，子串判断就能覆盖绝大多数情况；</li>
 *   <li>相似度阈值一旦引入，就要回答「0.8 算不算重复」这种没有客观答案的问题，
 *       误删正常上下文的代价远高于多留一段。</li>
 * </ul>
 * 太短的片段（如「好的」）不判重：那是正常对话节奏，删掉反而丢信息。
 * <p>
 * 线程安全：无状态，全部静态纯函数。
 */
public final class TurnDeduplicator {

    /** 判重前的最短长度：低于它一律视为「不能判重」，避免误删正常短句 */
    private static final int MIN_DEDUP_CHARS = 24;

    private TurnDeduplicator() {
    }

    /**
     * 单条近轮是否已被摘要覆盖。
     *
     * @param summary 摘要正文，可空
     * @param turn    单条近轮正文，可空
     * @return true 表示该条不必再进【最近对话】
     */
    public static boolean covered(String summary, String turn) {
        return containsNormalized(summary, turn);
    }

    /**
     * 整段近轮是否已被摘要覆盖。
     *
     * @param summary 摘要正文，可空
     * @param recent  拼接后的近轮正文，可空
     * @return true 表示整段都可以省略
     */
    public static boolean blockCovered(String summary, String recent) {
        return containsNormalized(summary, recent);
    }

    /**
     * 去掉所有空白再比对：摘要模型会重排换行与空格，逐字比对几乎必然不相等。
     *
     * @param summary 摘要正文
     * @param body    待判定的正文
     * @return true 表示 summary 包含 body（按去空白后的文本）
     */
    private static boolean containsNormalized(String summary, String body) {
        String digest = normalize(summary);
        String candidate = normalize(body);
        if (digest.isEmpty() || candidate.length() < MIN_DEDUP_CHARS) {
            return false;
        }
        return digest.contains(candidate);
    }

    /**
     * @param raw 原文，可空
     * @return 去掉所有空白后的文本
     */
    private static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", "");
    }
}
