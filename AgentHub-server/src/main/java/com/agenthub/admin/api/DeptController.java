package com.agenthub.admin.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.admin.api.dto.DeptResp;
import com.agenthub.admin.api.dto.DeptSaveReq;
import com.agenthub.admin.application.DeptAppService;
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
 * 部门接口。所属域：admin。
 */
@RestController
@RequestMapping("/api/system/depts")
public class DeptController {

    private final DeptAppService deptAppService;

    /**
     * @param deptAppService 部门用例
     */
    public DeptController(DeptAppService deptAppService) {
        this.deptAppService = deptAppService;
    }

    /**
     * @return 部门树
     */
    @SaCheckPermission(PermissionCodes.DEPT_LIST)
    @GetMapping
    public Result<List<DeptResp>> tree() {
        return Result.ok(deptAppService.tree());
    }

    /**
     * @param req 新部门
     * @return 创建结果
     */
    @SaCheckPermission(PermissionCodes.DEPT_ADD)
    @PostMapping
    public Result<DeptResp> create(@Valid @RequestBody DeptSaveReq req) {
        return Result.ok(deptAppService.create(req));
    }

    /**
     * @param id  部门 id
     * @param req 资料
     * @return 更新结果
     */
    @SaCheckPermission(PermissionCodes.DEPT_EDIT)
    @PutMapping("/{id}")
    public Result<DeptResp> update(@PathVariable Long id, @Valid @RequestBody DeptSaveReq req) {
        return Result.ok(deptAppService.update(id, req));
    }

    /**
     * @param id 部门 id
     * @return 空数据
     */
    @SaCheckPermission(PermissionCodes.DEPT_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        deptAppService.delete(id);
        return Result.ok();
    }
}
