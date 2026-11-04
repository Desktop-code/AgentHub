package com.agenthub.agent.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenthub.agent.api.dto.AgentPageReq;
import com.agenthub.agent.api.dto.MaListRowResp;
import com.agenthub.agent.application.MaListAppService;
import com.agenthub.common.api.PageResult;
import com.agenthub.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多智能体只读入口。所属域：agent。
 *
 * <p>侧栏「编排 → 多智能体」的数据源：只列 ROUTER 编排根及其模板、候选数。
 * 本控制器只读，不提供 ma_def CRUD；真正的编排编辑仍回
 * {@code /agent/list/{id}?tab=orchestration} 的既有「编排」Tab。
 */
@RestController
@RequestMapping("/api/agent/multi-agents")
public class MaListController {

    private final MaListAppService multiAgents;

    /**
     * @param multiAgents 多智能体列表用例
     */
    public MaListController(MaListAppService multiAgents) {
        this.multiAgents = multiAgents;
    }

    /**
     * 分页列出编排根（run_mode 固定 ROUTER）。keyword 搜名称/编码，status 可空。
     *
     * @param req 分页与筛选
     * @return 名称、状态、模板、候选数和更新时间
     */
    @SaCheckPermission(AgentPermissions.ROUTE_VIEW)
    @GetMapping
    public Result<PageResult<MaListRowResp>> page(@Valid AgentPageReq req) {
        return Result.ok(multiAgents.page(req));
    }
}
