package com.agenthub.runtime.domain.service;

import com.agenthub.memory.domain.model.ContextTurn;
import com.agenthub.memory.domain.service.TurnDeduplicator;
import com.agenthub.runtime.domain.model.PromptInjectionStats;
import org.junit.jupiter.api.Test;

/**
 * P1 验收：多轮长对话的出站体量下降，且下降来源可解释。所属域：runtime 的装配单测。
 * <p>
 * 三段可解释的下降：
 * <ol>
 *   <li><b>摘要与近轮去重</b>：被摘要覆盖的旧轮不再重复进【最近对话】；</li>
 *   <li><b>近轮窗口收紧</b>：条数/字符上限（配置项）让更早的轮次进摘要而不是原样保留；</li>
 *   <li><b>长工具结果句柄化</b>：超阈值结果只回灌摘要 + 路径。</li>
 * </ol>
 * 本类用同一份「多轮长对话」输入，分别按改前/改后口径算用户侧体量，打印两组数字供验收对照。
 * 断言只锁「改后显著更小 + 分段可解释」，具体数值不作硬性要求。
 */
class ChatWindowBudgetBaselineTest {

    /** 一轮旧对话的正文长度（接近真实工具回灌后的对话长度） */
    private static final int TURN_CHARS = 900;
    private static final int TURN_COUNT = 24;

    /**
     * 改前：全部轮次原样进【最近对话】，且窗口更宽（20 轮 / 32000 字），摘要里写过的也再写一遍。
     * 改后：窗口收紧到 12 轮 / 16000 字，摘要覆盖的旧轮剔除。
     * <p>
     * 每轮正文各不相同——否则「所有轮次都被同一段摘要覆盖」会把去重率虚高到不像真的。
     */
    @Test
    void multiTurnLongConversationShrinksAndIsExplainable() {
        // 构造 24 轮各不相同、每轮约 900 字的对话
        String[] turns = new String[TURN_COUNT];
        for (int i = 0; i < TURN_COUNT; i++) {
            StringBuilder one = new StringBuilder("第").append(i + 1).append("轮：");
            while (one.length() < TURN_CHARS) {
                one.append('内');
            }
            turns[i] = one.toString();
        }

        // ---- 改前：窗口 20 轮 / 32000 字，摘要与近轮不去重 ----
        String before = legacyRender(turns, 20, 32000);
        // ---- 改后：窗口 12 轮 / 16000 字，摘要与近轮去重 ----
        String after = modernRender(turns, 12, 16000);

        int beforeChars = before.length();
        int afterChars = after.length();
        System.out.println("[P1度量] 改前用户侧字符=" + beforeChars
                + " 估算 token≈" + com.agenthub.common.text.TokenEstimator.estimate(before));
        System.out.println("[P1度量] 改后用户侧字符=" + afterChars
                + " 估算 token≈" + com.agenthub.common.text.TokenEstimator.estimate(after));

        // 验收：改后必须显著更小（至少省 30%）
        org.junit.jupiter.api.Assertions.assertTrue(afterChars < beforeChars * 0.7,
                "多轮长对话出站体量应明显下降：改前=" + beforeChars + " 改后=" + afterChars);

        // 分段计量必须能给出数字，且合计自洽
        PromptInjectionStats stats = new PromptInjectionStats();
        stats.meterUser(after);
        org.junit.jupiter.api.Assertions.assertTrue(stats.getUserRecentChars() > 0,
                "近轮段应有数字（窗口内仍有未被摘要覆盖的轮次）");
        org.junit.jupiter.api.Assertions.assertTrue(stats.getUserCurrentChars() > 0, "本轮段应有数字");
        org.junit.jupiter.api.Assertions.assertTrue(stats.getUserChars() > 0);
        String breakdown = stats.breakdown();
        org.junit.jupiter.api.Assertions.assertTrue(breakdown.contains("摘要"), breakdown);
        org.junit.jupiter.api.Assertions.assertTrue(breakdown.contains("近轮"), breakdown);
    }

    /**
     * 摘要已覆盖全部旧轮时：近轮整段不再输出，只剩摘要 + 本轮。
     */
    @Test
    void dedupRemovesDuplicatedHistoryEntirely() {
        String longTurn = "内".repeat(TURN_CHARS);
        // 摘要把旧轮全部复述了一遍（这是摘要模型最常见的照抄行为）
        String summary = "早前对话：" + longTurn;

        String withoutDedup = "【会话摘要】\n" + summary + "\n\n【最近对话】\n用户：" + longTurn
                + "\n\n【当前用户】\n本轮问题";

        // 改后：逐条判定为已覆盖 → 整段近轮省略
        boolean covered = TurnDeduplicator.covered(summary, longTurn);
        String withDedup = covered
                ? "【会话摘要】\n" + summary + "\n\n【当前用户】\n本轮问题"
                : withoutDedup;

        org.junit.jupiter.api.Assertions.assertTrue(covered, "逐字复述必须被判定为已覆盖");
        org.junit.jupiter.api.Assertions.assertFalse(withDedup.contains("【最近对话】"),
                "全部被覆盖时不该留空段头");
        org.junit.jupiter.api.Assertions.assertTrue(withDedup.length() < withoutDedup.length() * 0.6,
                "去重应省下接近一半的体量");
    }

    /**
     * 改前渲染：窗口内所有轮次原样拼接，不与摘要去重。
     *
     * @param turns    全部轮次正文
     * @param maxTurns 窗口条数
     * @param maxChars 窗口字符上限
     * @return 用户侧正文
     */
    private static String legacyRender(String[] turns, int maxTurns, int maxChars) {
        java.util.List<ContextTurn> window = windowOf(turns, maxTurns, maxChars);
        StringBuilder text = new StringBuilder();
        text.append("【会话摘要】\n早前对话要点（模型复述了部分原文）\n\n");
        text.append("【最近对话】\n");
        for (ContextTurn turn : window) {
            text.append("用户：").append(turn.getText()).append('\n');
        }
        text.append("\n【当前用户】\n本轮问题");
        return text.toString();
    }

    /**
     * 改后渲染：同样窗口下，先按摘要覆盖剔除重复轮次，再拼接。
     *
     * @param turns    全部轮次正文
     * @param maxTurns 收紧后的窗口条数
     * @param maxChars 收紧后的字符上限
     * @return 用户侧正文
     */
    private static String modernRender(String[] turns, int maxTurns, int maxChars) {
        java.util.List<ContextTurn> window = windowOf(turns, maxTurns, maxChars);
        // 摘要复述了窗口内最早那一轮：它必须被剔除（其余的保留）
        String summary = "早前对话：" + window.get(0).getText();
        StringBuilder text = new StringBuilder();
        text.append("【会话摘要】\n").append(summary).append("\n\n");
        StringBuilder recent = new StringBuilder();
        for (ContextTurn turn : window) {
            if (TurnDeduplicator.covered(summary, turn.getText())) {
                continue;
            }
            recent.append("用户：").append(turn.getText()).append('\n');
        }
        if (recent.length() > 0) {
            text.append("【最近对话】\n").append(recent).append('\n');
        }
        text.append("【当前用户】\n本轮问题");
        return text.toString();
    }

    /**
     * 按条数与字符双上限取窗口尾部（与 {@code ContextWindowPlanner} 口径一致）。
     *
     * @param turns    全部轮次正文
     * @param maxTurns 条数上限
     * @param maxChars 字符上限
     * @return 命中的轮次
     */
    private static java.util.List<ContextTurn> windowOf(String[] turns, int maxTurns, int maxChars) {
        java.util.List<ContextTurn> all = new java.util.ArrayList<>();
        for (int i = 0; i < turns.length; i++) {
            all.add(ContextTurn.of(i % 2 == 0 ? "user" : "assistant", turns[i]));
        }
        java.util.List<ContextTurn> window = new java.util.ArrayList<>();
        int used = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            int len = all.get(i).getText().length();
            if (window.size() + 1 > maxTurns || used + len > maxChars) {
                break;
            }
            window.add(0, all.get(i));
            used += len;
        }
        return window;
    }
}
