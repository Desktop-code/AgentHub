package com.agenthub.agent.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.agent.api.dto.ModelBindCreateReq;
import com.agenthub.agent.api.dto.ModelBindResp;
import com.agenthub.agent.api.dto.ModelBindUpdateReq;
import com.agenthub.agent.api.dto.RouteCreateReq;
import com.agenthub.agent.api.dto.RouteResp;
import com.agenthub.agent.api.dto.RouteUpdateReq;
import com.agenthub.agent.api.dto.SkillBindCreateReq;
import com.agenthub.agent.api.dto.SkillBindResp;
import com.agenthub.agent.api.dto.SkillBindUpdateReq;
import com.agenthub.agent.api.dto.ToolBindCreateReq;
import com.agenthub.agent.api.dto.ToolBindResp;
import com.agenthub.agent.api.dto.ToolBindUpdateReq;
import com.agenthub.agent.application.AgentModelBindAppService;
import com.agenthub.agent.application.AgentRouteAppService;
import com.agenthub.agent.application.AgentSkillBindAppService;
import com.agenthub.agent.application.AgentToolBindAppService;
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
 * Agent 绑定与一层子路由。所属域：agent。
 * 模型、工具、Skill 和路由的写操作都用 agent:bind，查询用 agent:list。
 */
@RestController
@RequestMapping("/api/agent/agents/{agentId}")
public class AgentBindController {

    private final AgentModelBindAppService models;
    private final AgentToolBindAppService tools;
    private final AgentSkillBindAppService skills;
    private final AgentRouteAppService routes;

    /**
     * @param models 模型绑定
     * @param tools  工具绑定
     * @param skills Skill 绑定
     * @param routes 子路由
     */
    public AgentBindController(AgentModelBindAppService models, AgentToolBindAppService tools,
                               AgentSkillBindAppService skills, AgentRouteAppService routes) {
        this.models = models;
        this.tools = tools;
        this.skills = skills;
        this.routes = routes;
    }

    /**
     * @param agentId Agent 主键
     * @return 模型绑定
     */
    @SaCheckPermission(AgentPermissions.LIST)
    @GetMapping("/model-binds")
    public Result<List<ModelBindResp>> listModels(@PathVariable Long agentId) {
        return Result.ok(models.list(agentId));
    }

    /**
     * @param agentId Agent 主键
     * @param req     实例、用途和优先级
     * @return 新绑定
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @PostMapping("/model-binds")
    public Result<ModelBindResp> createModel(@PathVariable Long agentId, @Valid @RequestBody ModelBindCreateReq req) {
        return Result.ok(models.create(agentId, req));
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @param req     用途、优先级和启停
     * @return 更新后的绑定
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @PutMapping("/model-binds/{bindId}")
    public Result<ModelBindResp> updateModel(@PathVariable Long agentId, @PathVariable Long bindId,
                                             @Valid @RequestBody ModelBindUpdateReq req) {
        return Result.ok(models.update(agentId, bindId, req));
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @return 空数据
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @DeleteMapping("/model-binds/{bindId}")
    public Result<Void> removeModel(@PathVariable Long agentId, @PathVariable Long bindId) {
        models.remove(agentId, bindId);
        return Result.ok();
    }

    /**
     * @param agentId Agent 主键
     * @return 工具绑定
     */
    @SaCheckPermission(AgentPermissions.LIST)
    @GetMapping("/tool-binds")
    public Result<List<ToolBindResp>> listTools(@PathVariable Long agentId) {
        return Result.ok(tools.list(agentId));
    }

    /**
     * @param agentId Agent 主键
     * @param req     工具项
     * @return 新绑定
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @PostMapping("/tool-binds")
    public Result<ToolBindResp> createTool(@PathVariable Long agentId, @Valid @RequestBody ToolBindCreateReq req) {
        return Result.ok(tools.create(agentId, req));
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @param req     目标启停
     * @return 更新后的绑定
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @PutMapping("/tool-binds/{bindId}")
    public Result<ToolBindResp> updateTool(@PathVariable Long agentId, @PathVariable Long bindId,
                                           @Valid @RequestBody ToolBindUpdateReq req) {
        return Result.ok(tools.update(agentId, bindId, req));
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @return 空数据
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @DeleteMapping("/tool-binds/{bindId}")
    public Result<Void> removeTool(@PathVariable Long agentId, @PathVariable Long bindId) {
        tools.remove(agentId, bindId);
        return Result.ok();
    }

    /**
     * @param agentId Agent 主键
     * @return Skill 绑定，不含正文
     */
    @SaCheckPermission(AgentPermissions.LIST)
    @GetMapping("/skill-binds")
    public Result<List<SkillBindResp>> listSkills(@PathVariable Long agentId) {
        return Result.ok(skills.list(agentId));
    }

    /**
     * @param agentId Agent 主键
     * @param req     Skill
     * @return 新绑定
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @PostMapping("/skill-binds")
    public Result<SkillBindResp> createSkill(@PathVariable Long agentId, @Valid @RequestBody SkillBindCreateReq req) {
        return Result.ok(skills.create(agentId, req));
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @param req     目标启停
     * @return 更新后的绑定
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @PutMapping("/skill-binds/{bindId}")
    public Result<SkillBindResp> updateSkill(@PathVariable Long agentId, @PathVariable Long bindId,
                                             @Valid @RequestBody SkillBindUpdateReq req) {
        return Result.ok(skills.update(agentId, bindId, req));
    }

    /**
     * @param agentId Agent 主键
     * @param bindId  绑定主键
     * @return 空数据
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @DeleteMapping("/skill-binds/{bindId}")
    public Result<Void> removeSkill(@PathVariable Long agentId, @PathVariable Long bindId) {
        skills.remove(agentId, bindId);
        return Result.ok();
    }

    /**
     * @param agentId 父 Agent 主键
     * @return 一层子路由
     */
    @SaCheckPermission(AgentPermissions.LIST)
    @GetMapping("/routes")
    public Result<List<RouteResp>> listRoutes(@PathVariable Long agentId) {
        return Result.ok(routes.list(agentId));
    }

    /**
     * @param agentId 父 Agent 主键
     * @param req     子 Agent 和路由说明
     * @return 新路由
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @PostMapping("/routes")
    public Result<RouteResp> createRoute(@PathVariable Long agentId, @Valid @RequestBody RouteCreateReq req) {
        return Result.ok(routes.create(agentId, req));
    }

    /**
     * @param agentId 父 Agent 主键
     * @param routeId 路由主键
     * @param req     键、意图、排序和启停
     * @return 更新后的路由
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @PutMapping("/routes/{routeId}")
    public Result<RouteResp> updateRoute(@PathVariable Long agentId, @PathVariable Long routeId,
                                         @Valid @RequestBody RouteUpdateReq req) {
        return Result.ok(routes.update(agentId, routeId, req));
    }

    /**
     * @param agentId 父 Agent 主键
     * @param routeId 路由主键
     * @return 空数据
     */
    @SaCheckPermission(AgentPermissions.BIND)
    @DeleteMapping("/routes/{routeId}")
    public Result<Void> removeRoute(@PathVariable Long agentId, @PathVariable Long routeId) {
        routes.remove(agentId, routeId);
        return Result.ok();
    }
}
