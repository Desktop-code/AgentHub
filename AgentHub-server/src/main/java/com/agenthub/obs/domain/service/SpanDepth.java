package com.agenthub.obs.domain.service;

import com.agenthub.obs.domain.model.ObsSpan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按 parentId 计算缩进深度。所属域：obs。
 * 父步骤缺失或出现环时停在当前层，避免一条脏数据把查询打挂。
 */
public final class SpanDepth {

    private static final int MAX_DEPTH = 64;

    private SpanDepth() {
    }

    /**
     * @param spans 同一条 trace 的步骤，可空
     * @return 行主键 id → 深度。根是 0
     */
    public static Map<Long, Integer> of(List<ObsSpan> spans) {
        Map<Long, ObsSpan> byId = new HashMap<>();
        if (spans != null) {
            for (ObsSpan span : spans) {
                if (span != null && span.getId() != null) {
                    byId.put(span.getId(), span);
                }
            }
        }
        Map<Long, Integer> depth = new HashMap<>();
        for (ObsSpan span : byId.values()) {
            depth.put(span.getId(), measure(span, byId));
        }
        return depth;
    }

    private static int measure(ObsSpan span, Map<Long, ObsSpan> byId) {
        int level = 0;
        Long parent = span.getParentId();
        Set<Long> seen = new HashSet<>();
        while (parent != null && seen.add(parent) && level < MAX_DEPTH) {
            ObsSpan upper = byId.get(parent);
            if (upper == null) {
                break;
            }
            level++;
            parent = upper.getParentId();
        }
        return level;
    }
}
