package com.agenthub.prompt.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.common.api.Result;
import com.agenthub.prompt.api.dto.PromptConstraintDraftReq;
import com.agenthub.prompt.api.dto.PromptConstraintListReq;
import com.agenthub.prompt.api.dto.PromptConstraintResp;
import com.agenthub.prompt.application.PromptConstraintAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * L1 平台约束接口。所属域：prompt。只做参数与权限，业务在 AppService。
 */
@RestController
@RequestMapping("/api/prompt/constraints")
public class PromptConstraintController {

    private final PromptConstraintAppService constraints;

    /**
     * @param constraints L1 用例
     */
    public PromptConstraintController(PromptConstraintAppService constraints) {
        this.constraints = constraints;
    }

    /**
     * @param req 可选 locale
     * @return 版本列表
     */
    @SaCheckPermission(PromptPermissions.L1_LIST)
    @GetMapping
    public Result<List<PromptConstraintResp>> list(@Valid PromptConstraintListReq req) {
        return Result.ok(constraints.list(req));
    }

    /**
     * @param id 主键
     * @return 详情
     */
    @SaCheckPermission(PromptPermissions.L1_LIST)
    @GetMapping("/{id}")
    public Result<PromptConstraintResp> get(@PathVariable Long id) {
        return Result.ok(constraints.get(id));
    }

    /**
     * @param req 草稿正文
     * @return 保存后详情
     */
    @SaCheckPermission(PromptPermissions.L1_EDIT)
    @PostMapping("/draft")
    public Result<PromptConstraintResp> saveDraft(@Valid @RequestBody PromptConstraintDraftReq req) {
        return Result.ok(constraints.saveDraft(req));
    }

    /**
     * @param id 草稿主键
     * @return 发布后详情
     */
    @SaCheckPermission(PromptPermissions.L1_PUBLISH)
    @PostMapping("/{id}/publish")
    public Result<PromptConstraintResp> publish(@PathVariable Long id) {
        return Result.ok(constraints.publish(id));
    }
}
