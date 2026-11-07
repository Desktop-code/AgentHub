package com.agenthub.memory.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.memory.api.dto.MemoryItemPageReq;
import com.agenthub.memory.api.dto.MemoryItemReq;
import com.agenthub.memory.api.dto.MemoryItemResp;
import com.agenthub.memory.api.dto.MemoryStatusReq;
import com.agenthub.memory.application.MemoryItemAppService;
import com.agenthub.memory.domain.model.MemoryItem;
import com.agenthub.memory.domain.model.MemoryQuery;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆条目接口。所属域：memory。校验和双写不在这里。
 */
@RestController
@RequestMapping("/api/memory/items")
public class MemoryItemController {

    private final MemoryItemAppService items;

    /**
     * @param items 条目用例
     */
    public MemoryItemController(MemoryItemAppService items) {
        this.items = items;
    }

    /**
     * @param req 分页和筛选
     * @return 以数据库为准的一页
     */
    @SaCheckPermission(MemoryPermissions.ITEM_LIST)
    @GetMapping
    public Result<PageResult<MemoryItemResp>> page(@Valid MemoryItemPageReq req) {
        MemoryQuery query = new MemoryQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setAgentId(req.getAgentId());
        query.setScope(req.getScope());
        query.setSessionId(req.getSessionId());
        query.setUserId(req.getUserId());
        query.setStatus(req.getStatus());
        query.setLifeStatus(req.getLifeStatus());
        query.setCreateTimeFrom(req.getCreateTimeFrom());
        query.setCreateTimeTo(req.getCreateTimeTo());
        PageResult<MemoryItem> raw = items.page(query);
        return Result.ok(PageResult.of(raw.getTotal(), raw.getCurrent(), raw.getSize(),
                raw.getRecords().stream().map(MemoryItemController::toResp).toList()));
    }

    /**
     * @param id 主键
     * @return 详情
     */
    @SaCheckPermission(MemoryPermissions.ITEM_LIST)
    @GetMapping("/{id}")
    public Result<MemoryItemResp> get(@PathVariable Long id) {
        return Result.ok(toResp(items.get(id)));
    }

    /**
     * @param req 新记忆
     * @return 保存结果
     */
    @SaCheckPermission(MemoryPermissions.ITEM_ADD)
    @PostMapping
    public Result<MemoryItemResp> create(@Valid @RequestBody MemoryItemReq req) {
        return Result.ok(toResp(items.create(toItem(req))));
    }

    /**
     * @param id  主键
     * @param req 新内容
     * @return 更新后的条目
     */
    @SaCheckPermission(MemoryPermissions.ITEM_EDIT)
    @PutMapping("/{id}")
    public Result<MemoryItemResp> update(@PathVariable Long id, @Valid @RequestBody MemoryItemReq req) {
        return Result.ok(toResp(items.update(id, toItem(req))));
    }

    /**
     * @param id 主键
     * @return 空数据
     */
    @SaCheckPermission(MemoryPermissions.ITEM_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        items.remove(id);
        return Result.ok();
    }

    /**
     * @param id  主键
     * @param req 目标状态
     * @return 更新后的条目
     */
    @SaCheckPermission(MemoryPermissions.ITEM_ENABLE)
    @PutMapping("/{id}/status")
    public Result<MemoryItemResp> changeStatus(@PathVariable Long id, @Valid @RequestBody MemoryStatusReq req) {
        return Result.ok(toResp(items.changeStatus(id, req.getStatus())));
    }

    private static MemoryItem toItem(MemoryItemReq req) {
        MemoryItem item = new MemoryItem();
        item.setAgentId(req.getAgentId());
        item.setUserId(req.getUserId());
        item.setAppId(req.getAppId());
        item.setSessionId(req.getSessionId());
        item.setScope(req.getScope());
        item.setTitle(req.getTitle());
        item.setFactKey(req.getFactKey());
        item.setContent(req.getContent());
        item.setImportance(req.getImportance());
        item.setStatus(req.getStatus());
        item.setExpiredTime(req.getExpiredTime());
        item.setRemark(req.getRemark());
        return item;
    }

    /**
     * @param item 领域对象
     * @return 接口出参，不含哈希
     */
    public static MemoryItemResp toResp(MemoryItem item) {
        MemoryItemResp resp = new MemoryItemResp();
        resp.setId(item.getId());
        resp.setAgentId(item.getAgentId());
        resp.setUserId(item.getUserId());
        resp.setAppId(item.getAppId());
        resp.setSessionId(item.getSessionId());
        resp.setScope(item.getScope());
        resp.setTitle(item.getTitle());
        resp.setFactKey(item.getFactKey());
        resp.setContent(item.getContent());
        resp.setImportance(item.getImportance());
        resp.setStatus(item.getStatus());
        resp.setExpiredTime(item.getExpiredTime());
        resp.setRemark(item.getRemark());
        // 同步状态只读透出：列表/详情都展示，不允许通过条目写接口回写
        resp.setVectorSyncStatus(item.getVectorSyncStatus());
        resp.setVectorSyncFailCount(item.getVectorSyncFailCount());
        resp.setVectorSyncError(item.getVectorSyncError());
        resp.setCreateTime(item.getCreateTime());
        resp.setUpdateTime(item.getUpdateTime());
        return resp;
    }
}
