package com.agenthub.runtime.domain.service;

import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 用 route_key / intent 做一层关键字路由。所属域：runtime。
 * 未命中时由 Router 执行器再决定是否请父模型判定，这里不调用模型。
 */
public final class RouteKeywordPicker {

    private RouteKeywordPicker() {
    }

    /**
     * 按已排序的路由找第一条命中。停用行忽略。
     *
     * @param content 用户输入
     * @param routes  sort_order 升序
     * @return 命中的子路由
     */
    public static Optional<AgentRoute> match(String content, List<AgentRoute> routes) {
        List<AgentRoute> all = matchAll(content, routes, 1);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * 按 sort 顺序收集所有关键字命中；同 child 去重保留首次；截到 maxItems。
     * 关键字路径无法可靠切句，sub_query 由调用方填整句。
     *
     * @param content  用户输入
     * @param routes   sort_order 升序
     * @param maxItems 硬顶，至少 1
     * @return 有序命中列表，可能为空
     */
    public static List<AgentRoute> matchAll(String content, List<AgentRoute> routes, int maxItems) {
        if (content == null || content.isBlank() || routes == null || routes.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, maxItems);
        String haystack = content.toLowerCase(Locale.ROOT);
        List<AgentRoute> hits = new ArrayList<>();
        Set<Long> seenChild = new LinkedHashSet<>();
        for (AgentRoute route : routes) {
            if (hits.size() >= cap) {
                break;
            }
            if (route.getStatus() == null || route.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            Long childId = route.getChildAgentId();
            if (childId != null && !seenChild.add(childId)) {
                continue;
            }
            if (contains(haystack, route.getRouteKey()) || contains(haystack, route.getIntent())) {
                hits.add(route);
            }
        }
        return hits;
    }

    private static boolean contains(String haystack, String needle) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        return haystack.contains(needle.trim().toLowerCase(Locale.ROOT));
    }
}
