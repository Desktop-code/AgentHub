package com.agenthub.rag.application;

import com.agenthub.agent.domain.model.AgentModelBind;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.prompt.application.PromptAssembler;
import com.agenthub.prompt.domain.model.PromptAssemblyResult;
import com.agenthub.rag.domain.service.RagQueryRewriter;
import com.agenthub.spi.llm.TextCompletionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查询改写服务的两道门与失败回退。所属域：rag 的 application。
 * <p>
 * 全部替身：REWRITE 槽查询、提示词组装、文本出站都是 mock，<b>零真实出站</b>。
 * 覆盖点：
 * <ol>
 *   <li>开关只管「用不用」，模型槽才是第二道门；槽空时不改写、也不降级 CHAT；</li>
 *   <li>提示词不可用 → 记失败原因，不抛异常；</li>
 *   <li>出站报错 / 空结果 / 超长 → 一律回退原问句；</li>
 *   <li>成功时返回改写句并带上模型实例 id；</li>
 *   <li>模型输出清洗（代码围栏、包裹引号、首行、多余空白）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagQueryRewriteServiceTest {

    @Mock
    private AgentRewriteLookup lookup;
    @Mock
    private PromptAssembler prompts;
    @Mock
    private TextCompletionClient completions;

    private RagQueryRewriteService service;

    private static final RagQueryRewriter.Caller CALLER = new RagQueryRewriter.Caller(7L, 21L, 99L);

    @BeforeEach
    void setUp() {
        service = new RagQueryRewriteService(lookup, prompts, completions);
        lenient().when(prompts.assemble(any())).thenReturn(result("REWRITE 系统段"));
    }

    /**
     * REWRITE 槽为空：按跳过返回，且<b>绝不</b>去调模型（不降级 CHAT）。
     */
    @Test
    void missingRewriteSlotSkipsWithoutCallingModel() {
        when(lookup.enabledRewriteBind(7L)).thenReturn(Optional.empty());

        RagQueryRewriter.Result result = service.rewrite(CALLER, "那个退货咋弄");

        assertFalse(result.applied());
        assertEquals("rewrite_model_missing", result.reason());
        assertEquals("那个退货咋弄", result.query());
        assertNull(result.modelInstanceId());
        verify(completions, never()).complete(anyLong(), any(), anyLong(), anyLong(),
                anyString(), any(), anyString(), any(), anyInt());
    }

    /**
     * 没有 Agent：直接跳过，连槽都不查。
     */
    @Test
    void noAgentSkipsImmediately() {
        RagQueryRewriter.Result result = service.rewrite(new RagQueryRewriter.Caller(null, null, null), "问句");

        assertFalse(result.applied());
        assertEquals("no_agent", result.reason());
        verify(lookup, never()).enabledRewriteBind(anyLong());
    }

    /**
     * 空问句：跳过，不出站。
     */
    @Test
    void blankQuerySkips() {
        RagQueryRewriter.Result result = service.rewrite(CALLER, "   ");

        assertEquals("empty_query", result.reason());
        verify(completions, never()).complete(anyLong(), any(), anyLong(), anyLong(),
                anyString(), any(), anyString(), any(), anyInt());
    }

    /**
     * 提示词不可用（模板未发布 / 缺段）：记失败原因，不抛异常，检索仍能用原问句。
     */
    @Test
    void promptUnavailableFallsBackWithoutThrowing() {
        when(lookup.enabledRewriteBind(7L)).thenReturn(Optional.of(bind(88L)));
        when(prompts.assemble(any())).thenThrow(
                new BizException(ErrorCode.PROMPT_UNAVAILABLE, "用途未配置模板：REWRITE"));

        RagQueryRewriter.Result result = service.rewrite(CALLER, "退货怎么弄");

        assertFalse(result.applied());
        assertEquals("rewrite_prompt_unavailable", result.reason());
        assertEquals(88L, result.modelInstanceId());
        verify(completions, never()).complete(anyLong(), any(), anyLong(), anyLong(),
                anyString(), any(), anyString(), any(), anyInt());
    }

    /**
     * 出站报错：按模型错误回退原问句，异常类型写清楚供 Trace 区分。
     */
    @Test
    void modelErrorFallsBackToOriginal() {
        when(lookup.enabledRewriteBind(7L)).thenReturn(Optional.of(bind(88L)));
        when(completions.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), any(),
                anyString(), anyDouble(), anyInt()))
                .thenThrow(new BizException(ErrorCode.BAD_REQUEST, "模型没有返回任何内容块"));

        RagQueryRewriter.Result result = service.rewrite(CALLER, "退货怎么弄");

        assertFalse(result.applied());
        assertEquals("rewrite_model_error", result.reason());
        assertEquals("BizException", result.errorType());
        assertEquals("退货怎么弄", result.query());
    }

    /**
     * 超时：原因要能与「模型报错」区分，排障时一眼看出是等太久。
     */
    @Test
    void timeoutIsReportedDistinctly() {
        when(lookup.enabledRewriteBind(7L)).thenReturn(Optional.of(bind(88L)));
        when(completions.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), any(),
                anyString(), anyDouble(), anyInt()))
                .thenThrow(new RuntimeException("Request timed out after 60000ms"));

        RagQueryRewriter.Result result = service.rewrite(CALLER, "退货怎么弄");

        assertFalse(result.applied());
        assertEquals("rewrite_timeout", result.reason());
    }

    /**
     * 空结果（推理模型把额度全用在思考上）：记「空结果」而不是泛化成失败。
     */
    @Test
    void emptyResultFallsBackWithExplicitReason() {
        when(lookup.enabledRewriteBind(7L)).thenReturn(Optional.of(bind(88L)));
        when(completions.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), any(),
                anyString(), anyDouble(), anyInt())).thenReturn("   ");

        RagQueryRewriter.Result result = service.rewrite(CALLER, "退货怎么弄");

        assertFalse(result.applied());
        assertEquals("rewrite_empty_result", result.reason());
        assertEquals("退货怎么弄", result.query());
    }

    /**
     * 超长输出不像「检索短句」：按失败回退，避免把长文塞进嵌入与 BM25。
     */
    @Test
    void overlyLongResultFallsBack() {
        when(lookup.enabledRewriteBind(7L)).thenReturn(Optional.of(bind(88L)));
        when(completions.complete(anyLong(), any(), anyLong(), anyLong(), anyString(), any(),
                anyString(), anyDouble(), anyInt())).thenReturn("长".repeat(600));

        RagQueryRewriter.Result result = service.rewrite(CALLER, "退货怎么弄");

        assertFalse(result.applied());
        assertEquals("rewrite_too_long", result.reason());
    }

    /**
     * 成功：返回清洗后的改写句，带上实际使用的模型实例，并用确定性温度出站。
     */
    @Test
    void successReturnsRewrittenQuery() {
        when(lookup.enabledRewriteBind(7L)).thenReturn(Optional.of(bind(88L)));
        when(completions.complete(eq(7L), eq(21L), eq(99L), eq(88L), anyString(), any(),
                eq("退货怎么弄"), eq(0.2d), anyInt()))
                .thenReturn("```\n\"七天无理由退货流程\"\n```");

        RagQueryRewriter.Result result = service.rewrite(CALLER, "退货怎么弄");

        assertTrue(result.applied());
        assertEquals("七天无理由退货流程", result.query());
        assertEquals(88L, result.modelInstanceId());
        assertNull(result.reason());
    }

    /**
     * 模型输出清洗：只取首个非空行、去包裹引号、折叠多余空白。
     */
    @Test
    void normalizeStripsFencesQuotesAndCollapsesSpaces() {
        assertEquals("退货政策", RagQueryRewriteService.normalize("```text\n「退货政策」\n```"));
        assertEquals("年假 天数 规定", RagQueryRewriteService.normalize("\n\n  年假   天数\t规定  \n第二行"));
        assertEquals("", RagQueryRewriteService.normalize("   "));
        assertEquals("", RagQueryRewriteService.normalize(null));
    }

    private static PromptAssemblyResult result(String text) {
        PromptAssemblyResult result = new PromptAssemblyResult();
        result.setText(text);
        return result;
    }

    private static AgentModelBind bind(Long instanceId) {
        AgentModelBind bind = new AgentModelBind();
        bind.setId(900L);
        bind.setAgentId(7L);
        bind.setModelInstanceId(instanceId);
        bind.setPromptTemplateId(3L);
        bind.setTemplateVersion(1);
        bind.setStatus(1);
        return bind;
    }
}
