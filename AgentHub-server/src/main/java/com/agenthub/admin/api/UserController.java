package com.agenthub.admin.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.admin.api.dto.UserCreateReq;
import com.agenthub.admin.api.dto.UserPageReq;
import com.agenthub.admin.api.dto.UserResp;
import com.agenthub.admin.api.dto.UserRoleReq;
import com.agenthub.admin.api.dto.UserUpdateReq;
import com.agenthub.admin.application.UserAppService;
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
 * 用户接口。所属域：admin。只做参数校验和权限码，业务在 UserAppService。
 */
@RestController
@RequestMapping("/api/system/users")
public class UserController {

    private final UserAppService userAppService;

    /**
     * @param userAppService 用户用例
     */
    public UserController(UserAppService userAppService) {
        this.userAppService = userAppService;
    }

    /**
     * @param req 分页条件
     * @return 用户页
     */
    @SaCheckPermission(PermissionCodes.USER_LIST)
    @GetMapping
    public Result<PageResult<UserResp>> page(@Valid UserPageReq req) {
        return Result.ok(userAppService.page(req));
    }

    /**
     * @param id 用户 id
     * @return 用户资料
     */
    @SaCheckPermission(PermissionCodes.USER_LIST)
    @GetMapping("/{id}")
    public Result<UserResp> get(@PathVariable Long id) {
        return Result.ok(userAppService.get(id));
    }

    /**
     * @param req 新用户
     * @return 创建结果
     */
    @SaCheckPermission(PermissionCodes.USER_ADD)
    @PostMapping
    public Result<UserResp> create(@Valid @RequestBody UserCreateReq req) {
        return Result.ok(userAppService.create(req));
    }

    /**
     * @param id  用户 id
     * @param req 资料
     * @return 更新结果
     */
    @SaCheckPermission(PermissionCodes.USER_EDIT)
    @PutMapping("/{id}")
    public Result<UserResp> update(@PathVariable Long id, @Valid @RequestBody UserUpdateReq req) {
        return Result.ok(userAppService.update(id, req));
    }

    /**
     * @param id 用户 id
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.USER_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userAppService.delete(id);
        return Result.ok();
    }

    /**
     * 重置为配置的初始密码。
     *
     * @param id 用户 id
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.USER_RESET_PWD)
    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        userAppService.resetPassword(id);
        return Result.ok();
    }

    /**
     * @param id  用户 id
     * @param req 角色 id 列表
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.USER_ROLE)
    @PutMapping("/{id}/roles")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody UserRoleReq req) {
        userAppService.assignRoles(id, req);
        return Result.ok();
    }
}
