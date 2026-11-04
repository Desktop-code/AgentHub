package com.agenthub.workflow.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.workflow.api.dto.WorkflowDefCreateReq;
import com.agenthub.workflow.api.dto.WorkflowDefPageReq;
import com.agenthub.workflow.api.dto.WorkflowDefResp;
import com.agenthub.workflow.api.dto.WorkflowDefUpdateReq;
import com.agenthub.workflow.api.dto.WorkflowDraftResp;
import com.agenthub.workflow.api.dto.WorkflowPublishReq;
import com.agenthub.workflow.api.dto.WorkflowReleaseResp;
import com.agenthub.workflow.api.dto.WorkflowValidateResp;
import com.agenthub.workflow.application.WorkflowDefAppService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作流定义接口。所属域：workflow。
 * <p>控制器零业务：租户、校验、发布版本号都在 {@link WorkflowDefAppService}。
 * <p>路径：{@code /api/workflow/defs}；权限见 {@link WorkflowPermissions}。
 * <p>本刀不提供 run / trial / SSE 接口，避免被误读成引擎已通。
 */
@RestController
@RequestMapping("/api/workflow/defs")
public class WorkflowDefController {

    private final WorkflowDefAppService service;

    /**
     * @param service 工作流用例
     */
    public WorkflowDefController(WorkflowDefAppService service) {
        this.service = service;
    }

    /**
     * 分页查询当前租户的工作流。
     *
     * @param req 分页与筛选
     * @return 一页定义
     */
    @SaCheckPermission(WorkflowPermissions.DEF_VIEW)
    @GetMapping
    public Result<PageResult<WorkflowDefResp>> page(@Valid WorkflowDefPageReq req) {
        return Result.ok(service.page(req));
    }

    /**
     * 新建工作流，并自动建最小合法草稿。
     *
     * @param req 编码 / 名称 / 说明
     * @return 新定义
     */
    @SaCheckPermission(WorkflowPermissions.DEF_EDIT)
    @PostMapping
    public Result<WorkflowDefResp> create(@Valid @RequestBody WorkflowDefCreateReq req) {
        return Result.ok(service.create(req));
    }

    /**
     * @param id 主键
     * @return 定义详情
     */
    @SaCheckPermission(WorkflowPermissions.DEF_VIEW)
    @GetMapping("/{id:\\d+}")
    public Result<WorkflowDefResp> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    /**
     * 改名称与说明；code 不可改。
     *
     * @param id  主键
     * @param req 新名称 / 说明
     * @return 更新后的定义
     */
    @SaCheckPermission(WorkflowPermissions.DEF_EDIT)
    @PutMapping("/{id:\\d+}")
    public Result<WorkflowDefResp> update(@PathVariable Long id,
                                          @Valid @RequestBody WorkflowDefUpdateReq req) {
        return Result.ok(service.update(id, req));
    }

    /**
     * @param id 主键
     * @return 草稿图 + 更新时间
     */
    @SaCheckPermission(WorkflowPermissions.DEF_VIEW)
    @GetMapping("/{id:\\d+}/draft")
    public Result<WorkflowDraftResp> getDraft(@PathVariable Long id) {
        return Result.ok(service.getDraft(id));
    }

    /**
     * 保存草稿。请求体就是 graph_json 对象（nodes / edges / viewport）。
     *
     * @param id                 主键
     * @param graph              图 JSON
     * @param expectedUpdateTime 可选，期望更新时间；不一致返回 409
     * @return 保存后的草稿
     */
    @SaCheckPermission(WorkflowPermissions.DEF_EDIT)
    @PutMapping("/{id:\\d+}/draft")
    public Result<WorkflowDraftResp> saveDraft(@PathVariable Long id,
                                               @RequestBody JsonNode graph,
                                               @RequestParam(required = false) String expectedUpdateTime) {
        return Result.ok(service.saveDraft(id, graph, expectedUpdateTime));
    }

    /**
     * 校验当前草稿；绑定缺失/停用按发布严格模式记 error。
     *
     * @param id 主键
     * @return errors / warns / passed
     */
    @SaCheckPermission(WorkflowPermissions.DEF_VIEW)
    @PostMapping("/{id:\\d+}/validate")
    public Result<WorkflowValidateResp> validate(@PathVariable Long id) {
        return Result.ok(service.validate(id));
    }

    /**
     * 发布当前草稿，生成不可变 release。
     *
     * @param id  主键
     * @param req 发布说明，可空
     * @return 新版本
     */
    @SaCheckPermission(WorkflowPermissions.DEF_PUBLISH)
    @PostMapping("/{id:\\d+}/publish")
    public Result<WorkflowReleaseResp> publish(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) WorkflowPublishReq req) {
        return Result.ok(service.publish(id, req));
    }

    /**
     * @param id 主键
     * @return 版本列表，按版本号倒序
     */
    @SaCheckPermission(WorkflowPermissions.DEF_VIEW)
    @GetMapping("/{id:\\d+}/releases")
    public Result<List<WorkflowReleaseResp>> releases(@PathVariable Long id) {
        return Result.ok(service.releases(id));
    }
}
