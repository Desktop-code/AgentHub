package com.agenthub.memory.application;

import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryPolicy;
import com.agenthub.memory.domain.model.MemoryQuery;
import com.agenthub.memory.domain.model.MemoryScope;
import com.agenthub.memory.domain.repository.MemoryItemRepository;
import com.agenthub.memory.domain.service.MemoryTexts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 记忆条目用例。所属域：memory。
 * SHORT 走双写；LONG / AGENT 只写 MySQL。操作日志只记 id 和动作，不记正文。
 */
@Service
public class MemoryItemAppService {

    private final MemoryItemRepository items;
    private final MemoryPolicyAppService policies;
    private final ShortMemoryWriter shorts;
    private final LongMemoryMerger longMerger;
    private final AgentDefRepository agents;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final MemoryVectorHook vectors;

    /**
     * @param items           条目仓储
     * @param policies        条数和 TTL 策略
     * @param shorts          SHORT 双写
     * @param longMerger      LONG fact_key / hash 合并
     * @param agents          填写了 agentId 时必须存在
     * @param operatorContext 操作人
     * @param operLogWriter   审计
     * @param vectors         LONG 向量双写统一挂载点（只处理 LONG，提交后异步）
     */
    public MemoryItemAppService(MemoryItemRepository items, MemoryPolicyAppService policies, ShortMemoryWriter shorts,
                                LongMemoryMerger longMerger, AgentDefRepository agents, OperatorContext operatorContext,
                                OperLogWriter operLogWriter, MemoryVectorHook vectors) {
        this.items = items;
        this.policies = policies;
        this.shorts = shorts;
        this.longMerger = longMerger;
        this.agents = agents;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.vectors = vectors;
    }

    /**
     * 控制台列表查询。强制 agentId + scope，避免无维度全表扫描。
     *
     * @param query 分页筛选
     * @return 以数据库为准的一页
     * @throws BizException 未选 Agent 或记忆范围
     */
    public PageResult<MemoryItem> page(MemoryQuery query) {
        if (query.getAgentId() == null || query.getScope() == null || query.getScope().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请先选择 Agent 和记忆范围");
        }
        return items.page(query);
    }

    /**
     * @param id 主键
     * @return 条目
     * @throws BizException 不存在
     */
    public MemoryItem get(Long id) {
        return require(id);
    }

    /**
     * 控制台人工写入。LONG 按哈希合并，超限按重要程度淘汰。
     *
     * @param draft 新条目
     * @return 保存后的条目
     * @throws BizException 正文含密钥、范围不合法或 SHORT 双写失败
     */
    @Transactional
    public MemoryItem create(MemoryItem draft) {
        prepare(draft, true);
        long operatorId = operatorContext.requireUserId();
        if (MemoryScope.SHORT.name().equals(draft.getScope())) {
            MemoryPolicy policy = policies.effective(draft.getAgentId());
            MemoryItem saved = shorts.append(policy, draft, operatorId);
            operLogWriter.write("memory", "add", "memory_item", String.valueOf(saved.getId()), "新建记忆 " + saved.getId());
            return items.findById(saved.getId()).orElse(saved);
        }
        MemoryItem saved = upsertDurable(draft, operatorId);
        operLogWriter.write("memory", "add", "memory_item", String.valueOf(saved.getId()), "新建记忆 " + saved.getId());
        return saved;
    }

    /**
     * @param id    主键
     * @param draft 新内容
     * @return 更新后的条目
     */
    @Transactional
    public MemoryItem update(Long id, MemoryItem draft) {
        MemoryItem existing = require(id);
        draft.setId(id);
        draft.setScope(existing.getScope());
        prepare(draft, false);
        long operatorId = operatorContext.requireUserId();
        if (MemoryScope.SHORT.name().equals(existing.getScope())) {
            draft.setAgentId(draft.getAgentId() == null ? existing.getAgentId() : draft.getAgentId());
            draft.setSessionId(draft.getSessionId() == null ? existing.getSessionId() : draft.getSessionId());
            shorts.update(draft, operatorId);
        } else {
            items.update(draft, operatorId);
            evictDurable(draft);
        }
        operLogWriter.write("memory", "edit", "memory_item", String.valueOf(id), "编辑记忆 " + id);
        // 以库中整行为准挂向量双写：停用/过期会转成删向量，LONG 之外由 hook 自行忽略
        MemoryItem saved = items.findById(id).orElseThrow();
        vectors.afterSaved(saved);
        return saved;
    }

    /**
     * @param id 主键
     */
    @Transactional
    public void remove(Long id) {
        MemoryItem existing = require(id);
        long operatorId = operatorContext.requireUserId();
        if (MemoryScope.SHORT.name().equals(existing.getScope())) {
            shorts.remove(existing, operatorId);
        } else {
            items.logicalDelete(id, operatorId);
            // 逻辑删除是物理上下线：向量必须同步删除，否则召回仍会命中已删条目
            vectors.afterRemoved(List.of(id));
        }
        operLogWriter.write("memory", "remove", "memory_item", String.valueOf(id), "删除记忆 " + id);
    }

    /**
     * @param id     主键
     * @param status 1 启用，0 停用
     * @return 更新后的条目
     */
    @Transactional
    public MemoryItem changeStatus(Long id, int status) {
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 0 或 1");
        }
        MemoryItem existing = require(id);
        existing.setStatus(status);
        long operatorId = operatorContext.requireUserId();
        if (MemoryScope.SHORT.name().equals(existing.getScope())) {
            shorts.update(existing, operatorId);
        } else {
            items.update(existing, operatorId);
            // 停用=不该再被召回：hook 会按 status 决定删向量；启用则重新写入
            vectors.afterSaved(existing);
        }
        operLogWriter.write("memory", "enable", "memory_item", String.valueOf(id), "启停记忆 " + id);
        return items.findById(id).orElseThrow();
    }

    private MemoryItem upsertDurable(MemoryItem draft, long operatorId) {
        // LONG：有 fact_key 同键合并；无 key 仍走 hash 短路径（人工条不强制）
        if (MemoryScope.LONG.name().equals(draft.getScope())) {
            MemoryItem saved = longMerger.upsertManual(draft, operatorId);
            evictDurable(saved);
            return saved;
        }
        MemoryItem existing = items.findActiveByHash(draft.getAgentId(), draft.getUserId(), draft.getScope(),
                draft.getContentHash()).orElse(null);
        if (existing != null) {
            existing.setTitle(draft.getTitle());
            existing.setContent(draft.getContent());
            existing.setImportance(draft.getImportance());
            existing.setRemark(draft.getRemark());
            existing.setExpiredTime(draft.getExpiredTime());
            items.update(existing, operatorId);
            evictDurable(existing);
            // 以库中整行为准挂双写（AGENT 由 hook 自行忽略）
            MemoryItem saved = items.findById(existing.getId()).orElse(existing);
            vectors.afterSaved(saved);
            return saved;
        }
        Long id = items.insert(draft, operatorId);
        draft.setId(id);
        evictDurable(draft);
        MemoryItem saved = items.findById(id).orElse(draft);
        vectors.afterSaved(saved);
        return saved;
    }

    private void evictDurable(MemoryItem item) {
        if (item.getAgentId() == null) {
            return;
        }
        MemoryPolicy policy = policies.effective(item.getAgentId());
        int keep = MemoryScope.LONG.name().equals(item.getScope()) ? policy.getMaxLongItems() : policy.getMaxLongItems();
        if (MemoryScope.AGENT.name().equals(item.getScope())) {
            keep = policy.getMaxLongItems();
        }
        List<Long> evicted = items.evictOverflow(item.getAgentId(), item.getUserId(), item.getScope(), keep,
                operatorContext.requireUserId());
        // 只有 LONG 有向量；AGENT/SHORT 淘汰不涉及向量库
        if (MemoryScope.LONG.name().equals(item.getScope())) {
            vectors.afterRemoved(evicted);
        }
    }

    private void prepare(MemoryItem draft, boolean creating) {
        MemoryScope scope = MemoryScope.parse(draft.getScope());
        draft.setScope(scope.name());
        if (draft.getContent() == null || draft.getContent().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记忆正文不能为空");
        }
        if (draft.getContent().length() > 8000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记忆正文最长 8000");
        }
        MemoryTexts.rejectSecret(draft.getContent());
        draft.setContent(draft.getContent().trim());
        draft.setContentHash(MemoryTexts.hash(draft.getContent()));
        if (draft.getTitle() != null) {
            draft.setTitle(draft.getTitle().trim());
        }
        if (draft.getFactKey() != null) {
            String fk = draft.getFactKey().trim();
            draft.setFactKey(fk.isEmpty() ? null : fk);
        }
        int importance = draft.getImportance() == null ? 0 : draft.getImportance();
        if (importance < 0 || importance > 100) {
            throw new BizException(ErrorCode.BAD_REQUEST, "重要程度必须在 0 到 100");
        }
        draft.setImportance(importance);
        if (creating && draft.getStatus() == null) {
            draft.setStatus(1);
        }
        if (draft.getAgentId() != null && agents.findById(draft.getAgentId()).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "Agent 不存在");
        }
        if (scope == MemoryScope.SHORT && (draft.getAgentId() == null || draft.getSessionId() == null)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "短期记忆必须指定 Agent 和会话");
        }
    }

    private MemoryItem require(Long id) {
        return items.findById(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "记忆不存在"));
    }
}
