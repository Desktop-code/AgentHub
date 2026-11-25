package com.agenthub.runtime.infra.agentscope;

import com.agenthub.common.error.BizException;
import com.agenthub.memory.domain.service.MemoryModelClient;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆出站：空回判定、推理兜底重试、地址脱敏（离线，不连模型）。
 *
 * <p>覆盖排障结论：推理模型在输出上限被思考吃满时只回 ThinkingBlock。
 * 旧实现把这种响应当成「没有返回文本」并直接失败；现在必须先自动放大上限 + 关闭 think
 * 重试一次，只有仍拿不到正文才报错，且报错文案里不得出现任何密钥。
 */
class AgentScopeMemoryModelClientEmptyTest {

    private static final long AGENT_ID = 1L;
    private static final long USER_ID = 2L;
    private static final long SESSION_ID = 3L;
    private static final long INSTANCE_ID = 9L;
    private static final int L1_MAX_TOKENS = 512;

    @Test
    void thinkingOnlyThenRetrySucceeds() {
        Model model = Mockito.mock(Model.class);
        when(model.stream(any(), any(), any()))
                // 首次：思考吃满上限，没有正文
                .thenReturn(Flux.just(ChatResponse.builder()
                        .content(List.of(ThinkingBlock.builder().thinking("用户在要求记住，应该回 store").build()))
                        .finishReason("length").build()))
                // 重试：关闭 think 后给出正文
                .thenReturn(Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("store").build()))
                        .finishReason("stop").build()));
        AgentScopeMemoryModelClient client = clientOf(model);

        String raw = client.complete(AGENT_ID, SESSION_ID, USER_ID, INSTANCE_ID,
                "系统常量", null, "记住它", 0.0d, L1_MAX_TOKENS);

        assertEquals("store", raw);
        ArgumentCaptor<GenerateOptions> captor = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(model, times(2)).stream(any(), any(), captor.capture());
        List<GenerateOptions> used = captor.getAllValues();
        assertEquals(L1_MAX_TOKENS, used.get(0).getMaxTokens());
        // 兜底必须放大上限并带上关闭 think 的扩展键
        assertEquals(AgentScopeMemoryModelClient.escalate(L1_MAX_TOKENS), used.get(1).getMaxTokens());
        assertEquals(Boolean.FALSE, used.get(1).getAdditionalBodyParams().get("enable_thinking"));
    }

    @Test
    void thinkingOnlyTwiceStillFailsWithReasoningReason() {
        AgentScopeMemoryModelClient client = clientOf(modelReturning(
                ChatResponse.builder()
                        .content(List.of(ThinkingBlock.builder().thinking("用户在要求记住，应该回 store").build()))
                        .finishReason("length").build()));

        BizException ex = assertThrows(BizException.class, () -> client.complete(
                AGENT_ID, SESSION_ID, USER_ID, INSTANCE_ID, "系统常量", null, "记住它", 0.0d, L1_MAX_TOKENS));

        // 关键：思考不能当正文，错误也要说清「已尝试兜底」
        assertTrue(ex.getMessage().contains("推理内容"));
        assertTrue(ex.getMessage().contains("finishReason=length"));
        assertTrue(ex.getMessage().contains("关闭 think"));
        assertFalse(ex.getMessage().toLowerCase().contains("sk-"));
    }

    @Test
    void noContentBlockHasItsOwnReasonAndNoRetryForNonSamplingCalls() {
        Model model = modelReturning(
                ChatResponse.builder().content(List.of()).finishReason("stop").build());
        AgentScopeMemoryModelClient client = clientOf(model);

        // 不传采样参数（批抽 / 摘要 / 标题路径）：保持一次出站，不触发兜底重试
        BizException ex = assertThrows(BizException.class, () -> client.complete(
                AGENT_ID, SESSION_ID, USER_ID, INSTANCE_ID, "任务说明", "正文"));

        assertTrue(ex.getMessage().contains("没有返回任何内容块"));
        verify(model, times(1)).stream(any(), any(), any());
    }

    @Test
    void blankTextIsTreatedAsEmpty() {
        AgentScopeMemoryModelClient client = clientOf(modelReturning(
                ChatResponse.builder().content(List.of(TextBlock.builder().text("   ").build())).build()));

        BizException ex = assertThrows(BizException.class, () -> client.complete(
                AGENT_ID, SESSION_ID, USER_ID, INSTANCE_ID, "系统常量", null, "记住它", 0.0d, L1_MAX_TOKENS));

        assertTrue(ex.getMessage().contains("正文为空"));
    }

    @Test
    void textFramesAreConcatenated() {
        AgentScopeMemoryModelClient client = clientOf(modelReturning(
                ChatResponse.builder().content(List.of(TextBlock.builder().text("sto").build())).build(),
                ChatResponse.builder().content(List.of(TextBlock.builder().text("re").build()))
                        .finishReason("stop").build()));

        String raw = client.complete(AGENT_ID, SESSION_ID, USER_ID, INSTANCE_ID,
                "系统常量", null, "记住它", 0.0d, L1_MAX_TOKENS);

        assertEquals("store", raw);
    }

    @Test
    void escalateCoversThinkingAndCapsAtSaneCeiling() {
        assertEquals(4096, AgentScopeMemoryModelClient.escalate(512));
        assertEquals(2048, AgentScopeMemoryModelClient.escalate(8));
        assertEquals(4096, AgentScopeMemoryModelClient.escalate(4096));
    }

    @Test
    void endpointDropsUserInfoAndQuery() {
        // 部分厂商把签名或 key 放在 query，日志只允许 host + path
        assertEquals("api.example.com/v1",
                ModelCallDescriber.endpointOf("https://user:pw@api.example.com/v1?key=sk-secret"));
        assertEquals("dashscope.aliyuncs.com/api/v1",
                ModelCallDescriber.endpointOf("https://dashscope.aliyuncs.com/api/v1/"));
        assertEquals("", ModelCallDescriber.endpointOf("  "));
        assertEquals("api.example.com/v1", ModelCallDescriber.endpointOf("api.example.com/v1?key=sk-secret"));
    }

    @Test
    void outboundLineNeverContainsKey() {
        MemoryModelClient.Outbound out = new MemoryModelClient.Outbound(
                INSTANCE_ID, "记忆模型", "qwen-plus", "MEMORY", "DASHSCOPE", "dashscope.aliyuncs.com/api/v1");
        assertFalse(out.describeLine().contains("sk-"));
        assertTrue(out.describeLine().contains("capability=MEMORY"));
    }

    /**
     * 用可指定帧序列的模型替身组装客户端。
     */
    private AgentScopeMemoryModelClient clientOf(Model model) {
        MeteredModelOpener opener = Mockito.mock(MeteredModelOpener.class);
        when(opener.open(any())).thenReturn(model);
        ModelCallDescriber describer = Mockito.mock(ModelCallDescriber.class);
        when(describer.describe(INSTANCE_ID)).thenReturn(MemoryModelClient.Outbound.unknown(INSTANCE_ID));
        return new AgentScopeMemoryModelClient(opener, describer);
    }

    /**
     * @return 每次订阅都返回同一批帧的模型替身
     */
    private Model modelReturning(ChatResponse... frames) {
        Model model = Mockito.mock(Model.class);
        when(model.stream(any(), any(), any())).thenReturn(Flux.just(frames));
        return model;
    }
}
