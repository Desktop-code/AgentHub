package com.agenthub.agent.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.agent.api.dto.PromptPreviewReq;
import com.agenthub.agent.api.dto.PromptPreviewResp;
import com.agenthub.agent.api.dto.PromptTemplateOptionResp;
import com.agenthub.agent.api.dto.PromptTemplateViewResp;
import com.agenthub.agent.application.AgentPromptAppService;
import com.agenthub.common.api.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 侧 L3 提示词配置接口。所属域：agent。
 * <p>
 * 三件事：拉可选版本、拉钉死版段落、预览组装结果。
 * CHAT（agent_def）与非 CHAT（agent_model_bind）共用这套读接口，只有保存落在各自写接口上。
 * <p>
 * 权限沿用 Agent 写权限（{@code agent:edit} / {@code agent:bind}），不新造与 {@code prompt:l1:*} 冲突的码：
 * 配置者能改 Agent 就应能看模板段落，但**没有**改 L1/L2 的能力（那些走 prompt 域接口）。
 */
@RestController
@RequestMapping("/api/agent/prompts")
public class AgentPromptController {

    private final AgentPromptAppService prompts;

    /**
     * @param prompts L3 读路径与预览用例
     */
    public AgentPromptController(AgentPromptAppService prompts) {
        this.prompts = prompts;
    }

    /**
     * 该用途可选的已发布模板版本。草稿不出现，避免绑到永不生效的版本。
     *
     * @param purpose 用途码（CHAT / ROUTER / REWRITE / SUMMARY / MEMORY）
     * @param locale  语言；空则 zh-CN
     * @return 版本行；用途无模板维度时为空列表
     */
    @SaCheckPermission(value = {AgentPermissions.LIST, AgentPermissions.ADD,
            AgentPermissions.EDIT, AgentPermissions.BIND}, mode = SaMode.OR)
    @GetMapping("/templates")
    public Result<List<PromptTemplateOptionResp>> templates(@RequestParam(required = false) String purpose,
                                                            @RequestParam(required = false) String locale) {
        return Result.ok(prompts.options(purpose, locale));
    }

    /**
     * 钉死版（或待升级版）模板及其段落，供表单按 mutable 分栏渲染。
     *
     * @param id 版本行主键
     * @return 模板与段落；草稿或不存在的模板返回业务错误
     */
    @SaCheckPermission(value = {AgentPermissions.LIST, AgentPermissions.ADD,
            AgentPermissions.EDIT, AgentPermissions.BIND}, mode = SaMode.OR)
    @GetMapping("/templates/{id:\\d+}")
    public Result<PromptTemplateViewResp> template(@PathVariable Long id) {
        return Result.ok(prompts.view(id));
    }

    /**
     * 预览未保存的表单值。内部走 PromptAssembler，与出站同路径；**不调用任何模型**。
     * <p>
     * 缺 L1 / 缺模板 / 缺必选段时与运行时同语义失败（409），便于配置者提前发现而不是试跑才炸。
     *
     * @param req 用途 + 四列表单值
     * @return 正文与版本统计
     */
    @SaCheckPermission(value = {AgentPermissions.EDIT, AgentPermissions.BIND}, mode = SaMode.OR)
    @PostMapping("/preview")
    public Result<PromptPreviewResp> preview(@RequestBody PromptPreviewReq req) {
        return Result.ok(prompts.preview(req));
    }
}
