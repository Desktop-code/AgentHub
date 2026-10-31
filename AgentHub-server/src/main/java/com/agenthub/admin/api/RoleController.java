package com.agenthub.admin.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.admin.api.dto.RoleDataScopeReq;
import com.agenthub.admin.api.dto.RoleMenuReq;
import com.agenthub.admin.api.dto.RolePageReq;
import com.agenthub.admin.api.dto.RoleResp;
import com.agenthub.admin.api.dto.RoleSaveReq;
import com.agenthub.admin.application.RoleAppService;
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
 * 角色接口。所属域：admin。数据范围与菜单分配分开，避免一个保存接口包揽两种授权。
 */
@RestController
@RequestMapping("/api/system/roles")
public class RoleController {

    private final RoleAppService roleAppService;

    /**
     * @param roleAppService 角色用例
     */
    public RoleController(RoleAppService roleAppService) {
        this.roleAppService = roleAppService;
    }

    /**
     * @param req 分页条件
     * @return 角色页
     */
    @SaCheckPermission(PermissionCodes.ROLE_LIST)
    @GetMapping
    public Result<PageResult<RoleResp>> page(@Valid RolePageReq req) {
        return Result.ok(roleAppService.page(req));
    }

    /**
     * @param id 角色 id
     * @return 角色及授权回显
     */
    @SaCheckPermission(PermissionCodes.ROLE_LIST)
    @GetMapping("/{id}")
    public Result<RoleResp> get(@PathVariable Long id) {
        return Result.ok(roleAppService.get(id));
    }

    /**
     * @param req 新角色
     * @return 创建结果
     */
    @SaCheckPermission(PermissionCodes.ROLE_ADD)
    @PostMapping
    public Result<RoleResp> create(@Valid @RequestBody RoleSaveReq req) {
        return Result.ok(roleAppService.create(req));
    }

    /**
     * @param id  角色 id
     * @param req 资料
     * @return 更新结果
     */
    @SaCheckPermission(PermissionCodes.ROLE_EDIT)
    @PutMapping("/{id}")
    public Result<RoleResp> update(@PathVariable Long id, @Valid @RequestBody RoleSaveReq req) {
        return Result.ok(roleAppService.update(id, req));
    }

    /**
     * @param id 角色 id
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.ROLE_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roleAppService.delete(id);
        return Result.ok();
    }

    /**
     * @param id  角色 id
     * @param req 菜单 id
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.ROLE_MENU)
    @PutMapping("/{id}/menus")
    public Result<Void> assignMenus(@PathVariable Long id, @Valid @RequestBody RoleMenuReq req) {
        roleAppService.assignMenus(id, req);
        return Result.ok();
    }

    /**
     * @param id  角色 id
     * @param req 数据范围
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.ROLE_EDIT)
    @PutMapping("/{id}/data-scope")
    public Result<Void> updateDataScope(@PathVariable Long id, @Valid @RequestBody RoleDataScopeReq req) {
        roleAppService.updateDataScope(id, req);
        return Result.ok();
    }
}
