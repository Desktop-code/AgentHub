package com.agenthub.obs.domain.service;

import com.agenthub.obs.domain.model.ObsSpan;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 父步骤时间包络。所属域：obs。
 * 库里的 latency_ms 按本步 close 时刻计算，子步骤可能晚几毫秒才落库，
 * 或 datetime 秒精度把结束时间截短。查询展示前把父区间扩到能盖住全部子孙。
 * 树只认 id + parentId。
 */
public final class SpanEnvelope {

    private SpanEnvelope() {
    }

    /**
     * 就地修正 start/end/latency，使每个父节点的区间包含其全部子孙。
     *
     * @param spans 同一条 trace 的步骤，可含多棵根
     */
    public static void apply(List<ObsSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }
        Map<Long, ObsSpan> byId = new HashMap<>();
        for (ObsSpan span : spans) {
            if (span.getId() != null) {
                byId.put(span.getId(), span);
            }
        }
        Map<Long, List<ObsSpan>> children = new HashMap<>();
        List<ObsSpan> roots = new ArrayList<>();
        for (ObsSpan span : spans) {
            Long parentId = span.getParentId();
            if (parentId != null && byId.containsKey(parentId)) {
                children.computeIfAbsent(parentId, key -> new ArrayList<>()).add(span);
            } else {
                roots.add(span);
            }
        }
        Set<Long> seen = new HashSet<>();
        for (ObsSpan root : roots) {
            walk(root, children, seen);
        }
    }

    /** 后序：先收紧子树，再让父区间取并集。 */
    private static void walk(ObsSpan span, Map<Long, List<ObsSpan>> children, Set<Long> seen) {
        if (span.getId() == null || !seen.add(span.getId())) {
            return;
        }
        List<ObsSpan> kids = children.getOrDefault(span.getId(), List.of());
        for (ObsSpan kid : kids) {
            walk(kid, children, seen);
        }
        LocalDateTime start = span.getStartTime();
        LocalDateTime end = effectiveEnd(span);
        for (ObsSpan kid : kids) {
            if (kid.getStartTime() != null && (start == null || kid.getStartTime().isBefore(start))) {
                start = kid.getStartTime();
            }
            LocalDateTime kidEnd = effectiveEnd(kid);
            if (kidEnd != null && (end == null || kidEnd.isAfter(end))) {
                end = kidEnd;
            }
        }
        if (start != null) {
            span.setStartTime(start);
        }
        if (end != null) {
            span.setEndTime(end);
        }
        if (start != null && end != null && !end.isBefore(start)) {
            long ms = Duration.between(start, end).toMillis();
            span.setLatencyMs(ms > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ms);
        }
    }

    /**
     * 结束时刻取 end_time 与 start+latency 的较晚者。
     * datetime 无毫秒时，latency_ms 才是真实跨度。
     */
    private static LocalDateTime effectiveEnd(ObsSpan span) {
        LocalDateTime start = span.getStartTime();
        LocalDateTime end = span.getEndTime();
        Integer latency = span.getLatencyMs();
        if (start != null && latency != null && latency > 0) {
            LocalDateTime byLatency = start.plusNanos(latency.longValue() * 1_000_000L);
            if (end == null || byLatency.isAfter(end)) {
                return byLatency;
            }
        }
        return end;
    }
}
