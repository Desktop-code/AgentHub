package com.agenthub.runtime.infra.agentscope;

import com.agenthub.billing.domain.BillingMeter;
import com.agenthub.billing.domain.model.BillingChannel;
import com.agenthub.billing.domain.model.MeterCommand;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 一次 stream 记一条。失败步记 0，备注不带异常原文。
 */
class MeteringModelTest {

    @Test
    void eachStreamWritesOneLedgerUsingLastUsage() {
        List<MeterCommand> rows = new ArrayList<>();
        MeterScope scope = MeterScope.console(7L, 8L, 9L, 3L);
        MeteringModel model = new MeteringModel(scripted(
                Flux.just(usage(1, 1), usage(3, 4)),
                Flux.just(usage(8, 9))
        ), rows::add, scope);
        model.stream(List.of(), null, null).blockLast();
        model.stream(List.of(), null, null).blockLast();
        assertEquals(2, rows.size());
        assertEquals(3, rows.get(0).getTokenPrompt());
        assertEquals(4, rows.get(0).getTokenCompletion());
        assertEquals(8, rows.get(1).getTokenPrompt());
        assertEquals(9, rows.get(1).getTokenCompletion());
        assertEquals(BillingChannel.CONSOLE, rows.get(0).getChannel());
        assertEquals(7L, rows.get(0).getAgentId());
        assertEquals(9L, rows.get(0).getSessionId());
    }

    @Test
    void failedCallRecordsZeroWithoutExceptionText() {
        List<MeterCommand> rows = new ArrayList<>();
        MeteringModel model = new MeteringModel(scripted(
                Flux.error(new IllegalStateException("sk-should-not-leak"))
        ), rows::add, MeterScope.console(1L, 2L, 3L, 4L));
        assertThrows(IllegalStateException.class, () -> model.stream(List.of(), null, null).blockLast());
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).getTokenPrompt());
        assertEquals(0, rows.get(0).getTokenCompletion());
        assertEquals("IllegalStateException", rows.get(0).getRemark());
        assertFalse(rows.get(0).getRemark().contains("sk-"));
    }

    @Test
    void trialFailureKeepsSafeMessage() {
        List<MeterCommand> rows = new ArrayList<>();
        MeteringModel model = new MeteringModel(scripted(
                Flux.error(new TrialFailure("模型调用失败"))
        ), rows::add, MeterScope.console(1L, 2L, 3L, 4L));
        assertThrows(TrialFailure.class, () -> model.stream(List.of(), null, null).blockLast());
        assertEquals("模型调用失败", rows.get(0).getRemark());
    }

    private static ChatResponse usage(int prompt, int completion) {
        return ChatResponse.builder()
                .usage(ChatUsage.builder().inputTokens(prompt).outputTokens(completion).build())
                .build();
    }

    @SafeVarargs
    private static Model scripted(Flux<ChatResponse>... calls) {
        return new Model() {
            private int index;

            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return calls[index++];
            }

            @Override
            public String getModelName() {
                return "fake";
            }
        };
    }
}
