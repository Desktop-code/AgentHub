package com.agenthub.runtime.domain.service;

/**
 * 用户侧提示词的分段切分与计量。所属域：runtime。
 * <p>
 * 为什么需要它：用户消息不是一段话，而是「【会话摘要】+【最近对话】+【短期记忆】+【当前用户】」拼出来的。
 * 只看总字符数无法回答「这轮 token 花在摘要还是近轮上」，面板也就说不清 P1 到底省在哪。
 * 本类按固定标记切段，给出各段字符数与估算 token。
 * <p>
 * 标记口径必须与 {@code ContextWindowFacade#renderUser} 完全一致；两处任何一个改了，另一个必须同步，
 * 否则统计会静默变成 0。找不到某段标记时该段为 0，不抛异常——渲染侧可能没启用该段。
 * <p>
 * 去重判据不在这里：那是 memory 域的业务判断（{@code TurnDeduplicator}），
 * 本类只做「已经拼好的文本怎么切段」。
 * <p>
 * 线程安全：无状态，全部静态纯函数。
 */
public final class UserPromptSections {

    /** 会话摘要段标记，与渲染侧一致 */
    public static final String SUMMARY_MARK = "【会话摘要】";
    /** 最近对话段标记 */
    public static final String RECENT_MARK = "【最近对话】";
    /** 短期记忆段标记 */
    public static final String SHORT_MARK = "【短期记忆】";
    /** 当前用户段标记 */
    public static final String CURRENT_MARK = "【当前用户】";

    private UserPromptSections() {
    }

    /**
     * 按标记切出各段字符数。
     * <p>
     * 用「标记到下一个标记」而不是「按行解析」：段内正文本身可能含换行与列表，
     * 只有标记才是可靠边界。
     *
     * @param userText 完整用户侧正文，可空
     * @return 各段字符数；空输入时全为 0
     */
    public static Parts split(String userText) {
        String text = userText == null ? "" : userText;
        int summaryAt = text.indexOf(SUMMARY_MARK);
        int recentAt = text.indexOf(RECENT_MARK);
        int shortAt = text.indexOf(SHORT_MARK);
        int currentAt = text.indexOf(CURRENT_MARK);
        int summary = summaryAt < 0 ? 0 : spanLength(text, summaryAt, nextMark(summaryAt, recentAt, shortAt, currentAt));
        int recent = recentAt < 0 ? 0 : spanLength(text, recentAt, nextMark(recentAt, summaryAt, shortAt, currentAt));
        int shorts = shortAt < 0 ? 0 : spanLength(text, shortAt, nextMark(shortAt, summaryAt, recentAt, currentAt));
        int current = currentAt < 0 ? 0 : text.length() - currentAt;
        return new Parts(text.length(), summary, recent, shorts, current);
    }

    /**
     * 找出排在当前位置之后、距离最近的那个标记位置。
     *
     * @param self  当前位置
     * @param peers 其它标记位置，未命中的传 -1
     * @return 最近的后续标记位置；没有则返回 -1（表示段落到文末）
     */
    private static int nextMark(int self, int... peers) {
        int best = -1;
        for (int peer : peers) {
            if (peer > self && (best < 0 || peer < best)) {
                best = peer;
            }
        }
        return best;
    }

    /**
     * @param text 全文
     * @param from 段起点（含标记）
     * @param to   段终点；-1 表示到文末
     * @return 该段字符数
     */
    private static int spanLength(String text, int from, int to) {
        return (to < 0 ? text.length() : to) - from;
    }

    /**
     * 用户侧各段字符数。所属域：runtime。
     *
     * @param total   完整用户侧正文长度
     * @param summary 【会话摘要】段长度（含标记）
     * @param recent  【最近对话】段长度
     * @param shorts  【短期记忆】段长度
     * @param current 【当前用户】段长度
     */
    public record Parts(int total, int summary, int recent, int shorts, int current) {

        /**
         * @return 全 0 的空统计
         */
        public static Parts empty() {
            return new Parts(0, 0, 0, 0, 0);
        }
    }
}
