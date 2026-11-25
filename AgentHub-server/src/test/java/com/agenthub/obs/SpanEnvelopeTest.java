package com.agenthub.obs;

import com.agenthub.obs.domain.model.ObsSpan;
import com.agenthub.obs.domain.service.SpanEnvelope;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 父步骤必须包住更长的子步骤。所属域：obs。树键为 id + parentId。
 */
class SpanEnvelopeTest {

    @Test
    void parentDurationCoversLongerChild() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 28, 13, 0, 0);
        ObsSpan parent = step(1L, null, start, start.plusSeconds(2), 2223);
        ObsSpan child = step(2L, 1L, start, start.plusSeconds(2), 2233);

        SpanEnvelope.apply(List.of(parent, child));

        assertTrue(parent.getLatencyMs() >= child.getLatencyMs());
        assertFalse(parent.getEndTime().isBefore(child.getEndTime()));
    }

    private ObsSpan step(Long id, Long parentId, LocalDateTime start, LocalDateTime end, int latency) {
        ObsSpan span = new ObsSpan();
        span.setId(id);
        span.setParentId(parentId);
        span.setStartTime(start);
        span.setEndTime(end);
        span.setLatencyMs(latency);
        return span;
    }
}
