package com.agenthub.rag.domain.service;

import com.agenthub.rag.domain.model.RagKnowledgeBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按描述选库。所属域：rag 的 domain。
 * <p>
 * 全部纯函数、零出站。覆盖点：
 * <ol>
 *   <li>单库不筛选；</li>
 *   <li>问句与某个库描述相关时只搜该库；</li>
 *   <li>描述全为空 / 全不相关时按绑定范围兜底全搜；</li>
 *   <li>空描述永远不被当成相关。</li>
 * </ol>
 */
class KbSelectorTest {

    /**
     * 只绑一个库：直接用它，不走打分。
     */
    @Test
    void singleKbIsUsedAsIs() {
        RagKnowledgeBase kb = kb(1L, "退货政策、换货流程");

        KbSelector.Selection selection = KbSelector.select("年假怎么请", List.of(kb));

        assertEquals(1, selection.selected().size());
        assertEquals(KbSelector.Outcome.SINGLE_KB, selection.outcome());
    }

    /**
     * 问句命中其中一个库的描述：只搜这一个，另一个不进召回。
     */
    @Test
    void selectsOnlyMatchingKb() {
        RagKnowledgeBase refund = kb(1L, "退货政策、换货流程、七天无理由");
        RagKnowledgeBase leave = kb(2L, "年假、病假、调休、考勤制度");

        KbSelector.Selection selection = KbSelector.select("退货需要什么条件", List.of(refund, leave));

        assertEquals(KbSelector.Outcome.MATCHED, selection.outcome());
        assertEquals(1, selection.selected().size());
        assertEquals(1L, selection.selected().get(0).getId());
        assertEquals(List.of(1L), selection.matchedIds());
    }

    /**
     * 描述全为空：不能因为「没描述」就一条都不搜，必须按绑定范围兜底。
     */
    @Test
    void fallsBackWhenAllDescriptionsBlank() {
        RagKnowledgeBase a = kb(1L, "");
        RagKnowledgeBase b = kb(2L, "   ");

        KbSelector.Selection selection = KbSelector.select("退货政策", List.of(a, b));

        assertEquals(KbSelector.Outcome.NO_DESCRIPTION, selection.outcome());
        assertEquals(2, selection.selected().size());
        assertTrue(selection.matchedIds().isEmpty());
    }

    /**
     * 有描述但都不像：同样按绑定范围兜底，避免「有库却查不到」。
     * 同时证明空描述不会被当成相关（否则会把空描述库选进来）。
     */
    @Test
    void fallsBackWhenNothingMatches() {
        RagKnowledgeBase described = kb(1L, "年假、病假、调休");
        RagKnowledgeBase blank = kb(2L, "");

        KbSelector.Selection selection = KbSelector.select("跨境清关税率", List.of(described, blank));

        assertEquals(KbSelector.Outcome.NO_MATCH, selection.outcome());
        assertEquals(2, selection.selected().size());
        assertEquals(1, selection.describedCount());
    }

    /**
     * 疑问词不应把无关库拉成相关：「怎么办」这类二元组必须先剔除。
     */
    @Test
    void questionWordsDoNotMatchEveryKb() {
        RagKnowledgeBase leave = kb(1L, "年假、病假、调休");
        RagKnowledgeBase refund = kb(2L, "退货、换货、退款");

        KbSelector.Selection selection = KbSelector.select("这个怎么办", List.of(leave, refund));

        // 剔除疑问二元组后没有可用信号 → 不匹配任何库，按兜底全搜
        assertEquals(KbSelector.Outcome.NO_MATCH, selection.outcome());
        assertEquals(2, selection.selected().size());
    }

    /**
     * 打分单调：描述越贴近问句分越高；空描述恒为 0。
     */
    @Test
    void scoreIsZeroForBlankDescription() {
        assertEquals(0d, KbSelector.score("退货政策", ""), 1e-9);
        assertEquals(0d, KbSelector.score("退货政策", null), 1e-9);
        assertTrue(KbSelector.score("退货政策", "退货政策说明") >= KbSelector.MIN_SCORE);
        assertFalse(KbSelector.score("退货政策", "年假调休") >= KbSelector.MIN_SCORE);
    }

    /**
     * @param id          主键
     * @param description 描述
     * @return 知识库
     */
    private static RagKnowledgeBase kb(Long id, String description) {
        RagKnowledgeBase kb = new RagKnowledgeBase();
        kb.setId(id);
        kb.setName("库" + id);
        kb.setDescription(description);
        return kb;
    }
}
