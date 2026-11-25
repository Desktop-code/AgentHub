package com.agenthub.eval.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.eval.domain.model.EvalCase;
import com.agenthub.eval.domain.model.EvalCaseSpec;
import com.agenthub.eval.domain.model.EvalSuiteSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用例库 JSON 收口校验。所属域：eval。
 * <p>覆盖：classpath 平台样例可解析且字段齐全、空 key/非法输入被拦、断言结构校验。
 * 不连数据库。
 */
class EvalSpecsTest {

    private final ObjectMapper json = new ObjectMapper();

    /**
     * 平台样例必须能被解析成 ≥5 条用例，且关键字段一个不少。
     * 样例是「唯一真实存在」的用例集，它挂掉等于导入接口挂掉。
     */
    @Test
    void platformSampleParsesWithAllFields() throws Exception {
        Resource resource = new DefaultResourceLoader()
                .getResource("classpath:eval/samples/platform-smoke-suite.json");
        assertTrue(resource.exists(), "classpath 平台样例缺失");
        String text;
        try (InputStream in = resource.getInputStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        EvalSuiteSpec spec = EvalSpecs.parse(text, json);

        assertEquals("platform.smoke.rag", spec.getSuiteKey());
        assertNotNull(spec.getDefaultConfig());
        assertEquals(1, spec.getDefaultConfig().get("max_parallel").asInt());
        // OpenJudge 本刀不接，样例里必须是关的，避免后续刀误以为已通
        assertTrue(!spec.getDefaultConfig().get("openjudge").get("enabled").asBoolean());
        assertTrue(spec.getCases().size() >= 5, "样例至少 5 条用例");

        for (EvalCaseSpec one : spec.getCases()) {
            assertNotNull(one.getCaseKey());
            assertNotNull(one.getTitle());
            assertNotNull(one.getInput().get("message"));
            assertNotNull(one.getEnabled());
            assertNotNull(one.getSortOrder());
        }
        // 定稿 §5.2 的退货政策例必须在样例里，且带 blocking 断言
        EvalCaseSpec refund = spec.getCases().stream()
                .filter(one -> "rag-refund-policy-01".equals(one.getCaseKey()))
                .findFirst()
                .orElseThrow();
        assertTrue(refund.getAssertions().size() >= 4);
        assertNotNull(refund.getExpect().get("reference"));
        // 工具名对齐现网 BUILTIN：searchKnowledgeBase（禁残留 searchKB）
        assertTrue(text.contains("searchKnowledgeBase"));
        assertTrue(!text.contains("\"searchKB\"") && !text.contains("'searchKB'"),
                "样例 JSON 不得残留旧草名 searchKB");
        assertEquals("searchKnowledgeBase",
                refund.getExpect().get("expect_tools").get(0).asText());
    }

    @Test
    void blankKeysAreRejected() {
        EvalSuiteSpec spec = new EvalSuiteSpec();
        EvalCaseSpec one = new EvalCaseSpec();
        one.setCaseKey("  ");
        one.setTitle("标题");
        one.setInput(messageNode("hi"));
        spec.setCases(List.of(one));
        assertThrows(BizException.class, () -> EvalSpecs.normalize(spec));

        EvalCaseSpec noTitle = new EvalCaseSpec();
        noTitle.setCaseKey("k1");
        noTitle.setInput(messageNode("hi"));
        EvalSuiteSpec spec2 = new EvalSuiteSpec();
        spec2.setCases(List.of(noTitle));
        assertThrows(BizException.class, () -> EvalSpecs.normalize(spec2));
    }

    @Test
    void inputWithoutMessageIsRejected() {
        EvalCaseSpec one = new EvalCaseSpec();
        one.setCaseKey("k1");
        one.setTitle("标题");
        one.setInput(json.createObjectNode());
        EvalSuiteSpec spec = new EvalSuiteSpec();
        spec.setCases(List.of(one));
        BizException ex = assertThrows(BizException.class, () -> EvalSpecs.normalize(spec));
        assertTrue(ex.getMessage().contains("input.message"));
    }

    @Test
    void duplicateCaseKeyIsRejectedBeforeDb() {
        EvalSuiteSpec spec = new EvalSuiteSpec();
        spec.setCases(List.of(caseSpec("dup"), caseSpec("dup")));
        BizException ex = assertThrows(BizException.class, () -> EvalSpecs.normalize(spec));
        assertTrue(ex.getMessage().contains("case_key"));
    }

    @Test
    void inlineLlmJudgeAssertionIsRejected() {
        EvalCaseSpec one = caseSpec("k1");
        ObjectNode bad = json.createObjectNode();
        bad.put("type", "llm_judge_inline");
        one.setAssertions(json.createArrayNode().add(bad));
        EvalSuiteSpec spec = new EvalSuiteSpec();
        spec.setCases(List.of(one));
        // 定稿 §3.4：内嵌 LLM 断言禁止进 RuleScorer，导入阶段就拦下
        assertThrows(BizException.class, () -> EvalSpecs.normalize(spec));
    }

    @Test
    void brokenJsonBecomesBizException() {
        BizException bad = assertThrows(BizException.class, () -> EvalSpecs.parse("{not json", json));
        assertTrue(bad.getMessage().contains("格式不正确") || bad.getMessage().contains("JSON"));
        assertThrows(BizException.class, () -> EvalSpecs.parse("   ", json));
    }

    /** 缺 case_key 必须是可读业务错，而不是笼统「JSON 格式错误」。 */
    @Test
    void missingCaseKeyIsBusinessErrorNotFormatError() {
        String raw = """
                {"suite_key":"x.y.z","name":"n","cases":[{"title":"t","input":{"message":"hi"}}]}
                """;
        BizException ex = assertThrows(BizException.class, () -> EvalSpecs.parse(raw, json));
        assertTrue(ex.getMessage().contains("case_key"));
        assertTrue(!ex.getMessage().contains("格式不正确"));
    }

    @Test
    void suiteKeyPatternIsChecked() {
        assertThrows(BizException.class, () -> EvalSpecs.checkSuiteKeyPattern("with space"));
        assertThrows(BizException.class, () -> EvalSpecs.checkSuiteKeyPattern("a/b"));
        assertEquals("platform.smoke_1-x", EvalSpecs.checkSuiteKeyPattern("platform.smoke_1-x"));
    }

    /**
     * 行转换：JSON 块落库文本可空、sort_order 缺省按数组顺序补。
     */
    @Test
    void toCaseRowsFillsDefaultsAndSerializes() {
        EvalCaseSpec first = caseSpec("a");
        EvalCaseSpec second = caseSpec("b");
        first.setSortOrder(null);
        second.setSortOrder(null);
        EvalSuiteSpec spec = new EvalSuiteSpec();
        spec.setCases(List.of(first, second));
        List<EvalCase> rows = EvalSpecs.toCaseRows(9L, 3, spec, 7L, json);

        assertEquals(2, rows.size());
        assertEquals(9L, rows.get(0).getSuiteId());
        assertEquals(3, rows.get(0).getSuiteVersion());
        assertEquals(0, rows.get(0).getSortOrder());
        assertEquals(10, rows.get(1).getSortOrder());
        assertTrue(rows.get(0).getInputJson().contains("hi"));
        assertNull(rows.get(0).getExpectJson());
        assertEquals("[]", rows.get(0).getTagsJson());
        assertEquals(7L, rows.get(0).getCreatedBy());
    }

    private EvalCaseSpec caseSpec(String key) {
        EvalCaseSpec one = new EvalCaseSpec();
        one.setCaseKey(key);
        one.setTitle("标题 " + key);
        one.setInput(messageNode("hi"));
        return one;
    }

    private ObjectNode messageNode(String text) {
        ObjectNode input = json.createObjectNode();
        input.put("message", text);
        return input;
    }
}
