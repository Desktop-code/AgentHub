package com.agenthub.tool.application;

import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.spi.cache.AgentDictCacheInvalidator;
import com.agenthub.spi.crypto.SecretContexts;
import com.agenthub.spi.crypto.SecretStore;
import com.agenthub.tool.api.dto.ResetAuthReq;
import com.agenthub.tool.api.dto.SourceCreateReq;
import com.agenthub.tool.api.dto.SourceResp;
import com.agenthub.tool.api.dto.SourceStatusReq;
import com.agenthub.tool.api.dto.SourceUpdateReq;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.domain.service.ToolReferencePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工具源用例。所属域：tool。
 * 新建只接受 MCP。鉴权头加密后入库；任何接口都只回掩码，不回明文。
 */
@Service
public class ToolSourceAppService {

    private static final int CIPHER_MAX = 1024;
    private static final int MASK_MAX = 64;

    private final ToolSourceRepository sources;
    private final ToolItemRepository items;
    private final ToolLookup lookup;
    private final ToolReferencePolicy references;
    private final SecretStore secretStore;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;
    private final AgentDictCacheInvalidator dictCache;

    /**
     * @param sources         工具源
     * @param items           删除源时连带逻辑删除工具项
     * @param lookup          按 id 取值
     * @param references      内置源保护
     * @param secretStore     与模型共用的 AES-GCM
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志，摘要不含鉴权头
     * @param dictCache       运行期装配字典缓存失效（提交后执行）
     */
    public ToolSourceAppService(ToolSourceRepository sources, ToolItemRepository items, ToolLookup lookup,
                                ToolReferencePolicy references, SecretStore secretStore,
                                OperatorContext operatorContext, OperLogWriter operLogWriter,
                                AgentDictCacheInvalidator dictCache) {
        this.sources = sources;
        this.items = items;
        this.lookup = lookup;
        this.references = references;
        this.secretStore = secretStore;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
        this.dictCache = dictCache;
    }

    /**
     * @return 卡片列表，只有掩码
     */
    public List<SourceResp> list() {
        return sources.listAll().stream().map(this::toResp).toList();
    }

    /**
     * @param id 工具源 id
     * @return 详情，只有掩码
     */
    public SourceResp get(Long id) {
        return toResp(lookup.requireSource(id));
    }

    /**
     * @param req 新 MCP 源
     * @return 详情，只含掩码；鉴权头明文只用于本次入库，不回传
     */
    @Transactional
    public SourceResp create(SourceCreateReq req) {
        String name = ToolChecks.requiredText(req.getName(), 64, "名称");
        assertNameFree(name, null);
        ToolSource source = new ToolSource();
        source.setName(name);
        source.setType(ToolChecks.creatableType(req.getType()).name());
        source.setTransport(ToolChecks.transport(req.getTransport()));
        source.setBaseUrl(ToolChecks.baseUrl(req.getBaseUrl()));
        source.setStatus(ToolChecks.sourceStatus(req.getStatus()));
        source.setRemark(ToolChecks.optionalText(req.getRemark(), 500, "备注"));
        // 明文只用于加密与算掩码，方法结束即失去引用
        String plain = ToolChecks.optionalHeader(req.getAuthHeader());
        // 密文 AAD 要绑 sourceId，而 id 由自增生成：先插入，再回写密文与掩码
        Long id = sources.insert(source, operatorContext.requireUserId());
        if (plain != null) {
            StoredHeader stored = storeHeader(plain, id);
            sources.updateAuth(id, stored.cipher(), stored.mask(), operatorContext.requireUserId());
        }
        operLogWriter.write("tool", "create", "tool_source", String.valueOf(id), "创建工具源 " + source.getName());
        return get(id);
    }

    /**
     * @param id  工具源 id
     * @param req 不含鉴权头的资料
     * @return 更新后的详情
     */
    @Transactional
    public SourceResp update(Long id, SourceUpdateReq req) {
        ToolSource existing = lookup.requireSource(id);
        references.assertMutable(existing);
        String name = ToolChecks.requiredText(req.getName(), 64, "名称");
        assertNameFree(name, id);
        existing.setName(name);
        // 编辑可改传输；未传时沿用现有值再规范化（空仍按 SSE）
        String transportRaw = req.getTransport() != null && !req.getTransport().isBlank()
                ? req.getTransport()
                : existing.getTransport();
        existing.setTransport(ToolChecks.transport(transportRaw));
        existing.setBaseUrl(ToolChecks.baseUrl(req.getBaseUrl()));
        existing.setRemark(ToolChecks.optionalText(req.getRemark(), 500, "备注"));
        sources.updateProfile(existing, operatorContext.requireUserId());
        // 源名称会进装配说明，类型/启停决定整源可用性：提交后失效源快照与其下工具项
        dictCache.evictToolSource(id);
        operLogWriter.write("tool", "update", "tool_source", String.valueOf(id), "更新工具源 " + existing.getName());
        return get(id);
    }

    /**
     * 工具源名称冲突则抛 DUPLICATE（未删除范围内全局唯一，含 BUILTIN）。
     *
     * @param name      名称
     * @param excludeId 更新时排除自身
     */
    private void assertNameFree(String name, Long excludeId) {
        if (sources.nameExists(name, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "工具源名称已存在");
        }
    }

    /**
     * @param id  工具源 id
     * @param req 目标状态
     * @return 更新后的详情
     */
    @Transactional
    public SourceResp changeStatus(Long id, SourceStatusReq req) {
        ToolSource existing = lookup.requireSource(id);
        references.assertMutable(existing);
        int status = ToolChecks.exactStatus(req.getStatus());
        existing.setStatus(status);
        sources.updateProfile(existing, operatorContext.requireUserId());
        // 源停用等价整源下线：必须让缓存失效，否则停用后本轮仍能装配出工具
        dictCache.evictToolSource(id);
        String action = status == 1 ? "enable" : "disable";
        operLogWriter.write("tool", action, "tool_source", String.valueOf(id), "更新工具源状态 " + existing.getName());
        return get(id);
    }

    /**
     * 逻辑删除源及其下未删除的工具项。内置源拒绝。
     *
     * @param id 工具源 id
     */
    @Transactional
    public void remove(Long id) {
        ToolSource existing = lookup.requireSource(id);
        references.assertDeletable(existing);
        Long operatorId = operatorContext.requireUserId();
        // 删除前先记下源下工具项 id：逻辑删除后已查不到，否则这些键只能等 TTL 过期
        List<Long> itemIds = items.listBySourceId(id).stream().map(ToolItem::getId).toList();
        items.logicalDeleteBySourceId(id, operatorId);
        sources.logicalDelete(id, operatorId);
        dictCache.evictToolItems(itemIds);
        dictCache.evictToolSource(id);
        operLogWriter.write("tool", "remove", "tool_source", String.valueOf(id), "删除工具源 " + existing.getName());
    }

    /**
     * @param id  工具源 id
     * @param req 新的鉴权头明文
     * @return 详情，只含新掩码；明文不回传
     */
    @Transactional
    public SourceResp resetAuth(Long id, ResetAuthReq req) {
        ToolSource existing = lookup.requireSource(id);
        references.assertMutable(existing);
        // 明文仅用于重新加密与重算掩码；AAD 仍绑本源 id
        String plain = ToolChecks.requiredHeader(req.getAuthHeader());
        StoredHeader stored = storeHeader(plain, id);
        sources.updateAuth(id, stored.cipher(), stored.mask(), operatorContext.requireUserId());
        operLogWriter.write("tool", "resetAuth", "tool_source", String.valueOf(id), "重置工具源鉴权头 " + existing.getName());
        return get(id);
    }

    /**
     * 密文超列宽时拒绝，不把截断的密文写进库。
     *
     * @param plain  鉴权头明文
     * @param sourceId 源 id，作为 AAD 绑定，防止密文跨源搬动
     * @return 密文与掩码
     */
    private StoredHeader storeHeader(String plain, Long sourceId) {
        String cipher = secretStore.encrypt(plain, SecretContexts.toolSource(sourceId));
        if (cipher.length() > CIPHER_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "鉴权头过长");
        }
        String mask = secretStore.mask(plain);
        if (mask.length() > MASK_MAX) {
            mask = mask.substring(0, MASK_MAX);
        }
        return new StoredHeader(cipher, mask);
    }

    /**
     * 组装出参。只填掩码，不存在明文字段，调用方无需再传一次性明文。
     */
    private SourceResp toResp(ToolSource source) {
        SourceResp resp = new SourceResp();
        resp.setId(source.getId());
        resp.setName(source.getName());
        resp.setType(source.getType());
        resp.setTransport(source.getTransport());
        resp.setBaseUrl(source.getBaseUrl());
        resp.setAuthHeaderMask(source.getAuthHeaderMask());
        resp.setStatus(source.getStatus());
        resp.setRemark(source.getRemark());
        resp.setLastSyncTime(source.getLastSyncTime());
        resp.setLastSyncOk(source.getLastSyncOk());
        resp.setLastSyncMsg(source.getLastSyncMsg());
        resp.setCreateTime(source.getCreateTime());
        resp.setUpdateTime(source.getUpdateTime());
        return resp;
    }

    private record StoredHeader(String cipher, String mask) {
    }
}
