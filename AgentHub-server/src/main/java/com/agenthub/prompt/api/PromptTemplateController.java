package com.agenthub.prompt.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import com.agenthub.prompt.api.dto.PromptTemplateDraftReq;
import com.agenthub.prompt.api.dto.PromptTemplatePageReq;
import com.agenthub.prompt.api.dto.PromptTemplateResp;
import com.agenthub.prompt.application.PromptTemplateAppService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * L2 用途模板接口。所属域：prompt。草稿保存含段落同步；发布切 is_active。
 */
@RestController
@RequestMapping("/api/prompt/templates")
public class PromptTemplateController {

    private final PromptTemplateAppService templates;

    /**
     * @param templates L2 用例
     */
    public PromptTemplateController(PromptTemplateAppService templates) {
        this.templates = templates;
    }

    /**
     * @param req 分页筛选
     * @return 不含段落的一页
     */
    @SaCheckPermission(PromptPermissions.TEMPLATE_LIST)
    @GetMapping
    public Result<PageResult<PromptTemplateResp>> page(@Valid PromptTemplatePageReq req) {
        return Result.ok(templates.page(req));
    }

    /**
     * @param id 主键
     * @return 含段落详情
     */
    @SaCheckPermission(PromptPermissions.TEMPLATE_LIST)
    @GetMapping("/{id}")
    public Result<PromptTemplateResp> get(@PathVariable Long id) {
        return Result.ok(templates.get(id));
    }

    /**
     * 新建或更新草稿。新建需 add；更新需 edit（OR）。
     *
     * @param req 主信息 + 段落
     * @return 详情
     */
    @SaCheckPermission(value = {PromptPermissions.TEMPLATE_ADD, PromptPermissions.TEMPLATE_EDIT}, mode = SaMode.OR)
    @PostMapping("/draft")
    public Result<PromptTemplateResp> saveDraft(@Valid @RequestBody PromptTemplateDraftReq req) {
        return Result.ok(templates.saveDraft(req));
    }

    /**
     * @param id 草稿主键
     * @return 发布后详情
     */
    @SaCheckPermission(PromptPermissions.TEMPLATE_PUBLISH)
    @PostMapping("/{id}/publish")
    public Result<PromptTemplateResp> publish(@PathVariable Long id) {
        return Result.ok(templates.publish(id));
    }
}
