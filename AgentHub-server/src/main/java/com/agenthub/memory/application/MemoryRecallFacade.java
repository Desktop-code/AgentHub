package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.MemoryTexts;
import com.agenthub.memory.domain.service.ShortMemoryCache;
import com.agenthub.memory.infra.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 试跑注入用的记忆召回门面（唯一入口）。所属域：memory。
 * 默认只取未过期 SHORT。LONG / AGENT 要策略打开，并且有条数和字符上限。
 * <p>
 * LONG 的取数全部收口在 {@link MemoryLongRecall}：向量候选（实例 9 + 记忆 collection）→
 * MySQL 对齐剔删/过期 → 实例 12 可解析则重排 → Top N；未就绪/异常/超时/空 query 自动回落
 * MySQL「importance 降序 + 更新时间」Top N。本类只再套一层字符预算，保证「条数」与「字数」两个口径
 * 都只有一处实现。
 * <p>
 * Redis miss 时从库回补再写入。读 Redis 失败则退回数据库，不把试跑直接打死。
 */
@Component
public class MemoryRecallFacade {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallFacade.class);

    private final ShortMemoryCache cache;
    private final MemoryItemRepository items;
    private final MemoryProperties properties;
    private final MemoryLongRecall longRecall;

    /**
     * @param cache      SHORT 热路径
     * @param items      回补与 AGENT 范围读取
     * @param properties 注入字符上限 / Top N
     * @param longRecall LONG 召回编排（向量 + 对齐 + 重排 + 降级）
     */
    public MemoryRecallFacade(ShortMemoryCache cache, MemoryItemRepository items, MemoryProperties properties,
                              MemoryLongRecall longRecall) {
        this.cache = cache;
        this.items = items;
        this.properties = properties;
        this.longRecall = longRecall;
    }

    /**
     * @param policy    有效策略
     * @param agentId   Agent
     * @param sessionId 会话
     * @return 要注入的短期记忆，策略关闭时为空
     */
    public List<MemoryItem> shorts(MemoryPolicy policy, long agentId, long sessionId) {
        if (policy.getEnableShort() == null || policy.getEnableShort() != 1) {
            return List.of();
        }
        List<MemoryItem> rows = readShort(agentId, sessionId, policy);
        return limit(rows, policy.getMaxShortItems(), properties.getRecallMaxChars());
    }

    /**
     * 取本轮要注入的长期偏好。query 非空且向量链路可用时走语义召回，否则回落 MySQL Top N。
     *
     * @param policy  策略
     * @param agentId Agent
     * @param userId  平台用户
     * @param query   本轮用户查询；空表示不做语义召回
     * @return 工作记忆和用户偏好两段文本，不该注入时为空串；LONG 同时带回条目供工具补参
     */
    public Durable durable(MemoryPolicy policy, long agentId, long userId, String query) {
        String agent = "";
        String pref = "";
        List<MemoryItem> prefItems = List.of();
        int budget = Math.max(properties.getRecallMaxChars(), 1);
        if (policy.getEnableLong() != null && policy.getEnableLong() == 1) {
            // Top N：策略上限与平台硬顶取小，避免策略配大后一次注入几十条
            int topN = Math.min(properties.longRecallTopN(), Math.max(policy.getMaxLongItems(), 1));
            // 召回编排只此一处：向量优先、失败自动回落 MySQL，不在调用方各写一套
            List<MemoryItem> rows = longRecall.topN(agentId, userId, query, topN);
            // 与注入正文用同一批行，保证「注入过」与「可用于补参」完全一致
            prefItems = limit(rows, topN, budget);
            pref = render(prefItems);
            log.debug("LONG召回 agentId={} userId={} topN={} 候选={} 注入={} chars={} byVector={}",
                    agentId, userId, topN, rows.size(), prefItems.size(), pref.length(),
                    query != null && !query.isBlank());
        }
        List<MemoryItem> work = items.listActive(agentId, null, MemoryScope.AGENT.name(),
                policy.getMaxLongItems(), LocalDateTime.now());
        agent = render(limit(work, policy.getMaxLongItems(), budget));
        return new Durable(agent, pref, prefItems);
    }

    /**
     * 无查询文本的兼容入口（调试页/后台排查）：等价于不做语义召回，直接走 MySQL Top N。
     *
     * @param policy  策略
     * @param agentId Agent
     * @param userId  平台用户
     * @return 同 {@link #durable(MemoryPolicy, long, long, String)}
     */
    public Durable durable(MemoryPolicy policy, long agentId, long userId) {
        return durable(policy, agentId, userId, null);
    }

    private List<MemoryItem> readShort(long agentId, long sessionId, MemoryPolicy policy) {
        try {
            Optional<List<MemoryItem>> hit = cache.read(agentId, sessionId);
            if (hit.isPresent()) {
                return alive(hit.get());
            }
        } catch (RuntimeException ex) {
            log.warn("短期记忆读取失败，改从数据库回补 agentId={} sessionId={} type={}",
                    agentId, sessionId, ex.getClass().getSimpleName());
            return items.listActiveShort(agentId, sessionId, LocalDateTime.now());
        }
        List<MemoryItem> fromDb = items.listActiveShort(agentId, sessionId, LocalDateTime.now());
        try {
            int ttl = policy.getShortDefaultTtlSec() == null ? 86400 : policy.getShortDefaultTtlSec();
            for (MemoryItem item : fromDb) {
                ttl = Math.max(ttl, MemoryTexts.ttlSeconds(item.getExpiredTime(), ttl));
            }
            cache.write(agentId, sessionId, fromDb, ttl);
        } catch (RuntimeException ex) {
            log.warn("短期记忆回补写入失败 agentId={} sessionId={} type={}",
                    agentId, sessionId, ex.getClass().getSimpleName());
        }
        return fromDb;
    }

    private List<MemoryItem> alive(List<MemoryItem> rows) {
        LocalDateTime now = LocalDateTime.now();
        List<MemoryItem> live = new ArrayList<>();
        for (MemoryItem item : rows) {
            if (item.getStatus() != null && item.getStatus() != 1) {
                continue;
            }
            if (item.getExpiredTime() != null && !item.getExpiredTime().isAfter(now)) {
                continue;
            }
            if (item.getContent() == null || item.getContent().isBlank()) {
                continue;
            }
            live.add(item);
        }
        return live;
    }

    private List<MemoryItem> limit(List<MemoryItem> rows, Integer maxItems, int maxChars) {
        int max = maxItems == null ? rows.size() : maxItems;
        List<MemoryItem> kept = new ArrayList<>();
        int used = 0;
        for (MemoryItem item : rows) {
            if (kept.size() >= max) {
                break;
            }
            int len = item.getContent() == null ? 0 : item.getContent().length();
            if (used + len > maxChars && !kept.isEmpty()) {
                break;
            }
            kept.add(item);
            used += len;
        }
        return kept;
    }

    private String render(List<MemoryItem> rows) {
        StringBuilder text = new StringBuilder();
        for (MemoryItem item : rows) {
            text.append("- ");
            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                text.append(item.getTitle().trim()).append('：');
            }
            text.append(item.getContent().trim()).append('\n');
        }
        return text.toString().trim();
    }

    /**
     * 系统提示里的两段记忆。所属域：memory。
     * items 是本轮真正拼进【用户偏好】的 LONG 行，供 runtime 判断字段补参依据；未注入的条目不在其中。
     *
     * @param agent 工作记忆
     * @param pref  用户偏好
     * @param items 已注入的 LONG 条目，可能为空
     */
    public record Durable(String agent, String pref, List<MemoryItem> items) {

        public Durable {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
