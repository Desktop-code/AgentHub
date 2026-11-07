package com.agenthub.memory.application;

import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.model.MemoryVectorHit;
import com.agenthub.memory.domain.model.MemoryVectorReadiness;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.infra.MemoryProperties;
import com.agenthub.spi.rag.RerankClient;
import com.agenthub.spi.rag.RerankHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * LONG 用户偏好的召回编排（唯一实现）。所属域：memory 的 application。
 * <p>
 * 为什么集中在这里：向量候选、MySQL 对齐、重排、Top N 截断是同一件事的四个步骤，
 * 散落到各调用点必然出现「有人截断有人不截」「有人忘了剔过期」。所有召回入口只调 {@link #topN}。
 * <p>
 * 执行路径（与验收口径一一对应）：
 * <ol>
 *   <li><b>降级</b>：未就绪 / 异常 / 超时 / 空 query / 无命中 → MySQL「启用 + 未过期 + importance 降序 + update_time 降序」Top N；</li>
 *   <li><b>正常</b>：Embedding(平台级) → Milvus 按 user_id + agent_id 过滤取 topK → 用主键回 MySQL
 *       只留启用且未过期的行（剔已删/已过期）→ 平台级 Rerank 可解析则 rerank 后截 N，否则按 importance + 时间截 N；</li>
 *   <li><b>同一门槛</b>：两路都先按 {@code recall-importance-min} 丢低分条目，再截 Top N；</li>
 *   <li><b>近义去重</b>：开关打开时，截 Top N 前与已选项做 fact_key / 相似度去重（只影响注入，不删库）；</li>
 *   <li>字符预算不在这里做：由门面统一按 {@code recall-max-chars} 裁，避免两处各算一套。</li>
 * </ol>
 * 只读：本类不写库、不写向量、不改策略。
 */
@Component
public class MemoryLongRecall {

    private static final Logger log = LoggerFactory.getLogger(MemoryLongRecall.class);

    /** 近义去重前先抹掉标点/空白：这些差异不应让同一事实被算成两条 */
    private static final Pattern DUP_NOISE = Pattern.compile("[\\p{P}\\p{Z}\\s]+");

    private final MemoryItemRepository items;
    private final MemoryVectorRecall vectors;
    private final RerankClient reranks;
    private final MemoryRecallGateway gateway;
    private final MemoryProperties properties;

    /**
     * @param items      回 MySQL 对齐正文与有效性
     * @param vectors    向量候选（带超时/降级）
     * @param reranks    重排出站（平台级 Rerank）
     * @param gateway    重排的带超时执行
     * @param properties 候选条数、重排超时
     */
    public MemoryLongRecall(MemoryItemRepository items, MemoryVectorRecall vectors, RerankClient reranks,
                            MemoryRecallGateway gateway, MemoryProperties properties) {
        this.items = items;
        this.vectors = vectors;
        this.reranks = reranks;
        this.gateway = gateway;
        this.properties = properties;
    }

    /**
     * 取本轮要注入的 LONG 条目（已按规则排序并截到 N）。
     *
     * @param agentId Agent
     * @param userId  平台用户
     * @param query   本轮用户查询；空则走 MySQL Top N
     * @param topN    期望条数，调用方已按策略与硬顶收敛
     * @return 注入顺序的条目；任何失败都返回 MySQL Top N（可能为空）
     */
    public List<MemoryItem> topN(long agentId, long userId, String query, int topN) {
        int limit = Math.max(topN, 1);
        // 两路共用同一门槛：低于下限的条目先丢，再截 Top N
        int minImportance = properties.recallImportanceMin();
        List<MemoryItem> aligned = vectorAligned(agentId, userId, query, limit, minImportance);
        if (!aligned.isEmpty()) {
            return aligned;
        }
        return mysqlTop(agentId, userId, limit, minImportance);
    }

    /**
     * 向量候选 → MySQL 对齐 → 重排 → 截 N。
     *
     * @return 有序条目；未就绪/失败/全部失效时为空列表（由调用方回落 MySQL）
     */
    private List<MemoryItem> vectorAligned(long agentId, long userId, String query, int limit, int minImportance) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        MemoryVectorReadiness snapshot = vectors.readiness();
        if (snapshot == null || !snapshot.ready()) {
            return List.of();
        }
        // 候选要略多于 N：对齐掉已删/过期后仍可能够 N，重排也需要备选
        int topK = Math.max(limit, Math.max(properties.getRecallVectorTopK(), 1));
        List<MemoryVectorHit> hits = vectors.candidates(agentId, userId, query, topK);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<MemoryItem> aligned = filterByImportance(align(hits), minImportance);
        if (aligned.isEmpty()) {
            // 命中全被删/过期/停用或低于 importance 下限：视为向量库没有可用候选，回落 MySQL
            log.debug("[MEMORY] 向量候选全部失效或低于重要性下限 agentId={} userId={} hits={} min={}",
                    agentId, userId, hits.size(), minImportance);
            return List.of();
        }
        // 去重开启时多要一些重排候选，避免去重后不足 N；关闭时与现网一致
        int rerankLimit = candidateLimit(limit);
        if (snapshot.rerankReady()) {
            List<MemoryItem> reranked = rerank(snapshot.rerankInstanceId(), query, aligned, rerankLimit);
            if (!reranked.isEmpty()) {
                return finish(reranked, limit, true);
            }
        }
        return finish(byImportance(aligned), limit, true);
    }

    /**
     * 用主键回 MySQL 对齐：只保留「启用 + 未过期」，顺序先按向量分数。
     * <p>
     * 向量库与 MySQL 可能短暂不一致（双写异步），所以以 MySQL 为准：库里没有的行直接丢。
     * 正文也以 MySQL 为准；只有 MySQL 取回空正文时才用向量侧副本兜底——
     * 这样「只靠主键才能拿到正文」不再是唯一取数路径，同时不会让陈旧的向量副本覆盖真源。
     */
    private List<MemoryItem> align(List<MemoryVectorHit> hits) {
        // 去重并保序：同一 memory_id 只回查一次，顺序仍是 Milvus 分数序
        LinkedHashSet<Long> ids = new LinkedHashSet<>(hits.size());
        for (MemoryVectorHit hit : hits) {
            if (hit != null && hit.memoryId() > 0) {
                ids.add(hit.memoryId());
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, MemoryVectorHit> hitById = new HashMap<>(ids.size() * 2);
        for (MemoryVectorHit hit : hits) {
            if (hit != null && hit.memoryId() > 0) {
                hitById.put(hit.memoryId(), hit);
            }
        }
        Map<Long, MemoryItem> byId = new HashMap<>(ids.size() * 2);
        for (MemoryItem item : items.listActiveByIds(ids, MemoryScope.LONG.name(), LocalDateTime.now())) {
            if (item != null && item.getId() != null) {
                byId.put(item.getId(), item);
            }
        }
        List<MemoryItem> ordered = new ArrayList<>(byId.size());
        // 按向量分数序保留；MySQL 已被删除/过期的 id 不在 byId 里，天然被剔除
        for (Long id : ids) {
            MemoryItem item = byId.get(id);
            if (item == null) {
                continue;
            }
            fillContentFromVector(item, hitById.get(id));
            ordered.add(item);
        }
        return ordered;
    }

    /**
     * 正文兜底：MySQL 正文为空时用向量侧副本，避免把空串注入提示词。
     *
     * @param item MySQL 对齐后的条目（就地补正文）
     * @param hit  同一主键的向量命中；可空
     */
    private static void fillContentFromVector(MemoryItem item, MemoryVectorHit hit) {
        if (item.getContent() != null && !item.getContent().isBlank()) {
            return;
        }
        if (hit == null || !hit.hasContent()) {
            return;
        }
        item.setContent(hit.content());
        log.debug("[MEMORY] 正文取用向量副本 memoryId={} reason=mysql_content_blank", item.getId());
    }

    /**
     * 调平台级 Rerank 重排。失败（超时/未适配/返回空）返回空列表，由调用方退回 importance 序。
     */
    private List<MemoryItem> rerank(Long instanceId, String query, List<MemoryItem> candidates, int limit) {
        if (instanceId == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> documents = new ArrayList<>(candidates.size());
        boolean hasText = false;
        for (MemoryItem item : candidates) {
            String content = item.getContent() == null ? "" : item.getContent();
            hasText = hasText || !content.isBlank();
            documents.add(content);
        }
        // 全部正文为空时重排没有意义（也浪费一次出站）
        if (!hasText) {
            return List.of();
        }
        List<RerankHit> hits = gateway.call("rerank", Math.max(properties.getRecallRerankTimeoutMs(), 0L),
                () -> reranks.rerank(instanceId, query, documents, limit));
        if (hits == null || hits.isEmpty()) {
            log.debug("[MEMORY] 重排无结果，退回 importance 序 candidates={}", candidates.size());
            return List.of();
        }
        List<MemoryItem> ordered = new ArrayList<>(hits.size());
        for (RerankHit hit : hits) {
            if (hit == null || hit.index() < 0 || hit.index() >= candidates.size()) {
                continue;
            }
            MemoryItem item = candidates.get(hit.index());
            if (item != null && !ordered.contains(item)) {
                ordered.add(item);
            }
        }
        if (ordered.isEmpty()) {
            return List.of();
        }
        log.debug("[MEMORY] 重排完成 candidates={} kept={}", candidates.size(), ordered.size());
        return ordered.size() <= limit ? ordered : new ArrayList<>(ordered.subList(0, limit));
    }

    /**
     * 降级路径：MySQL 有效未过期 + importance 下限 + importance 降序 + 更新时间降序 Top N。
     * <p>
     * importance 下限必须交给仓储在 SQL 里过滤（见 {@link MemoryItemRepository#listActive}），
     * 否则先 LIMIT N 再过滤会让合格行不足 N。
     */
    private List<MemoryItem> mysqlTop(long agentId, long userId, int limit, int minImportance) {
        int fetch = candidateLimit(limit);
        LocalDateTime now = LocalDateTime.now();
        List<MemoryItem> rows = minImportance > 0
                ? items.listActive(agentId, userId, MemoryScope.LONG.name(), fetch, minImportance, now)
                : items.listActive(agentId, userId, MemoryScope.LONG.name(), fetch, now);
        return finish(rows, limit, false);
    }

    /**
     * 候选窗口：去重开启时多取几条，保证「丢弃后再截 Top N」仍能补足；
     * 关闭时与现网一致，只取 N。
     *
     * @param limit 期望注入条数
     * @return 实际向数据源要的条数
     */
    private int candidateLimit(int limit) {
        if (!properties.isRecallNearDupEnabled()) {
            return limit;
        }
        return Math.min(Math.max(limit * 3, limit + 5), 100);
    }

    /**
     * 注入前的统一收尾：近义去重 → 截 Top N。
     * <p>
     * 开关关闭时一步都不做，保证与未上线该能力时的行为完全一致。
     *
     * @param ordered    已排序候选（向量重排序 / importance 序 / MySQL 序）
     * @param limit      期望注入条数
     * @param vectorPath true=向量路径（按相似度阈值判近义）；false=MySQL-only（按规范化指纹判等）
     * @return 注入顺序的条目
     */
    private List<MemoryItem> finish(List<MemoryItem> ordered, int limit, boolean vectorPath) {
        if (!properties.isRecallNearDupEnabled()) {
            return truncate(ordered, limit);
        }
        List<MemoryItem> selected = nearDupFilter(ordered, properties.recallNearDupSimilarity(), vectorPath);
        if (selected.size() != ordered.size()) {
            // 只记丢弃条数与路径，禁止把正文写进日志
            log.debug("[MEMORY] 召回近义去重 path={} candidates={} dropped={}",
                    vectorPath ? "vector" : "mysql", ordered.size(), ordered.size() - selected.size());
        }
        return truncate(selected, limit);
    }

    /**
     * importance 下限过滤：null 按 0 处理；0 表示不过滤（直接返回原列表）。
     *
     * @param rows          候选
     * @param minImportance 下限
     * @return 达标的条目，保持原顺序
     */
    private static List<MemoryItem> filterByImportance(List<MemoryItem> rows, int minImportance) {
        if (minImportance <= 0) {
            return rows;
        }
        List<MemoryItem> kept = new ArrayList<>(rows.size());
        for (MemoryItem item : rows) {
            if (item == null) {
                continue;
            }
            int importance = item.getImportance() == null ? 0 : item.getImportance();
            if (importance >= minImportance) {
                kept.add(item);
            }
        }
        return kept;
    }

    /**
     * 贪心近义去重：按候选顺序逐个与已选项比较，命中同 fact_key 或近义正文即冲突。
     * <p>
     * 冲突裁决：留 importance 高者；同分留 update_time 更新者；再同分留 id 大者。
     * 候选胜出时替换掉所有被它压过的冲突项，并放回最早冲突位以尽量保持相关性排序。
     *
     * @param ordered    候选顺序
     * @param threshold  相似度阈值（向量路径）
     * @param vectorPath true=向量路径；false=MySQL-only
     * @return 去重后的列表（未截断）
     */
    private static List<MemoryItem> nearDupFilter(List<MemoryItem> ordered, double threshold, boolean vectorPath) {
        List<MemoryItem> selected = new ArrayList<>(ordered.size());
        for (MemoryItem candidate : ordered) {
            if (candidate == null) {
                continue;
            }
            List<Integer> conflicts = new ArrayList<>(2);
            boolean loses = false;
            for (int i = 0; i < selected.size(); i++) {
                if (!isNearDuplicate(candidate, selected.get(i), threshold, vectorPath)) {
                    continue;
                }
                conflicts.add(i);
                // 只要输给任意一个已选项就丢弃候选，避免出现「赢了 A 却和 B 重复」
                if (!moreImportant(candidate, selected.get(i))) {
                    loses = true;
                    break;
                }
            }
            if (loses) {
                continue;
            }
            if (conflicts.isEmpty()) {
                selected.add(candidate);
                continue;
            }
            int insertAt = conflicts.get(0);
            for (int i = conflicts.size() - 1; i >= 0; i--) {
                selected.remove((int) conflicts.get(i));
            }
            selected.add(Math.min(insertAt, selected.size()), candidate);
        }
        return selected;
    }

    /**
     * 判定两条记忆是否为近义重复。
     * <p>
     * 同 fact_key 优先；正文为空不参与（避免两条空正文互相吞掉）。
     * 向量路径用字符二元组 Dice 相似度；MySQL-only 没有向量分，改用去标点/空白后的正文指纹判等。
     */
    private static boolean isNearDuplicate(MemoryItem left, MemoryItem right, double threshold, boolean vectorPath) {
        if (left == null || right == null) {
            return false;
        }
        String leftKey = trimToNull(left.getFactKey());
        String rightKey = trimToNull(right.getFactKey());
        if (leftKey != null && leftKey.equals(rightKey)) {
            return true;
        }
        String leftText = left.getContent();
        String rightText = right.getContent();
        if (leftText == null || leftText.isBlank() || rightText == null || rightText.isBlank()) {
            return false;
        }
        if (vectorPath) {
            return similarity(leftText, rightText) >= threshold;
        }
        return normalizeForDup(leftText).equals(normalizeForDup(rightText));
    }

    /**
     * 冲突裁决：importance 降序 → update_time 新者 → id 大者。
     */
    private static boolean moreImportant(MemoryItem candidate, MemoryItem existing) {
        int candidateValue = candidate.getImportance() == null ? 0 : candidate.getImportance();
        int existingValue = existing.getImportance() == null ? 0 : existing.getImportance();
        if (candidateValue != existingValue) {
            return candidateValue > existingValue;
        }
        LocalDateTime candidateTime = candidate.getUpdateTime() == null
                ? LocalDateTime.MIN : candidate.getUpdateTime();
        LocalDateTime existingTime = existing.getUpdateTime() == null
                ? LocalDateTime.MIN : existing.getUpdateTime();
        if (!candidateTime.equals(existingTime)) {
            return candidateTime.isAfter(existingTime);
        }
        long candidateId = candidate.getId() == null ? 0L : candidate.getId();
        long existingId = existing.getId() == null ? 0L : existing.getId();
        return candidateId > existingId;
    }

    /**
     * 正文规范化：转小写并抹掉标点、空白、分隔符，用于 MySQL-only 的指纹判等。
     */
    private static String normalizeForDup(String text) {
        return DUP_NOISE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /**
     * 字符二元组 Dice 相似度（0~1）。中文没有天然分词，用相邻字符组对近义改写更稳。
     * <p>
     * 完全相同返回 1；任一正文规范化后不足 2 个字符则只能判等，不再算相似度。
     */
    private static double similarity(String left, String right) {
        String a = normalizeForDup(left);
        String b = normalizeForDup(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        if (a.equals(b)) {
            return 1d;
        }
        if (a.length() < 2 || b.length() < 2) {
            return 0d;
        }
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i < a.length() - 1; i++) {
            grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        }
        int intersection = 0;
        for (int i = 0; i < b.length() - 1; i++) {
            String gram = b.substring(i, i + 2);
            Integer count = grams.get(gram);
            if (count != null && count > 0) {
                intersection++;
                grams.put(gram, count - 1);
            }
        }
        int total = (a.length() - 1) + (b.length() - 1);
        return total <= 0 ? 0d : (2.0d * intersection) / total;
    }

    /**
     * @return 去首尾空白后的非空字符串；空串/null 返回 null
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * importance 降序 → update_time 新近 → id 降序，与 MySQL Top N 同口径。
     */
    private static List<MemoryItem> byImportance(List<MemoryItem> rows) {
        List<MemoryItem> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator
                .comparingInt((MemoryItem item) -> item.getImportance() == null ? 0 : item.getImportance())
                .reversed()
                .thenComparing(item -> item.getUpdateTime() == null ? LocalDateTime.MIN : item.getUpdateTime(),
                        Comparator.reverseOrder())
                .thenComparing(item -> item.getId() == null ? 0L : item.getId(), Comparator.reverseOrder()));
        return sorted;
    }

    private static List<MemoryItem> truncate(List<MemoryItem> rows, int limit) {
        if (rows.size() <= limit) {
            return rows;
        }
        return new ArrayList<>(rows.subList(0, limit));
    }
}
