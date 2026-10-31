package com.agenthub.tool.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.tool.api.dto.ItemBatchStatusReq;
import com.agenthub.tool.api.dto.ItemBatchStatusResp;
import com.agenthub.tool.api.dto.ItemCreateReq;
import com.agenthub.tool.api.dto.ItemPageReq;
import com.agenthub.tool.api.dto.ItemResp;
import com.agenthub.tool.api.dto.ItemStatusReq;
import com.agenthub.tool.api.dto.ItemUpdateReq;
import com.agenthub.tool.application.ToolItemAppService;
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
 * 工具项接口。所属域：tool。白名单启停使用独立权限，不并进编辑。
 */
@RestController
@RequestMapping("/api/tool/items")
public class ToolItemController {

    private final ToolItemAppService items;

    /**
     * @param items 工具项用例
     */
    public ToolItemController(ToolItemAppService items) {
        this.items = items;
    }

    /**
     * @param req 分页、源、类型、启停、关键词
     * @return 一页工具项
     */
    @SaCheckPermission(ToolPermissions.ITEM_LIST)
    @GetMapping
    public Result<PageResult<ItemResp>> page(@Valid ItemPageReq req) {
        return Result.ok(items.page(req));
    }

    /**
     * @param id 工具项 id
     * @return 详情
     */
    @SaCheckPermission(ToolPermissions.ITEM_LIST)
    @GetMapping("/{id}")
    public Result<ItemResp> get(@PathVariable Long id) {
        return Result.ok(items.get(id));
    }

    /**
     * @param req 新工具项，状态由服务固定为停用
     * @return 详情
     */
    @SaCheckPermission(ToolPermissions.ITEM_ADD)
    @PostMapping
    public Result<ItemResp> create(@Valid @RequestBody ItemCreateReq req) {
        return Result.ok(items.create(req));
    }

    /**
     * @param id  工具项 id
     * @param req 元数据
     * @return 更新后的详情
     */
    @SaCheckPermission(ToolPermissions.ITEM_EDIT)
    @PutMapping("/{id}")
    public Result<ItemResp> update(@PathVariable Long id, @Valid @RequestBody ItemUpdateReq req) {
        return Result.ok(items.update(id, req));
    }

    /**
     * @param id 工具项 id
     * @return 空数据
     */
    @SaCheckPermission(ToolPermissions.ITEM_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        items.remove(id);
        return Result.ok();
    }

    /**
     * @param id  工具项 id
     * @param req 目标状态
     * @return 更新后的详情
     */
    @SaCheckPermission(ToolPermissions.ITEM_ENABLE)
    @PutMapping("/{id}/status")
    public Result<ItemResp> changeStatus(@PathVariable Long id, @Valid @RequestBody ItemStatusReq req) {
        return Result.ok(items.changeStatus(id, req));
    }

    /**
     * 源内批量启停：一次把该 MCP 源下全部未删除工具项置为启用或停用。
     * 复用单条启停的 permission，避免新增菜单种子；内置源与非 MCP 源由服务层拒绝。
     * 路径用 batch-status 而非 batch/status，避免与单条 {@code /{id}/status} 的路径变量混淆。
     *
     * @param req 工具源 id 与目标状态
     * @return 目标状态与实际更新行数
     */
    @SaCheckPermission(ToolPermissions.ITEM_ENABLE)
    @PutMapping("/batch-status")
    public Result<ItemBatchStatusResp> changeAllStatus(@Valid @RequestBody ItemBatchStatusReq req) {
        return Result.ok(items.changeAllStatus(req.getSourceId(), toStatusReq(req.getStatus())));
    }

    /**
     * 批量入参复用到单条启停的用例签名上，只携带 status。
     *
     * @param status 目标状态
     * @return 单条启停入参
     */
    private ItemStatusReq toStatusReq(Integer status) {
        ItemStatusReq req = new ItemStatusReq();
        req.setStatus(status);
        return req;
    }
}
