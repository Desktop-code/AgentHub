package com.agenthub.memory.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.FactKeyNormalizer;
import com.agenthub.memory.domain.service.MemoryTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * LONG 同键合并写入。所属域：memory。
 * 维度 agent_id+user_id+LONG+事实族（canonical fact_key 及其别名）；content_hash 作全文完全相同短路径。
 * 事务内按键查询再写，禁止同键双插。
 * <p>
 * 「事实族」而非单一 canonical 查询，是为了并轨历史行：旧版本没有偏好族别名时，
 * 同一条偏好可能已经用 {@code favorite_food} / {@code food_preference} 各存了一条。
 * 命中多行时留最新一条，其余走既有逻辑删 + 向量联删（{@link MemoryVectorHook#afterRemoved}），
 * 所以「合并」是<b>写库</b>行为，不是召回期去重。
 */
@Service
public class LongMemoryMerger {

    private static final Logger log = LoggerFactory.getLogger(LongMemoryMerger.class);

    /** debug 日志里正文摘要的最大长度：够定位是哪条被改，又不把整段正文写进日志 */
    private static final int CONTENT_PREVIEW_CHARS = 40;

    private final MemoryItemRepository items;
    private final FactKeyNormalizer factKeys;
    private final MemoryVectorHook vectors;

    /**
     * @param items    条目仓储
     * @param factKeys fact_key 归一
     * @param vectors  LONG 向量双写统一挂载点（提交后异步，失败不回滚 MySQL）
     */
    public LongMemoryMerger(MemoryItemRepository items, FactKeyNormalizer factKeys, MemoryVectorHook vectors) {
        this.items = items;
        this.factKeys = factKeys;
        this.vectors = vectors;
    }

    /**
     * 抽取路径：必须带有效 fact_key；失败跳过并打点日志，禁止无 key 插入。
     *
     * <p>ttl_days 在这里换算成 {@code expired_time}：0/缺省=永久（留 null），
     * 正整数=now + N 天。非法值在解析处已按永久处理，这里不再二次猜测。
     *
     * @param agentId    Agent
     * @param userId     用户
     * @param draft      抽取草稿（含 factKey/title/content/importance/ttlDays）
     * @param operatorId 操作人（计量用用户 id）
     * @return empty=跳过；true=新建；false=同键或同 hash 更新
     */
    @Transactional
    public Optional<Boolean> upsertExtracted(long agentId, long userId, MemoryTexts.PreferenceDraft draft,
                                             long operatorId) {
        Optional<String> key = factKeys.normalize(draft.getFactKey());
        if (key.isEmpty()) {
            // 打点：缺键/归一失败不得落库
            log.warn("LONG抽取跳过 reason=fact_key_invalid title={} rawKey={}",
                    draft.getTitle(), draft.getFactKey());
            return Optional.empty();
        }
        MemoryItem item = new MemoryItem();
        item.setAgentId(agentId);
        item.setUserId(userId);
        item.setScope(MemoryScope.LONG.name());
        item.setTitle(draft.getTitle());
        item.setFactKey(key.get());
        item.setContent(draft.getContent());
        item.setContentHash(MemoryTexts.hash(draft.getContent()));
        item.setImportance(Math.max(0, Math.min(draft.getImportance(), 100)));
        // 0=永久：expired_time 保持 null，召回以 IS NULL 视为不过期
        item.setExpiredTime(MemoryTexts.expiredTimeOf(draft.getTtlDays(), LocalDateTime.now()));
        item.setStatus(1);
        // 抽取路径的过期时间以本次为准：新抽取填 0（永久）时必须能清掉旧的过期时间
        boolean inserted = merge(item, operatorId, true, true);
        // 统一出口：显式 / 批抽 / 摘要顺带都经这里，提交后异步双写，失败不影响已提交的 MySQL
        vectors.afterSaved(item);
        return Optional.of(inserted);
    }

    /**
     * 控制台人工写入 LONG：填了 fact_key 走同键合并；未填保持 hash 短路径，不强制。
     *
     * @param draft      已 prepare 的条目（含 contentHash）
     * @param operatorId 操作人
     * @return 落库后的条目
     * @throws BizException fact_key 非空但格式/归一失败
     */
    @Transactional
    public MemoryItem upsertManual(MemoryItem draft, long operatorId) {
        String raw = draft.getFactKey();
        if (raw != null && !raw.isBlank()) {
            Optional<String> key = factKeys.normalize(raw);
            if (key.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "fact_key 须为小写 snake_case（[a-z0-9_]，≤64）或已配置同义词");
            }
            draft.setFactKey(key.get());
            merge(draft, operatorId, true, false);
        } else {
            draft.setFactKey(null);
            merge(draft, operatorId, false, false);
        }
        Long id = draft.getId();
        if (id == null) {
            return draft;
        }
        // 以重新查回的整行为准（status / expired_time 可能是库里旧值），再统一挂双写
        MemoryItem saved = items.findById(id).orElse(draft);
        vectors.afterSaved(saved);
        return saved;
    }

    /**
     * 先按事实族（canonical + 别名）查再写；未命中再走 content_hash；都未命中才插入。
     *
     * @param draft           待写入（scope 应为 LONG）
     * @param operatorId      操作人
     * @param hasFactKey      本次是否带有效 fact_key
     * @param overrideExpired true=过期时间以本次为准（抽取路径，允许把已设置过期的条改回永久）
     * @return true=insert，false=update
     */
    private boolean merge(MemoryItem draft, long operatorId, boolean hasFactKey, boolean overrideExpired) {
        // 同族优先：命中则覆盖 content，importance 取 max，其余同族旧行收敛掉
        if (hasFactKey && draft.getFactKey() != null) {
            List<MemoryItem> family = items.findActiveByFactKeys(
                    draft.getAgentId(), draft.getUserId(), MemoryScope.LONG.name(),
                    factKeys.familyKeysOf(draft.getFactKey()));
            if (!family.isEmpty()) {
                // 查询按 id 倒序：首条即最新，作为保留行（纠正/更正场景天然以新值为准）
                MemoryItem keep = family.get(0);
                // 同族要收敛掉的旧行可能带着更高 importance；合并后不能因为删旧行把它降下去
                int familyImportance = family.stream()
                        .mapToInt(row -> row.getImportance() == null ? 0 : row.getImportance())
                        .max().orElse(0);
                if (draft.getImportance() == null || draft.getImportance() < familyImportance) {
                    draft.setImportance(familyImportance);
                }
                applyUpdate(keep, draft, operatorId, overrideExpired);
                draft.setId(keep.getId());
                collapseFamily(family, keep, operatorId);
                return false;
            }
        }
        // 全文完全相同短路径，与 fact_key 互补
        MemoryItem byHash = items.findActiveByHash(
                draft.getAgentId(), draft.getUserId(), MemoryScope.LONG.name(), draft.getContentHash())
                .orElse(null);
        if (byHash != null) {
            // 旧行无 key、本次有 key 时由 applyUpdate 补写，便于后续同族合并
            applyUpdate(byHash, draft, operatorId, overrideExpired);
            draft.setId(byHash.getId());
            return false;
        }
        Long id = items.insert(draft, operatorId);
        draft.setId(id);
        return true;
    }

    /**
     * 同族多行收敛：留 {@code keep}，其余逻辑删并走既有向量联删。
     * <p>
     * 为什么必须显式删：只更新最新一条会把旧行留在库里，召回仍会命中旧值，
     * 表现就是「纠正了但还答旧的」。向量点同样要删，否则向量召回还能命中已废弃内容。
     *
     * @param family     同族活跃行（首条为保留行）
     * @param keep       保留行
     * @param operatorId 操作人
     */
    private void collapseFamily(List<MemoryItem> family, MemoryItem keep, long operatorId) {
        List<Long> redundant = new ArrayList<>();
        for (MemoryItem row : family) {
            if (row.getId() != null && !row.getId().equals(keep.getId())) {
                redundant.add(row.getId());
            }
        }
        if (redundant.isEmpty()) {
            return;
        }
        redundant.forEach(id -> items.logicalDelete(id, operatorId));
        // 事务提交后才删向量点；MySQL 是唯一真源，向量删除失败只影响派生数据
        vectors.afterRemoved(redundant);
        log.info("[MEMORY] LONG同族收敛 factKey={} keepId={} removedIds={}",
                keep.getFactKey(), keep.getId(), redundant);
    }

    /**
     * 冲突策略：content/title/hash 以本次为准（纠错/更正即覆盖）；importance 取较大值。
     * <p>
     * 覆盖是<b>无条件</b>的：抽取模型已经把「最新结论」提炼进 content，
     * 这里再判断「差异是否足够大」只会让真实更正写不进去。
     *
     * @param existing        库中已有条目
     * @param draft           本次写入
     * @param operatorId      操作人
     * @param overrideExpired true=无条件用本次的 expired_time（可为 null，即改回永久）；
     *                        false=仅当本次带了过期时间才覆盖（人工编辑不强制 TTL）
     */
    private void applyUpdate(MemoryItem existing, MemoryItem draft, long operatorId, boolean overrideExpired) {
        String oldContent = existing.getContent();
        if (draft.getTitle() != null) {
            existing.setTitle(draft.getTitle());
        }
        existing.setContent(draft.getContent());
        existing.setContentHash(draft.getContentHash());
        // 历史行可能是同族旧别名 key（如 favorite_food）：并入后改成 canonical，下次不用再靠别名窗口。
        // 只在「同族」内改写：hash 命中跨域的旧行（key 完全不同）不能因为内容相同就被换掉事实键
        if (draft.getFactKey() != null) {
            String existingKey = existing.getFactKey();
            boolean sameFamily = existingKey == null || existingKey.isBlank()
                    || factKeys.familyKeysOf(draft.getFactKey()).contains(existingKey);
            if (sameFamily && !draft.getFactKey().equals(existingKey)) {
                existing.setFactKey(draft.getFactKey());
            }
        }
        int oldImp = existing.getImportance() == null ? 0 : existing.getImportance();
        int newImp = draft.getImportance() == null ? 0 : draft.getImportance();
        existing.setImportance(Math.max(oldImp, newImp));
        if (draft.getRemark() != null) {
            existing.setRemark(draft.getRemark());
        }
        // 抽取路径必须覆盖：本次 ttl_days=0 时要把旧的过期时间清成永久，而不是保留旧值
        if (overrideExpired || draft.getExpiredTime() != null) {
            existing.setExpiredTime(draft.getExpiredTime());
        }
        items.update(existing, operatorId);
        // 可观测：INFO 只留事实键 / 主键 / 重要度 / 长度 / 内容哈希前缀。
        // 用户记忆正文属 PII，禁止进 INFO；需要看差异时把本类日志调到 debug（也只打 40 字摘要）。
        if (!Objects.equals(oldContent, existing.getContent())) {
            log.info("[MEMORY] LONG覆盖 factKey={} memoryId={} importance={} oldLen={} newLen={} hash={}",
                    existing.getFactKey(), existing.getId(), existing.getImportance(),
                    lengthOf(oldContent), lengthOf(existing.getContent()), hashPrefix(existing.getContentHash()));
            if (log.isDebugEnabled()) {
                log.debug("[MEMORY] LONG覆盖正文摘要 factKey={} memoryId={} old=\"{}\" new=\"{}\"",
                        existing.getFactKey(), existing.getId(),
                        preview(oldContent), preview(existing.getContent()));
            }
        } else {
            log.debug("[MEMORY] LONG同值重写 factKey={} memoryId={}",
                    existing.getFactKey(), existing.getId());
        }
    }

    /**
     * 正文字符数，供 INFO 日志替代正文本身。
     *
     * @param content 正文，可空
     * @return 字符数；null 记 0
     */
    private static int lengthOf(String content) {
        return content == null ? 0 : content.length();
    }

    /**
     * 内容哈希前缀：在不打正文的前提下判断「是否同一段内容」。
     *
     * @param hash {@code memory_item.content_hash}，可空
     * @return 前 8 位；空值返回 "-"
     */
    private static String hashPrefix(String hash) {
        if (hash == null || hash.isBlank()) {
            return "-";
        }
        String value = hash.trim();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    /**
     * 正文摘要：压成一行并截断，避免多行正文把日志撑爆。<b>仅 debug 使用</b>，不参与 INFO 输出。
     *
     * @param content 正文，可空
     * @return 单行摘要
     */
    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        String one = content.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= CONTENT_PREVIEW_CHARS ? one : one.substring(0, CONTENT_PREVIEW_CHARS) + "...";
    }
}
