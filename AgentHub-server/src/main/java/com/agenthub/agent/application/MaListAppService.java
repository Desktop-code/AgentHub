package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.AgentPageReq;
import com.agenthub.agent.api.dto.MaListRowResp;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentQuery;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.common.api.PageResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 多智能体列表用例。所属域：agent。
 *
 * <p>本刀是「产品入口债」：侧栏「编排 → 多智能体」只要能看到现网编排根，
 * 不引入 ma_def / ma_candidate 独立实体，也不复制 Agent 详情能力。
 *
 * <p>数据口径（窄）：
 * <ul>
 *   <li>行 = 未删除且 {@code run_mode=ROUTER} 的 agent_def；</li>
 *   <li>模板 = runtime_config_json.maTemplate，空/非法回落 ROUTER_PARALLEL；</li>
 *   <li>候选数 = 启用中的 agent_route 行数（停用子不计入参与者）。</li>
 * </ul>
 *
 * <p>分页由 Agent 定义仓储完成；候选数按当前页 id 批量 group by 一次取回，
 * 不逐行 count，也不在本刀引入 SQL 聚合新表。
 */
@Service
public class MaListAppService {

    private final AgentDefRepository agents;
    private final AgentRouteRepository routes;

    /**
     * @param agents Agent 定义仓储
     * @param routes 子路由仓储，用于统计候选数
     */
    public MaListAppService(AgentDefRepository agents, AgentRouteRepository routes) {
        this.agents = agents;
        this.routes = routes;
    }

    /**
     * 分页查询编排根（ROUTER）。keyword 匹配名称/编码，status 可空。
     *
     * @param req 分页与筛选；runMode 由本用例强制为 ROUTER，忽略调用方传值
     * @return 多智能体列表行
     */
    public PageResult<MaListRowResp> page(AgentPageReq req) {
        AgentQuery query = new AgentQuery();
        query.setCurrent(req.getCurrent());
        query.setSize(req.getSize());
        query.setKeyword(req.getKeyword());
        // 入口只展示编排根：即便前端误传 REACT 也强制收敛，避免列表串味
        query.setRunMode(AgentRunMode.ROUTER.name());
        query.setStatus(AgentChecks.optionalStatus(req.getStatus()));
        PageResult<AgentDef> page = agents.page(query);
        // 先取整页 id，再一条 group by 补齐候选数，避免逐行 count 的 N+1
        List<Long> ids = page.getRecords().stream().map(AgentDef::getId).toList();
        Map<Long, Integer> candidateCounts = routes.countEnabledByParentIds(ids);
        List<MaListRowResp> rows = page.getRecords().stream()
                .map(agent -> toRow(agent, candidateCounts.getOrDefault(agent.getId(), 0)))
                .toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), rows);
    }

    /**
     * Agent 定义 → 列表行。模板解析复用 {@link AgentRuntimeConfig#parse} 的归一化，
     * 因此旧数据（无 maTemplate 字段）也会稳定回显 ROUTER_PARALLEL。
     *
     * @param agent          编排根定义
     * @param candidateCount 批量聚合得到的启用候选数
     */
    private MaListRowResp toRow(AgentDef agent, int candidateCount) {
        MaListRowResp row = new MaListRowResp();
        row.setId(agent.getId());
        row.setName(agent.getName());
        row.setCode(agent.getCode());
        row.setStatus(agent.getStatus());
        AgentRuntimeConfig config = AgentRuntimeConfig.parse(agent.getRuntimeConfigJson(), AgentRunMode.ROUTER.name());
        row.setMaTemplate(config.maTemplateOrDefault());
        row.setCandidateCount(candidateCount);
        row.setUpdateTime(agent.getUpdateTime());
        return row;
    }
}
