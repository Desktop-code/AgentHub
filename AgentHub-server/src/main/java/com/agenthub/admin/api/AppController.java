package com.agenthub.admin.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.admin.api.dto.AppAgentReq;
import com.agenthub.admin.api.dto.AppPageReq;
import com.agenthub.admin.api.dto.AppResp;
import com.agenthub.admin.api.dto.AppSaveReq;
import com.agenthub.admin.application.AppAppService;
import com.agenthub.admin.domain.enums.PermissionCodes;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
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
 * 调用方应用接口。所属域：admin。
 */
@RestController
@RequestMapping("/api/system/apps")
public class AppController {

    private final AppAppService appAppService;

    /**
     * @param appAppService 应用用例
     */
    public AppController(AppAppService appAppService) {
        this.appAppService = appAppService;
    }

    /**
     * @param req 分页条件
     * @return 应用页
     */
    @SaCheckPermission(PermissionCodes.APP_LIST)
    @GetMapping
    public Result<PageResult<AppResp>> page(@Valid AppPageReq req) {
        return Result.ok(appAppService.page(req));
    }

    /**
     * @param id 应用 id
     * @return 应用资料
     */
    @SaCheckPermission(PermissionCodes.APP_LIST)
    @GetMapping("/{id}")
    public Result<AppResp> get(@PathVariable Long id) {
        return Result.ok(appAppService.get(id));
    }

    /**
     * @param req 新应用
     * @return 含一次性密钥
     */
    @SaCheckPermission(PermissionCodes.APP_ADD)
    @PostMapping
    public Result<AppResp> create(@Valid @RequestBody AppSaveReq req) {
        return Result.ok(appAppService.create(req));
    }

    /**
     * @param id  应用 id
     * @param req 资料
     * @return 更新结果
     */
    @SaCheckPermission(PermissionCodes.APP_EDIT)
    @PutMapping("/{id}")
    public Result<AppResp> update(@PathVariable Long id, @Valid @RequestBody AppSaveReq req) {
        return Result.ok(appAppService.update(id, req));
    }

    /**
     * @param id 应用 id
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.APP_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        appAppService.delete(id);
        return Result.ok();
    }

    /**
     * @param id 应用 id
     * @return 新的一次性密钥
     */
    @SaCheckPermission(PermissionCodes.APP_EDIT)
    @PostMapping("/{id}/secret")
    public Result<AppResp> resetSecret(@PathVariable Long id) {
        return Result.ok(appAppService.resetSecret(id));
    }

    /**
     * @param id  应用 id
     * @param req agent id 列表
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.APP_EDIT)
    @PutMapping("/{id}/agents")
    public Result<Void> grantAgents(@PathVariable Long id, @Valid @RequestBody AppAgentReq req) {
        appAppService.grantAgents(id, req);
        return Result.ok();
    }
}
