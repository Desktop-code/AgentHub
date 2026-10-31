package com.agenthub.agent.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.agenthub.agent.api.dto.AgentCardResp;
import com.agenthub.agent.api.dto.AgentCreateReq;
import com.agenthub.agent.api.dto.AgentDetailResp;
import com.agenthub.agent.api.dto.AgentPageReq;
import com.agenthub.agent.api.dto.AgentStatusReq;
import com.agenthub.agent.api.dto.AgentUpdateReq;
import com.agenthub.agent.api.dto.PlatformPrefixResp;
import com.agenthub.agent.api.dto.RuntimeLimitsViewResp;
import com.agenthub.agent.application.AgentAppService;
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
 * Agent 定义接口。所属域：agent。校验、唯一性和审计不在这里。
 * 试跑接口在 runtime，不放在本控制器。
 */
@RestController
@RequestMapping("/api/agent/agents")
public class AgentController {

    private final AgentAppService agents;

    /**
     * @param agents Agent 用例
     */
    public AgentController(AgentAppService agents) {
        this.agents = agents;
    }

    /**
     * @param req 分页；可选 keyword、runMode、status
     * @return 列表，不含系统提示词
     */
    @SaCheckPermission(AgentPermissions.LIST)
    @GetMapping
    public Result<PageResult<AgentCardResp>> page(@Valid AgentPageReq req) {
        return Result.ok(agents.page(req));
    }

    /**
     * 新建页没有详情 id，编辑页切换运行模式时也要换文案。只读，不落库。
     * 放在 /{id} 之前，并用数字约束 id，避免 platform-prefix 被当成主键。
     *
     * @return REACT 与 ROUTER 两份前缀
     */
    @SaCheckPermission(mode = SaMode.OR, value = {AgentPermissions.LIST, AgentPermissions.ADD, AgentPermissions.EDIT})
    @GetMapping("/platform-prefix")
    public Result<PlatformPrefixResp> platformPrefix() {
        return Result.ok(agents.platformPrefix());
    }

    /**
     * 运行限制平台默认与硬顶。配置页占位；保存超顶由后端再拦一次。
     *
     * @return 各项 default / max
     */
    @SaCheckPermission(mode = SaMode.OR, value = {AgentPermissions.LIST, AgentPermissions.ADD, AgentPermissions.EDIT})
    @GetMapping("/runtime-limits")
    public Result<RuntimeLimitsViewResp> runtimeLimits() {
        return Result.ok(agents.runtimeLimits());
    }

    /**
     * @param id 主键
     * @return 基本信息、只读平台前缀和绑定计数
     */
    @SaCheckPermission(AgentPermissions.LIST)
    @GetMapping("/{id:\\d+}")
    public Result<AgentDetailResp> get(@PathVariable Long id) {
        return Result.ok(agents.get(id));
    }

    /**
     * @param req 新 Agent
     * @return 详情
     */
    @SaCheckPermission(AgentPermissions.ADD)
    @PostMapping
    public Result<AgentDetailResp> create(@Valid @RequestBody AgentCreateReq req) {
        return Result.ok(agents.create(req));
    }

    /**
     * @param id  主键
     * @param req 基本信息
     * @return 更新后的详情
     */
    @SaCheckPermission(AgentPermissions.EDIT)
    @PutMapping("/{id}")
    public Result<AgentDetailResp> update(@PathVariable Long id, @Valid @RequestBody AgentUpdateReq req) {
        return Result.ok(agents.update(id, req));
    }

    /**
     * @param id 主键
     * @return 空数据
     */
    @SaCheckPermission(AgentPermissions.REMOVE)
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable Long id) {
        agents.remove(id);
        return Result.ok();
    }

    /**
     * @param id  主键
     * @param req 目标状态
     * @return 更新后的详情
     */
    @SaCheckPermission(AgentPermissions.ENABLE)
    @PutMapping("/{id}/status")
    public Result<AgentDetailResp> changeStatus(@PathVariable Long id, @Valid @RequestBody AgentStatusReq req) {
        return Result.ok(agents.changeStatus(id, req));
    }
}
