package com.agenthub.agent.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.agent.api.dto.ReceptionAgentOptionResp;
import com.agenthub.agent.api.dto.ReceptionCandidateAddReq;
import com.agenthub.agent.api.dto.ReceptionCandidateEditReq;
import com.agenthub.agent.api.dto.ReceptionCandidateOrderReq;
import com.agenthub.agent.api.dto.ReceptionConfigResp;
import com.agenthub.agent.api.dto.ReceptionPromptReq;
import com.agenthub.agent.api.dto.ReceptionRouterConfigResp;
import com.agenthub.agent.api.dto.ReceptionSchemeOptionResp;
import com.agenthub.agent.api.dto.ReceptionWorkflowOptionResp;
import com.agenthub.agent.application.ReceptionConfigAppService;
import com.agenthub.agent.application.ReceptionRouterAppService;
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
 * 默认接待简易配置接口。所属域：agent。
 *
 * <p>路径挂在 {@code /api/agent/multi-agents/reception} 下：默认接待本质上就是一条
 * {@code ma_def} 方案，沿用同一入口便于深链与权限归组，但只暴露<b>路由池名单</b>
 * （添加 / 启停 / 排序 / 移除），不开放提示词、意图说明、编码 / 模板 / 绑根等字段。
 *
 * <p>控制器零业务：就绪判定、候选门闩、排序校验都在 {@link ReceptionConfigAppService}。
 */
@RestController
@RequestMapping("/api/agent/multi-agents/reception")
public class ReceptionConfigController {

    private final ReceptionConfigAppService service;
    /**
     * 入口 Router 只读回显（方案、生效模型、ROUTER 提示词摘要）。
     * <p>本刀起入口的模型 / 提示词编辑走现网方案编辑页，本端点只给默认接待页一行状态与跳转用。
     */
    private final ReceptionRouterAppService router;

    /**
     * @param service 默认接待简易配置用例
     * @param router  Router 网关配置用例
     */
    public ReceptionConfigController(ReceptionConfigAppService service, ReceptionRouterAppService router) {
        this.service = service;
        this.router = router;
    }

    /**
     * 当前默认接待配置与就绪状态。
     *
     * @return 配置视图；未配置默认方案时 ready=false
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @GetMapping
    public Result<ReceptionConfigResp> config() {
        return Result.ok(service.config());
    }

    /**
     * 工作台统一入口（Router）的网关配置：现在用哪套模型、哪版 ROUTER 模板。
     *
     * <p>数据全部来自现网 {@code ma_def.config_json} 与已发布 ROUTER 模板，
     * 本端点只是把它读出来给页面，不新建配置载体，也不影响任何专家的模型 / 提示词。
     *
     * @return Router 网关配置；未配置默认接待时 ready=false
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @GetMapping("/router")
    public Result<ReceptionRouterConfigResp> routerConfig() {
        return Result.ok(router.config());
    }

    /**
     * 可加入默认接待的 Agent（已排除被任一方案占用的）。
     *
     * @return 选项列表
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @GetMapping("/available-agents")
    public Result<List<ReceptionAgentOptionResp>> availableAgents() {
        return Result.ok(service.availableAgents());
    }

    /**
     * 可加入默认接待的「另一套编排方案」（刀5）。
     *
     * <p>已剔除自己 / 默认接待 / 停用 / 自己不判路由 / 自己还挂着方案候选的方案；
     * 说明为空的方案仍会列出，但前端不允许提交。
     *
     * @return 方案选项列表
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @GetMapping("/available-schemes")
    public Result<List<ReceptionSchemeOptionResp>> availableSchemes() {
        return Result.ok(service.availableSchemes());
    }

    /**
     * 可加入默认接待的「已发布工作流」（刀5B）。
     *
     * <p>已剔除草稿-only、无默认发布、说明为空、已在池里的工作流：
     * 草稿态跑不起来，空说明进池后判路由无从判断，所以本端点<b>不</b>出置灰项，与保存侧门闩一致。
     *
     * @return 工作流选项列表
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @GetMapping("/available-workflows")
    public Result<List<ReceptionWorkflowOptionResp>> availableWorkflows() {
        return Result.ok(service.availableWorkflows());
    }

    /**
     * 修改接待提示词：<b>已废止</b>，固定返回 400。
     *
     * <p>刀4 起接待人设只配在接待助手 Agent 的系统提示词里（Agent 管理 → 接待助手）。
     * 保留该路由只为让旧前端 / 旧脚本明确失败，而不是以为保存成功了。
     *
     * @param req 旧参数；不再读取
     * @return 永不返回
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @PutMapping("/prompt")
    public Result<ReceptionConfigResp> updatePrompt(@Valid @RequestBody ReceptionPromptReq req) {
        return Result.ok(service.updatePrompt(req.getPrompt()));
    }

    /**
     * 添加子 Agent。意图说明取自该 Agent 的简介，简介为空会被拒绝。
     *
     * @param req 子 Agent 主键
     * @return 更新后的配置
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @PostMapping("/candidates")
    public Result<ReceptionConfigResp> addCandidate(@Valid @RequestBody ReceptionCandidateAddReq req) {
        return Result.ok(service.addCandidate(req));
    }

    /**
     * 修改候选启停。意图说明不可改：传 intentDesc 会明确失败。
     *
     * @param id  候选主键
     * @param req 只读 enabled
     * @return 更新后的配置
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @PutMapping("/candidates/{id:\\d+}")
    public Result<ReceptionConfigResp> updateCandidate(@PathVariable Long id,
                                                       @Valid @RequestBody ReceptionCandidateEditReq req) {
        return Result.ok(service.updateCandidate(id, req));
    }

    /**
     * 移除候选。
     *
     * @param id 候选主键
     * @return 更新后的配置
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @DeleteMapping("/candidates/{id:\\d+}")
    public Result<ReceptionConfigResp> removeCandidate(@PathVariable Long id) {
        return Result.ok(service.removeCandidate(id));
    }

    /**
     * 按提交顺序重排候选；首位成为接待助手。
     *
     * @param req 全部候选主键的一个排列
     * @return 更新后的配置
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @PutMapping("/candidates/order")
    public Result<ReceptionConfigResp> reorder(@Valid @RequestBody ReceptionCandidateOrderReq req) {
        return Result.ok(service.reorder(req.getIds()));
    }

    /**
     * 把指定方案设为平台默认接待。
     *
     * @param maId 方案主键
     * @return 空数据
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @PutMapping("/default/{maId:\\d+}")
    public Result<Void> setDefault(@PathVariable Long maId) {
        service.setDefault(maId);
        return Result.ok();
    }

    /**
     * 取消当前默认接待；之后工作台按「未配置」处理，不会自动挑别的方案。
     *
     * @return 空数据
     */
    @SaCheckPermission(MaPermissions.RECEPTION)
    @DeleteMapping("/default")
    public Result<Void> clearDefault() {
        service.clearDefault();
        return Result.ok();
    }
}
