package com.agenthub.runtime.application.router;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作台入口切问的纯单测。所属域：runtime。
 *
 * <p>只验证「哪些是另一问」，不涉及判路由与执行：一次消息里两个不同问题必须切开，
 * 同一件事的多块交付（整理要点 + 起草报告）不能切，单问与闲聊保持一条。
 * 不连库、不调模型。
 */
class EntryQuestionSplitterTest {

    private static final List<String> NAMES = List.of("企业综合台", "制度要点与报告", "接待助手");

    /** 两条消息：点名方案的一问 + 明确要求工作流再跑一次。 */
    @Test
    void secondSentenceAskingWorkflowIsSplitOut() {
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(
                "请企业综合台根据公司制度整理知识要点，并起草一份报告。同时也按工作流跑一次", NAMES);

        assertEquals(2, questions.size());
        assertEquals("请企业综合台根据公司制度整理知识要点，并起草一份报告", questions.get(0).text());
        assertEquals("同时也按工作流跑一次", questions.get(1).text());
        // 第二问自身没有内容：子问题沿用第一问，否则工作流会拿到一句空请求
        assertEquals(questions.get(0).subQuery(), questions.get(1).subQuery());
    }

    /** 同一句里用逗号引出的第二件事，也必须切开。 */
    @Test
    void commaIntroducedSecondQuestionIsSplit() {
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(
                "年假制度是怎么规定的，再请工作流出报告", NAMES);

        assertEquals(2, questions.size());
        assertEquals("年假制度是怎么规定的", questions.get(0).text());
        assertEquals("再请工作流出报告", questions.get(1).text());
    }

    /** 同一件事的两块交付：整句仍是一问，交给覆盖它的方案。 */
    @Test
    void twoDeliverablesOfOneTaskStayOneQuestion() {
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(
                "根据公司制度整理知识要点，并起草一份报告", NAMES);

        assertEquals(1, questions.size());
        assertEquals("根据公司制度整理知识要点，并起草一份报告", questions.get(0).text());
    }

    /** 名字里带方案名的单问：不能因为出现「企业综合台」就切成两问。 */
    @Test
    void namedSchemeSingleQuestionStaysOne() {
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(
                "请企业综合台根据公司制度整理知识要点，并起草一份报告", NAMES);

        assertEquals(1, questions.size());
    }

    /** 单问制度咨询与寒暄：与改造前一致。 */
    @Test
    void singleQuestionAndGreetingStayOne() {
        assertEquals(1, EntryQuestionSplitter.split("年假制度是怎么规定的", NAMES).size());
        assertEquals(1, EntryQuestionSplitter.split("你好", NAMES).size());
        assertTrue(EntryQuestionSplitter.split("你好", NAMES).get(0).subQuery().contains("你好"));
    }

    /**
     * 同一句用分号接「另外」点名多个方案：每一问各自成段，不能粘成一条。
     *
     * <p>这是入口漏派的那一种写法，不是给某一句开的特例：分号和逗号一样只提供小句边界，
     * 真正切开的仍是句首的强引子「另外」。四个方案名都要留在自己的那一问里。
     */
    @Test
    void semicolonIntroducedWorkshopsSplitIntoFourQuestions() {
        List<String> workshops = List.of("文件车间", "数据车间", "合规车间", "文书车间");
        String text = "请文件车间整理操作空间并比对两份原稿；另外请数据车间汇总金额并做名单去重；"
                + "另外请合规车间扫描敏感词并推算到期日；另外请文书车间生成编号并套模板出成稿";

        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(text, workshops);

        assertEquals(4, questions.size());
        assertEquals("请文件车间整理操作空间并比对两份原稿", questions.get(0).text());
        assertEquals("另外请数据车间汇总金额并做名单去重", questions.get(1).text());
        assertEquals("另外请合规车间扫描敏感词并推算到期日", questions.get(2).text());
        assertEquals("另外请文书车间生成编号并套模板出成稿", questions.get(3).text());
        // 每一问自己有任务，子问题不得沿用上一问，否则后三个车间会拿到文件车间的原文
        for (EntryQuestionSplitter.Question question : questions) {
            assertEquals(question.text(), question.subQuery());
        }
        assertTrue(questions.get(0).text().contains("文件车间") && !questions.get(0).text().contains("数据车间"));
        assertTrue(questions.get(1).text().contains("数据车间") && !questions.get(1).text().contains("合规车间"));
        assertTrue(questions.get(2).text().contains("合规车间") && !questions.get(2).text().contains("文书车间"));
        assertTrue(questions.get(3).text().contains("文书车间") && !questions.get(3).text().contains("文件车间"));
    }

    /** 半角分号同样是小句边界，强引子在段首就切开。 */
    @Test
    void asciiSemicolonAlsoStartsAnotherQuestion() {
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(
                "请文件车间整理清单;另外请数据车间做汇总", List.of("文件车间", "数据车间"));

        assertEquals(2, questions.size());
        assertEquals("请文件车间整理清单", questions.get(0).text());
        assertEquals("另外请数据车间做汇总", questions.get(1).text());
    }

    /** 逗号接「另外」本来就能切；分号改动不能把它切坏。 */
    @Test
    void commaIntroducedLingwaiStillSplits() {
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(
                "请文件车间整理清单，另外请数据车间做汇总", List.of("文件车间", "数据车间"));

        assertEquals(2, questions.size());
        assertEquals("请文件车间整理清单", questions.get(0).text());
        assertEquals("另外请数据车间做汇总", questions.get(1).text());
    }

    /** 分号不是句号：没有强引子、只是同一件事的下一块交付时不切，原文也不改标点。 */
    @Test
    void semicolonWithoutNewQuestionMarkerStaysOne() {
        String text = "请文件车间整理操作空间清单；并写成结果文件";
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(
                text, List.of("文件车间", "数据车间", "合规车间", "文书车间"));

        assertEquals(1, questions.size());
        assertEquals(text, questions.get(0).text());
    }

    /** 只是补充同一件事的要求（没有目标线索、也不是强引子）：不切。 */
    @Test
    void plainContinuationSentenceStaysOne() {
        List<EntryQuestionSplitter.Question> questions = EntryQuestionSplitter.split(
                "请根据制度整理要点。报告要正式一些", NAMES);

        assertEquals(1, questions.size());
    }
}
