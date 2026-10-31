package com.agenthub.tool.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.Result;
import com.agenthub.tool.api.dto.ResetAuthReq;
import com.agenthub.tool.api.dto.SourceCreateReq;
import com.agenthub.tool.api.dto.SourceProbeReq;
import com.agenthub.tool.api.dto.SourceProbeResp;
import com.agenthub.tool.api.dto.SourceResp;
import com.agenthub.tool.api.dto.SourceStatusReq;
import com.agenthub.tool.api.dto.SourceUpdateReq;
import com.agenthub.tool.api.dto.SyncResp;
import com.agenthub.tool.application.ToolSourceAppService;
import com.agenthub.tool.application.ToolSyncAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具源接口。所属域：tool。加密、同步协议和删除规则不在这里。
 */
@RestController
@RequestMapping("/api/tool/sources")
public class ToolSourceController {

    private final ToolSourceAppService sources;
    private final ToolSyncAppService sync;

    /**
     * @param sources 工具源用例
     * @param sync    MCP 同步用例
     */
    public ToolSourceController(ToolSourceAppService sources, ToolSyncAppService sync) {
        this.sources = sources;
        this.sync = sync;
    }

    /**
     * @return 卡片列表，只有掩码
     */
    @SaCheckPermission(ToolPermissions.SOURCE_LIST)
    @GetMapping
    public Result<List<SourceResp>> list() {
        return Result.ok(sources.list());
    }

    /**
     * @param id 工具源 id
     * @return 详情
     */
    @SaCheckPermission(ToolPermissions.SOURCE_LIST)
    @GetMapping("/{id}")
    public Result<SourceResp> get(@PathVariable Long id) {
        return Result.ok(sources.get(id));
    }

    /**
     * @param req 新 MCP 源
     * @return 详情，只含掩码
     */
    @SaCheckPermission(ToolPermissions.SOURCE_ADD)
    @PostMapping
    public Result<SourceResp> create(@Valid @RequestBody SourceCreateReq req) {
        return Result.ok(sources.create(req));
    }

    /**
     * 新建 / 编辑弹窗测 SSE 链接。不写库；新建与编辑权限任一即可。
     *
     * @param req 地址与可选鉴权头；编辑可带 sourceId 复用库存头
     * @return 探测结果，失败也 success=false
     */
    @SaCheckPermission(value = {ToolPermissions.SOURCE_ADD, ToolPermissions.SOURCE_EDIT}, mode = SaMode.OR)
    @PostMapping("/probe")
    public Result<SourceProbeResp> probe(@Valid @RequestBody SourceProbeReq req) {
        return Result.ok(sync.probe(req));
    }

    /**
     * @param id  工具源 id
     * @param req 不含鉴权头的资料
     * @return 更新后的详情
     */
    @SaCheckPermission(ToolPermissions.SOURCE_EDIT)
    @PutMapping("/{id}")
    public Result<SourceResp> update(@PathVariable Long id, @Valid @RequestBody SourceUpdateReq req) {
        return Result.ok(sources.update(id, req));
    }

    /**
     * @param id 工具源 id
     * @return 空数据
     */
    @SaCheckPermission(ToolPermissions.SOURCE_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        sources.remove(id);
        return Result.ok();
    }

    /**
     * @param id  工具源 id
     * @param req 目标状态
     * @return 更新后的详情
     */
    @SaCheckPermission(ToolPermissions.SOURCE_ENABLE)
    @PutMapping("/{id}/status")
    public Result<SourceResp> changeStatus(@PathVariable Long id, @Valid @RequestBody SourceStatusReq req) {
        return Result.ok(sources.changeStatus(id, req));
    }

    /**
     * @param id 工具源 id
     * @return 同步计数；远端失败时 success 为 false
     */
    @SaCheckPermission(ToolPermissions.SOURCE_SYNC)
    @PostMapping("/{id}/sync")
    public Result<SyncResp> sync(@PathVariable Long id) {
        return Result.ok(sync.sync(id));
    }

    /**
     * @param id  工具源 id
     * @param req 新鉴权头
     * @return 只含新掩码的详情
     */
    @SaCheckPermission(ToolPermissions.SOURCE_EDIT)
    @PostMapping("/{id}/reset-auth")
    public Result<SourceResp> resetAuth(@PathVariable Long id, @Valid @RequestBody ResetAuthReq req) {
        return Result.ok(sources.resetAuth(id, req));
    }
}
