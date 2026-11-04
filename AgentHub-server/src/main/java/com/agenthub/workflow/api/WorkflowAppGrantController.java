package com.agenthub.workflow.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.admin.domain.enums.PermissionCodes;
import com.agenthub.common.api.Result;
import com.agenthub.workflow.api.dto.WorkflowAppGrantItemResp;
import com.agenthub.workflow.api.dto.WorkflowAppGrantSaveReq;
import com.agenthub.workflow.application.WorkflowAppGrantAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用-工作流授权接口。所属域：workflow 的 api。
 * <p>挂在控制台（Sa-Token 用户会话 + 权限码），不是 OpenAPI；
 * 入站触发见 {@link WorkflowOpenController}。
 * <p>权限沿用应用管理：查看 {@code system:app:list}，保存 {@code system:app:edit}，
 * 不新造权限码，避免角色配置里多一层无意义开关。
 */
@RestController
@RequestMapping("/api/workflow/app-grants")
public class WorkflowAppGrantController {

    private final WorkflowAppGrantAppService service;

    /**
     * @param service 授权用例
     */
    public WorkflowAppGrantController(WorkflowAppGrantAppService service) {
        this.service = service;
    }

    /**
     * 某个应用的工作流授权列表。
     *
     * @param appId 应用 id
     * @return 授权行（含定义名称 / 默认发布指针）
     */
    @SaCheckPermission(PermissionCodes.APP_LIST)
    @GetMapping
    public Result<List<WorkflowAppGrantItemResp>> list(@RequestParam Long appId) {
        return Result.ok(service.list(appId));
    }

    /**
     * 可授权清单：当前租户下已发布的工作流。
     * <p>给「应用管理 → 工作流授权」的下拉用；权限沿用应用查看，
     * 应用管理员无需额外持有 {@code workflow:def:view}。
     *
     * @return 最多 100 条已发布定义（含默认版本号）
     */
    @SaCheckPermission(PermissionCodes.APP_LIST)
    @GetMapping("/published-defs")
    public Result<List<WorkflowAppGrantItemResp>> publishedDefs() {
        return Result.ok(service.publishedDefs());
    }

    /**
     * 整体对齐某个应用的工作流授权。
     *
     * @param appId 应用 id
     * @param req   目标 workflow id 列表（空数组 = 撤销全部）
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.APP_EDIT)
    @PutMapping("/{appId:\\d+}")
    public Result<Void> save(@PathVariable Long appId, @Valid @RequestBody WorkflowAppGrantSaveReq req) {
        service.save(appId, req.getWorkflowIds());
        return Result.ok();
    }
}
