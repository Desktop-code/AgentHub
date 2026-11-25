package com.agenthub.runtime.infra.agentscope;

import com.agenthub.billing.domain.model.MeterCommand;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.TraceRecorder;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanDraft;
import com.agenthub.obs.domain.model.TraceStart;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.MeterScope;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型出站失败时，LLM 步骤要带上脱敏 cause。所属域：runtime 的 infra。
 *
 * <p>这条断言守的是「连通测 success、试跑失败却只看得到一句人话」那一类排障缺口：
 * 只要 cause 丢了，面板上就再也无法定位是鉴权、超时还是参数被拒。
 */
class MeteringModelCauseTest {

    @AfterEach
    void clearTrace() {
        ObsTraceHolder.clear();
    }

    @Test
    void failedLlmSpanCarriesCauseDigest() {
        CapturingRecorder recorder = new CapturingRecorder();
        ObsTraceHolder.bind(new ObsTraceScope("t-cause", recorder));
        MeteringModel model = new MeteringModel(failingModel(), rows -> { },
                MeterScope.console(1L, 2L, 3L, 4L));
        assertThrows(IllegalStateException.class, () -> model.stream(List.of(), null, null).blockLast());

        SpanClose closed = recorder.last();
        assertNotNull(closed);
        String cause = ObsAttrs.causeOf(closed.getAttrsJson());
        assertNotNull(cause, "LLM 失败步骤必须带 cause，实际 attrs=" + closed.getAttrsJson());
        assertTrue(cause.contains("IllegalStateException"), cause);
        assertTrue(cause.contains("401"), cause);
    }

    /** 只会失败的模型桩：用于验证失败步的 attrs。 */
    private static Model failingModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(new IllegalStateException("401 invalid api key"));
            }

            @Override
            public String getModelName() {
                return "fake-vision";
            }
        };
    }

    /** 只记录 close 的内存实现。 */
    private static final class CapturingRecorder implements TraceRecorder {
        private final AtomicLong ids = new AtomicLong(1);
        private final List<SpanClose> closes = new ArrayList<>();

        @Override
        public Optional<ObsTraceScope> beginConsole(TraceStart start) {
            return Optional.empty();
        }

        @Override
        public Long openSpan(ObsTraceScope scope, SpanDraft draft) {
            return ids.getAndIncrement();
        }

        @Override
        public void closeSpan(ObsTraceScope scope, Long spanId, SpanClose close) {
            closes.add(close);
        }

        @Override
        public void finish(ObsTraceScope scope, ObsStatus status, String errorSummary) {
            // 本测不验 trace 收尾
        }

        @Override
        public void cancelSession(long sessionId, String reason) {
            // no-op
        }

        SpanClose last() {
            return closes.isEmpty() ? null : closes.get(closes.size() - 1);
        }
    }
}
