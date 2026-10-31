package com.agenthub.memory.domain.service;

import com.agenthub.memory.domain.model.ContextTurn;

import java.util.ArrayList;
import java.util.List;

/**
 * 判断窗口是否触顶，并切出要摘要的旧段。所属域：memory。
 * 轮数或字符任一超限即触顶。只剩当前一轮时，超长部分进旧段，尾部留下仍能放进预算的后缀。
 */
public final class ContextWindowPlanner {

    private ContextWindowPlanner() {
    }

    /**
     * @param turns    当前窗口，含刚追加的一轮
     * @param maxTurns 最大轮数
     * @param maxChars 最大字符
     * @return head 需要摘要；tail 留在窗口。head 为空表示未触顶
     */
    public static Decision decide(List<ContextTurn> turns, int maxTurns, int maxChars) {
        List<ContextTurn> source = turns == null ? List.of() : turns;
        int turnsCap = Math.max(maxTurns, 1);
        int charsCap = Math.max(maxChars, 1);
        if (source.isEmpty() || (source.size() <= turnsCap && chars(source) <= charsCap)) {
            return Decision.keep(source);
        }
        List<ContextTurn> tail = new ArrayList<>();
        int used = 0;
        int index = source.size();
        for (int i = source.size() - 1; i >= 0; i--) {
            ContextTurn turn = source.get(i);
            int len = length(turn);
            if (tail.size() + 1 > turnsCap || used + len > charsCap) {
                break;
            }
            tail.add(0, turn);
            used += len;
            index = i;
        }
        // 最后一轮自己就超字符预算：旧段带走被裁掉的前缀，窗口留下后缀
        if (tail.isEmpty() && !source.isEmpty()) {
            ContextTurn last = source.get(source.size() - 1);
            String text = last.getText() == null ? "" : last.getText();
            int keep = Math.min(text.length(), charsCap);
            String suffix = text.substring(text.length() - keep);
            String prefix = text.substring(0, text.length() - keep);
            List<ContextTurn> head = new ArrayList<>(source.subList(0, source.size() - 1));
            if (!prefix.isEmpty()) {
                head.add(ContextTurn.of(last.getRole(), prefix));
            }
            return new Decision(head, List.of(ContextTurn.of(last.getRole(), suffix)));
        }
        return new Decision(new ArrayList<>(source.subList(0, index)), tail);
    }

    private static int chars(List<ContextTurn> turns) {
        int sum = 0;
        for (ContextTurn turn : turns) {
            sum += length(turn);
        }
        return sum;
    }

    private static int length(ContextTurn turn) {
        return turn == null || turn.getText() == null ? 0 : turn.getText().length();
    }

    /**
     * 一次切分结果。head 为空表示不用摘要。
     *
     * @param head 即将移出窗口的旧段
     * @param tail 留下的近轮
     */
    public record Decision(List<ContextTurn> head, List<ContextTurn> tail) {

        /**
         * @param turns 原窗口
         * @return 不摘要
         */
        public static Decision keep(List<ContextTurn> turns) {
            return new Decision(List.of(), turns);
        }

        /**
         * @return 是否必须滚动摘要
         */
        public boolean overflow() {
            return head != null && !head.isEmpty();
        }
    }
}
