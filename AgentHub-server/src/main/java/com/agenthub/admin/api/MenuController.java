package com.agenthub.admin.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.admin.api.dto.MenuResp;
import com.agenthub.admin.api.dto.MenuSaveReq;
import com.agenthub.admin.application.MenuAppService;
import com.agenthub.admin.domain.enums.PermissionCodes;
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

import java.util.List;

/**
 * 菜单接口。所属域：admin。
 */
@RestController
@RequestMapping("/api/system/menus")
public class MenuController {

    private final MenuAppService menuAppService;

    /**
     * @param menuAppService 菜单用例
     */
    public MenuController(MenuAppService menuAppService) {
        this.menuAppService = menuAppService;
    }

    /**
     * @return 完整菜单树
     */
    @SaCheckPermission(PermissionCodes.MENU_LIST)
    @GetMapping
    public Result<List<MenuResp>> tree() {
        return Result.ok(menuAppService.tree());
    }

    /**
     * @param req 新菜单
     * @return 创建结果
     */
    @SaCheckPermission(PermissionCodes.MENU_ADD)
    @PostMapping
    public Result<MenuResp> create(@Valid @RequestBody MenuSaveReq req) {
        return Result.ok(menuAppService.create(req));
    }

    /**
     * @param id  菜单 id
     * @param req 资料
     * @return 更新结果
     */
    @SaCheckPermission(PermissionCodes.MENU_EDIT)
    @PutMapping("/{id}")
    public Result<MenuResp> update(@PathVariable Long id, @Valid @RequestBody MenuSaveReq req) {
        return Result.ok(menuAppService.update(id, req));
    }

    /**
     * @param id 菜单 id
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.MENU_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        menuAppService.delete(id);
        return Result.ok();
    }
}
