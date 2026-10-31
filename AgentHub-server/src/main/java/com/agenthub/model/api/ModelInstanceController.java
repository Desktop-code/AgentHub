package com.agenthub.model.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.model.api.dto.ConnectivityResp;
import com.agenthub.model.api.dto.InstancePageReq;
import com.agenthub.model.api.dto.InstanceResp;
import com.agenthub.model.api.dto.InstanceSaveReq;
import com.agenthub.model.api.dto.InstanceStatusReq;
import com.agenthub.model.application.ModelConnectivityAppService;
import com.agenthub.model.application.ModelInstanceAppService;
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
 * 模型实例接口。所属域：model。连通测试单独使用 test 权限，不并进编辑。
 */
@RestController
@RequestMapping("/api/model/instances")
public class ModelInstanceController {

    private final ModelInstanceAppService instances;
    private final ModelConnectivityAppService connectivity;

    /**
     * @param instances    实例用例
     * @param connectivity 探活用例
     */
    public ModelInstanceController(ModelInstanceAppService instances, ModelConnectivityAppService connectivity) {
        this.instances = instances;
        this.connectivity = connectivity;
    }

    /**
     * @param req 分页、供应商、用途、关键词
     * @return 一页实例
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_LIST)
    @GetMapping
    public Result<PageResult<InstanceResp>> page(@Valid InstancePageReq req) {
        return Result.ok(instances.page(req));
    }

    /**
     * @param id 实例 id
     * @return 详情
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_LIST)
    @GetMapping("/{id}")
    public Result<InstanceResp> get(@PathVariable Long id) {
        return Result.ok(instances.get(id));
    }

    /**
     * @param req 新实例
     * @return 详情
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_ADD)
    @PostMapping
    public Result<InstanceResp> create(@Valid @RequestBody InstanceSaveReq req) {
        return Result.ok(instances.create(req));
    }

    /**
     * @param id  实例 id
     * @param req 新资料
     * @return 详情
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_EDIT)
    @PutMapping("/{id}")
    public Result<InstanceResp> update(@PathVariable Long id, @Valid @RequestBody InstanceSaveReq req) {
        return Result.ok(instances.update(id, req));
    }

    /**
     * @param id  实例 id
     * @param req 目标状态
     * @return 详情
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_EDIT)
    @PutMapping("/{id}/status")
    public Result<InstanceResp> changeStatus(@PathVariable Long id, @Valid @RequestBody InstanceStatusReq req) {
        return Result.ok(instances.changeStatus(id, req));
    }

    /**
     * @param id 实例 id
     * @return 空数据
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        instances.delete(id);
        return Result.ok();
    }

    /**
     * @param id 实例 id
     * @return 探活结果
     */
    @SaCheckPermission(ModelPermissions.INSTANCE_TEST)
    @PostMapping("/{id}/test")
    public Result<ConnectivityResp> test(@PathVariable Long id) {
        return Result.ok(connectivity.test(id));
    }
}
