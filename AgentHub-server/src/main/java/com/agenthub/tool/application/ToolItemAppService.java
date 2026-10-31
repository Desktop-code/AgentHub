package com.agenthub.tool.application;

import com.agenthub.admin.domain.enums.RecordStatus;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import com.agenthub.tool.api.dto.ItemBatchStatusResp;
import com.agenthub.tool.api.dto.ItemCreateReq;
import com.agenthub.tool.api.dto.ItemPageReq;
import com.agenthub.tool.api.dto.ItemResp;
import com.agenthub.tool.api.dto.ItemStatusReq;
import com.agenthub.tool.api.dto.ItemUpdateReq;
import com.agenthub.tool.domain.model.ItemQuery;
import com.agenthub.tool.domain.model.ToolItem;
import com.agenthub.tool.domain.model.ToolSource;
import com.agenthub.tool.domain.model.ToolSourceType;
import com.agenthub.tool.domain.repository.ToolItemRepository;
import com.agenthub.tool.domain.repository.ToolSourceRepository;
import com.agenthub.tool.domain.service.ToolReferencePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具项用例。所属域：tool。
 * 新建一律停用，启用必须走启停接口，避免用新增权限绕过白名单。
 * 源内批量启停只改 status，作用于该 MCP 源下未删除项，不影响其它源。
 */
@Service
public class ToolItemAppService {

    private final ToolItemRepository items;
    private final ToolSourceRepository sources;
    private final ToolLookup lookup;
    private final ToolReferencePolicy references;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param items           工具项
     * @param sources         补来源名称和类型
     * @param lookup          按 id 取值
     * @param references      同名检查
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     */
    public ToolItemAppService(ToolItemRepository items, ToolSourceRepository sources, ToolLookup lookup,
                              ToolReferencePolicy references, OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.items = items;
        this.sources = sources;
        this.lookup = lookup;
        this.references = references;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * @param req 分页和筛选
     * @return 一页工具项，带上来源类型
     */
    public PageResult<ItemResp> page(ItemPageReq req) {
        ItemQuery query = new ItemQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setSourceId(req.getSourceId());
        query.setKeyword(req.getKeyword());
        if (req.getStatus() != null) {
            query.setStatus(ToolChecks.exactStatus(req.getStatus()));
        }
        ToolSourceType type = ToolChecks.optionalType(req.getType());
        if (type != null) {
            query.setSourceIds(sources.listIdsByType(type.name()));
        }
        PageResult<ToolItem> page = items.page(query);
        Map<Long, ToolSource> sourceMap = sources.listAll().stream()
                .collect(Collectors.toMap(ToolSource::getId, source -> source, (left, right) -> left));
        List<ItemResp> records = page.getRecords().stream().map(item -> toResp(item, sourceMap.get(item.getSourceId()))).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    /**
     * @param id 工具项 id
     * @return 详情
     */
    public ItemResp get(Long id) {
        ToolItem item = lookup.requireItem(id);
        return toResp(item, sources.findById(item.getSourceId()).orElse(null));
    }

    /**
     * @param req 新工具项
     * @return 详情，状态为停用
     */
    @Transactional
    public ItemResp create(ItemCreateReq req) {
        ToolSource source = lookup.requireSource(req.getSourceId());
        String name = ToolChecks.requiredText(req.getName(), 128, "名称");
        references.assertNameAvailable(items.findBySourceAndName(source.getId(), name).isPresent());
        ToolItem item = new ToolItem();
        item.setSourceId(source.getId());
        item.setName(name);
        item.setTitle(ToolChecks.optionalText(req.getTitle(), 128, "展示名"));
        item.setDescription(ToolChecks.optionalText(req.getDescription(), 1000, "说明"));
        item.setInputSchemaJson(ToolChecks.schemaJson(req.getInputSchemaJson()));
        item.setStatus(RecordStatus.DISABLED.getCode());
        Long id = items.insert(item, operatorContext.requireUserId());
        operLogWriter.write("tool", "create", "tool_item", String.valueOf(id), "创建工具项 " + name);
        return get(id);
    }

    /**
     * @param id  工具项 id
     * @param req 标题、说明和 schema
     * @return 更新后的详情
     */
    @Transactional
    public ItemResp update(Long id, ItemUpdateReq req) {
        ToolItem existing = lookup.requireItem(id);
        existing.setTitle(ToolChecks.optionalText(req.getTitle(), 128, "展示名"));
        existing.setDescription(ToolChecks.optionalText(req.getDescription(), 1000, "说明"));
        existing.setInputSchemaJson(ToolChecks.schemaJson(req.getInputSchemaJson()));
        items.updateMetadata(existing, operatorContext.requireUserId());
        operLogWriter.write("tool", "update", "tool_item", String.valueOf(id), "更新工具项 " + existing.getName());
        return get(id);
    }

    /**
     * @param id  工具项 id
     * @param req 目标状态
     * @return 更新后的详情
     */
    @Transactional
    public ItemResp changeStatus(Long id, ItemStatusReq req) {
        ToolItem existing = lookup.requireItem(id);
        int status = ToolChecks.exactStatus(req.getStatus());
        items.updateStatus(id, status, operatorContext.requireUserId());
        String action = status == 1 ? "enable" : "disable";
        operLogWriter.write("tool", action, "tool_item", String.valueOf(id), "更新工具项状态 " + existing.getName());
        return get(id);
    }

    /**
     * 源内批量启停：一次改动该 MCP 源下全部未删除工具项。
     * 只作用于 sourceId 圈定的行，不下架、不改 schema，也不碰 BUILTIN 与其它源。
     *
     * @param sourceId 工具源 id
     * @param req      目标状态，1 全部启用 / 0 全部停用
     * @return 目标状态与实际更新行数
     * @throws BizException 源不存在、或不是 MCP 源
     */
    @Transactional
    public ItemBatchStatusResp changeAllStatus(Long sourceId, ItemStatusReq req) {
        ToolSource source = lookup.requireSource(sourceId);
        // 内置源由种子维护，批量启停会绕开只读约束，直接拒绝
        if (!ToolSourceType.MCP.name().equals(source.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有 MCP 工具源可以批量启停工具项");
        }
        int status = ToolChecks.exactStatus(req.getStatus());
        Long operatorId = operatorContext.requireUserId();
        int updated = items.updateStatusBySourceId(sourceId, status, operatorId);
        String action = status == 1 ? "enableAll" : "disableAll";
        operLogWriter.write("tool", action, "tool_source", String.valueOf(sourceId),
                "批量更新工具源 " + source.getName() + " 下工具项状态为 " + status + "，共 " + updated + " 条");
        return ItemBatchStatusResp.of(sourceId, status, updated);
    }

    /**
     * @param id 工具项 id
     */
    @Transactional
    public void remove(Long id) {
        ToolItem existing = lookup.requireItem(id);
        items.logicalDelete(id, operatorContext.requireUserId());
        operLogWriter.write("tool", "remove", "tool_item", String.valueOf(id), "删除工具项 " + existing.getName());
    }

    private ItemResp toResp(ToolItem item, ToolSource source) {
        ItemResp resp = new ItemResp();
        resp.setId(item.getId());
        resp.setSourceId(item.getSourceId());
        resp.setName(item.getName());
        resp.setTitle(item.getTitle());
        resp.setDescription(item.getDescription());
        resp.setInputSchemaJson(item.getInputSchemaJson());
        resp.setStatus(item.getStatus());
        resp.setCreateTime(item.getCreateTime());
        resp.setUpdateTime(item.getUpdateTime());
        if (source != null) {
            resp.setSourceName(source.getName());
            resp.setSourceType(source.getType());
        }
        return resp;
    }
}
