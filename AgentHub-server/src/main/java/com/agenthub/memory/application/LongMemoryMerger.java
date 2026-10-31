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

import java.util.Optional;

/**
 * LONG 同键合并写入。所属域：memory。
 * 维度 agent_id+user_id+LONG+fact_key；content_hash 作全文完全相同短路径。
 * 事务内按键查询再写，禁止同键双插。
 */
@Service
public class LongMemoryMerger {

    private static final Logger log = LoggerFactory.getLogger(LongMemoryMerger.class);

    private final MemoryItemRepository items;
    private final FactKeyNormalizer factKeys;

    /**
     * @param items    条目仓储
     * @param factKeys fact_key 归一
     */
    public LongMemoryMerger(MemoryItemRepository items, FactKeyNormalizer factKeys) {
        this.items = items;
        this.factKeys = factKeys;
    }

    /**
     * 抽取路径：必须带有效 fact_key；失败跳过并打点日志，禁止无 key 插入。
     *
     * @param agentId    Agent
     * @param userId     用户
     * @param draft      抽取草稿（含 factKey/title/content/importance）
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
        item.setStatus(1);
        boolean inserted = merge(item, operatorId, true);
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
            merge(draft, operatorId, true);
        } else {
            draft.setFactKey(null);
            merge(draft, operatorId, false);
        }
        Long id = draft.getId();
        if (id == null) {
            return draft;
        }
        return items.findById(id).orElse(draft);
    }

    /**
     * 先按 fact_key 查再写；未命中再走 content_hash；都未命中才插入。
     *
     * @param draft       待写入（scope 应为 LONG）
     * @param operatorId  操作人
     * @param hasFactKey  本次是否带有效 fact_key
     * @return true=insert，false=update
     */
    private boolean merge(MemoryItem draft, long operatorId, boolean hasFactKey) {
        // 同键优先：命中则覆盖 content，importance 取 max
        if (hasFactKey && draft.getFactKey() != null) {
            MemoryItem byKey = items.findActiveByFactKey(
                    draft.getAgentId(), draft.getUserId(), MemoryScope.LONG.name(), draft.getFactKey())
                    .orElse(null);
            if (byKey != null) {
                applyUpdate(byKey, draft, operatorId);
                draft.setId(byKey.getId());
                return false;
            }
        }
        // 全文完全相同短路径，与 fact_key 互补
        MemoryItem byHash = items.findActiveByHash(
                draft.getAgentId(), draft.getUserId(), MemoryScope.LONG.name(), draft.getContentHash())
                .orElse(null);
        if (byHash != null) {
            // 旧行无 key、本次有 key 时补写，便于后续同键合并
            if (hasFactKey && (byHash.getFactKey() == null || byHash.getFactKey().isBlank())) {
                byHash.setFactKey(draft.getFactKey());
            }
            applyUpdate(byHash, draft, operatorId);
            draft.setId(byHash.getId());
            return false;
        }
        Long id = items.insert(draft, operatorId);
        draft.setId(id);
        return true;
    }

    /**
     * 冲突策略：content/title/hash 以本次为准；importance 取较大值。
     */
    private void applyUpdate(MemoryItem existing, MemoryItem draft, long operatorId) {
        if (draft.getTitle() != null) {
            existing.setTitle(draft.getTitle());
        }
        existing.setContent(draft.getContent());
        existing.setContentHash(draft.getContentHash());
        int oldImp = existing.getImportance() == null ? 0 : existing.getImportance();
        int newImp = draft.getImportance() == null ? 0 : draft.getImportance();
        existing.setImportance(Math.max(oldImp, newImp));
        if (draft.getRemark() != null) {
            existing.setRemark(draft.getRemark());
        }
        if (draft.getExpiredTime() != null) {
            existing.setExpiredTime(draft.getExpiredTime());
        }
        items.update(existing, operatorId);
    }
}
