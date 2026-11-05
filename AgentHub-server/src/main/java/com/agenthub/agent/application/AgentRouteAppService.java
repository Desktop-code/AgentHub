package com.agenthub.agent.application;

import com.agenthub.agent.api.dto.RouteCreateReq;
import com.agenthub.agent.api.dto.RouteResp;
import com.agenthub.agent.api.dto.RouteUpdateReq;
import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.agent.domain.service.AgentCriticPolicy;
import com.agenthub.agent.domain.service.AgentRouteDefaults;
import com.agenthub.agent.domain.service.AgentRoutePolicy;
import com.agenthub.audit.domain.OperLogWriter;
import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.common.web.OperatorContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Router 子路由用例。所属域：agent。
 * 只保存一层 children。父节点当前不是 ROUTER 时拒绝写入，已有行保留给切回后继续用。
 * route_key / sort 可空由系统按 {@link AgentRouteDefaults} 补全；同父 route_key 唯一。
 *
 * <p>MA-P2 刀3 起：本类只维护历史 {@code agent_route} 行，<b>不再</b>把候选镜像到
 * {@code ma_candidate}；多智能体方案以 ma 侧为唯一写源，路由 Tab 不再作为编辑入口。
 */
@Service
public class AgentRouteAppService {

    private final AgentLookup lookup;
    private final AgentRouteRepository routes;
    private final OperatorContext operatorContext;
    private final OperLogWriter operLogWriter;

    /**
     * @param lookup          父、子定义
     * @param routes          路由仓储
     * @param operatorContext 操作人
     * @param operLogWriter   操作日志
     */
    public AgentRouteAppService(AgentLookup lookup, AgentRouteRepository routes,
                                OperatorContext operatorContext, OperLogWriter operLogWriter) {
        this.lookup = lookup;
        this.routes = routes;
        this.operatorContext = operatorContext;
        this.operLogWriter = operLogWriter;
    }

    /**
     * 父节点是 REACT 时也返回已有行，方便切回 ROUTER 后接着看。没有子路由不视为错误。
     * 打开路由 Tab 时懒补：空 key，以及仍为系统 child_* 且规范化 code 可用者；已手改 key 不动。
     *
     * @param agentId 父 Agent 主键
     * @return 子路由
     */
    @Transactional
    public List<RouteResp> list(Long agentId) {
        lookup.require(agentId);
        backfillSystemKeys(agentId);
        return routes.listByParentId(agentId).stream().map(this::toResp).toList();
    }

    /**
     * @param agentId 父 Agent 主键
     * @param req     子 Agent、意图；route_key / sort 可空由系统生成
     * @return 新路由
     * @throws BizException 父节点不是 ROUTER、子节点不合法、已配过或 route_key 冲突
     */
    @Transactional
    public RouteResp create(Long agentId, RouteCreateReq req) {
        AgentDef parent = lookup.require(agentId);
        AgentDef child = lookup.require(req.getChildAgentId());
        // 新建时无论路由行是否先停用，父子关系都必须满足一层规则。
        AgentRoutePolicy.assertCanBind(parent, child);
        int status = AgentChecks.initialStatus(req.getStatus());
        if (routes.existsPair(agentId, child.getId(), null)) {
            throw new BizException(ErrorCode.DUPLICATE, "该子 Agent 已在路由中");
        }
        String intent = AgentChecks.requiredText(req.getIntent(), 255, "意图");
        String routeKey = resolveIncomingKey(agentId, req.getRouteKey(), child, null);
        int sortOrder = resolveIncomingSort(agentId, req.getSortOrder());
        assertRouteKeyUnique(agentId, routeKey, null);

        AgentRoute route = new AgentRoute();
        route.setParentAgentId(agentId);
        route.setChildAgentId(child.getId());
        route.setRouteKey(routeKey);
        route.setIntent(intent);
        route.setSortOrder(sortOrder);
        route.setStatus(status);
        Long id = routes.insert(route, operatorContext.requireUserId());
        // 评审环角色闸：新增候选可能变成「第 2 个执行者」，保存即拦，避免库内出现跑不起来的配置
        assertCriticRoles(parent);
        // 刀3 起本路径只写 agent_route（历史兼容数据），不再镜像回 ma_def
        operLogWriter.write("agent", "bind", "agent_route", String.valueOf(id), "添加子 Agent " + child.getName());
        return toResp(routes.findById(id).orElseThrow());
    }

    /**
     * 重新启用时按当前父子定义再检查一层规则。父子 id 不变。
     * 空 route_key 在保存/启用时补生成；编辑默认应带上原 key，勿误清空。
     *
     * @param agentId 父 Agent 主键
     * @param routeId 路由主键
     * @param req     键、意图、排序和启停
     * @return 更新后的路由
     */
    @Transactional
    public RouteResp update(Long agentId, Long routeId, RouteUpdateReq req) {
        AgentDef parent = lookup.require(agentId);
        AgentRoute existing = requireOwned(agentId, routeId);
        AgentDef child = lookup.require(existing.getChildAgentId());
        int status = AgentChecks.exactStatus(req.getStatus());
        if (status == AgentStatus.ENABLED) {
            AgentRoutePolicy.assertCanBind(parent, child);
        } else {
            assertRouterParent(parent);
        }
        // 启停等未改意图时可能传空白；沿用原值。原值也空则要求先补意图。
        String intent = AgentChecks.optionalText(req.getIntent(), 255, "意图");
        if (intent == null) {
            intent = existing.getIntent();
        }
        if (intent == null || intent.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "意图不能为空，请先编辑填写");
        }
        // 编辑默认应带回原 key；空白才补生成，避免已跑通的提示词对不上。
        String rawKey = req.getRouteKey();
        if (AgentRouteDefaults.needsGeneratedKey(rawKey)
                && !AgentRouteDefaults.needsGeneratedKey(existing.getRouteKey())) {
            rawKey = existing.getRouteKey();
        }
        String routeKey = resolveIncomingKey(agentId, rawKey, child, routeId);
        int sortOrder = req.getSortOrder() == null
                ? resolveIncomingSort(agentId, null)
                : AgentChecks.sortOrder(req.getSortOrder());
        assertRouteKeyUnique(agentId, routeKey, routeId);

        existing.setRouteKey(routeKey);
        existing.setIntent(intent);
        existing.setSortOrder(sortOrder);
        existing.setStatus(status);
        routes.update(existing, operatorContext.requireUserId());
        // 重新启用候选同样可能凑出「第 2 个执行者」：与其他保存路径同口径再检查一次
        assertCriticRoles(parent);
        // 刀3 起只写 agent_route，不再同步方案候选
        operLogWriter.write("agent", "bind", "agent_route", String.valueOf(routeId), "更新子路由 " + child.getName());
        return toResp(routes.findById(routeId).orElseThrow());
    }

    /**
     * 父节点已切回 REACT 时不允许改路由，避免和「只有 ROUTER 维护子节点」不一致。
     *
     * @param agentId 父 Agent 主键
     * @param routeId 路由主键
     */
    @Transactional
    public void remove(Long agentId, Long routeId) {
        AgentDef parent = lookup.require(agentId);
        assertRouterParent(parent);
        AgentRoute existing = requireOwned(agentId, routeId);
        routes.logicalDelete(routeId, operatorContext.requireUserId());
        // 刀3 起只动 agent_route，方案候选以 ma_candidate 为唯一真源
        operLogWriter.write("agent", "unbind", "agent_route", String.valueOf(routeId),
                "移除子路由 " + existing.getChildAgentId());
    }

    /**
     * 存量空 key / 系统 child_*：按规范化 code 补全或升级并保存。
     * QAAgent 等已手改 key 不覆盖；规范化后仍冲突则保持原 child_*。
     */
    private void backfillSystemKeys(Long parentAgentId) {
        List<AgentRoute> siblings = routes.listByParentId(parentAgentId);
        Long operatorId = null;
        for (AgentRoute route : siblings) {
            if (!AgentRouteDefaults.isSystemFallbackKey(route.getRouteKey(), route.getChildAgentId(), route.getId())) {
                continue;
            }
            AgentDef child = lookup.find(route.getChildAgentId()).orElse(null);
            String code = child == null ? null : child.getCode();
            Long childId = route.getChildAgentId();
            // 排除自身当前 key，否则 child_18 会挡住升级到 MCPTestAgent 时的占用集误判。
            Set<String> used = occupiedKeys(siblings, route.getId());
            String key = AgentRouteDefaults.resolveKey(code, childId, route.getId(), used);
            if (routes.existsRouteKey(parentAgentId, key, route.getId())) {
                key = AgentRouteDefaults.fallbackKey(childId) + "_" + route.getId();
            }
            // 已是同一 key（仍只能 child_*）则不必写库。
            if (key.equals(route.getRouteKey())) {
                continue;
            }
            if (operatorId == null) {
                operatorId = operatorContext.requireUserId();
            }
            route.setRouteKey(key);
            routes.update(route, operatorId);
        }
    }

    /**
     * 请求未传或空白则系统生成；手填则校验长度后原样使用（唯一性另检）。
     */
    private String resolveIncomingKey(Long parentAgentId, String rawKey, AgentDef child, Long excludeRouteId) {
        if (!AgentRouteDefaults.needsGeneratedKey(rawKey)) {
            return AgentChecks.optionalText(rawKey, 64, "路由键");
        }
        Set<String> used = occupiedKeys(routes.listByParentId(parentAgentId), excludeRouteId);
        String key = AgentRouteDefaults.resolveKey(child.getCode(), child.getId(), excludeRouteId, used);
        if (routes.existsRouteKey(parentAgentId, key, excludeRouteId)) {
            throw new BizException(ErrorCode.DUPLICATE, "路由键「" + key + "」已存在，请在高级中改成其它键");
        }
        return key;
    }

    /**
     * 未传 sort 时按同父 max+10（无子则 10）；显式传 0 也允许。
     */
    private int resolveIncomingSort(Long parentAgentId, Integer rawSort) {
        if (rawSort != null) {
            return AgentChecks.sortOrder(rawSort);
        }
        return AgentRouteDefaults.nextSort(routes.maxSortOrder(parentAgentId).orElse(null));
    }

    private void assertRouteKeyUnique(Long parentAgentId, String routeKey, Long excludeId) {
        if (routeKey == null || routeKey.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "路由键不能为空");
        }
        if (routes.existsRouteKey(parentAgentId, routeKey, excludeId)) {
            throw new BizException(ErrorCode.DUPLICATE, "同父下路由键「" + routeKey + "」已存在");
        }
    }

    /**
     * 收集同父已占用 key；exclude 自身。比较用小写，避免大小写撞车被漏掉。
     */
    private Set<String> occupiedKeys(List<AgentRoute> siblings, Long excludeRouteId) {
        Set<String> used = new HashSet<>();
        for (AgentRoute sibling : siblings) {
            if (excludeRouteId != null && excludeRouteId.equals(sibling.getId())) {
                continue;
            }
            if (sibling.getRouteKey() == null || sibling.getRouteKey().isBlank()) {
                continue;
            }
            used.add(sibling.getRouteKey().toLowerCase(Locale.ROOT));
        }
        return used;
    }

    /**
     * 停用中的路由也只能挂在当前的 ROUTER 上。切回 REACT 后这些行只读保留。
     */
    private void assertRouterParent(AgentDef parent) {
        if (!AgentRunMode.ROUTER.name().equals(parent.getRunMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有 ROUTER 可以配置子 Agent");
        }
    }

    /**
     * 评审环角色闸：模板为 CRITIC_LOOP 时，启用候选（排除评审者）必须恰好 1 个。
     *
     * <p>放在写库之后：读取的是「本次写入后的真实列表」，避免新增/重新启用时自己再拼一遍推定状态；
     * 抛异常由 {@code @Transactional} 回滚，库里不会留下半截配置。
     *
     * @param parent 父 Agent（已校验是 ROUTER）
     * @throws BizException 执行者不是恰好 1 个，或评审者非法
     */
    private void assertCriticRoles(AgentDef parent) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse(parent.getRuntimeConfigJson(), parent.getRunMode());
        if (!runtime.isCriticLoop()) {
            return;
        }
        AgentCriticPolicy.assertRoles(runtime, routes.listByParentId(parent.getId()), lookup::find);
    }

    private AgentRoute requireOwned(Long agentId, Long routeId) {
        AgentRoute route = routes.findById(routeId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "子路由不存在"));
        if (!agentId.equals(route.getParentAgentId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "子路由不存在");
        }
        return route;
    }

    private RouteResp toResp(AgentRoute route) {
        RouteResp resp = new RouteResp();
        resp.setId(route.getId());
        resp.setParentAgentId(route.getParentAgentId());
        resp.setChildAgentId(route.getChildAgentId());
        resp.setRouteKey(route.getRouteKey());
        resp.setIntent(route.getIntent());
        resp.setSortOrder(route.getSortOrder());
        resp.setStatus(route.getStatus());
        lookup.find(route.getChildAgentId()).ifPresent(child -> {
            resp.setChildName(child.getName());
            resp.setChildCode(child.getCode());
            resp.setChildRunMode(child.getRunMode());
            resp.setChildStatus(child.getStatus());
            // 供前端对「已存在但被关掉开关」的候选提前报错，不必等保存失败才发现
            resp.setChildAllowOrchestrated(child.allowOrchestratedOn() ? 1 : 0);
        });
        return resp;
    }
}
