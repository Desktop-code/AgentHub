package com.agenthub.prompt.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.common.api.Result;
import com.agenthub.prompt.api.dto.PromptCatalogResp;
import com.agenthub.prompt.application.PromptCatalogAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词管理端目录接口。所属域：prompt。
 * 一次性给出左栏所需的 L1/L2 摘要，减少分页与多次拉取。
 * 任一提示词查询权限即可读（L1 或 L2 管理页合并后同一入口）。
 */
@RestController
@RequestMapping("/api/prompt/catalog")
public class PromptCatalogController {

    private final PromptCatalogAppService catalog;

    /**
     * @param catalog 目录用例
     */
    public PromptCatalogController(PromptCatalogAppService catalog) {
        this.catalog = catalog;
    }

    /**
     * @param locale 语言；空则 zh-CN
     * @return L1 版本摘要 + 按 template_key 聚合的 L2 摘要
     */
    @SaCheckPermission(value = {PromptPermissions.L1_LIST, PromptPermissions.TEMPLATE_LIST}, mode = SaMode.OR)
    @GetMapping
    public Result<PromptCatalogResp> get(@RequestParam(required = false) String locale) {
        return Result.ok(catalog.catalog(locale));
    }
}
