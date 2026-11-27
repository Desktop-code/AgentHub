package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.WorkflowNodeFailure;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowRelease;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.repository.WorkflowReleaseRepository;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import com.agenthub.workflow.domain.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 把 SUBGRAPH 节点钉死的 {@code releaseId} 解析成可执行发布快照。所属域：workflow 的 application。
 *
 * <p>职责：
 * <ul>
 *   <li>只按节点配置里的 {@code subReleaseId} 取发布版本，<b>绝不</b>回落到草稿或当前默认发布版；</li>
 *   <li>运行时重新校验：版本仍在、属于所绑工作流、工作流属于同一租户；</li>
 *   <li>发布快照的 graph_json 解析后按版本缓存（发布不可变），避免每轮 / 每层重复解析。</li>
 * </ul>
 *
 * <p>边界：错误文案面向用户，不带主机、端口、路径、异常类名或堆栈。
 * 解析失败抛 {@link WorkflowNodeFailure}，由引擎按普通节点失败规则收口。
 */
@Component
public class WorkflowSubgraphResolver {

    /** 发布快照缓存上限：超过后整表清空，宁可多解析一次也不无界增长 */
    private static final int CACHE_MAX = 256;

    private final WorkflowReleaseRepository releases;
    private final WorkflowDefRepository defs;
    private final WorkflowGraphCodec codec;
    private final ConcurrentMap<Long, CachedGraph> graphCache = new ConcurrentHashMap<>();

    /**
     * @param releases 发布版本仓储
     * @param defs     工作流定义仓储（取名称 / 编码 / 租户）
     * @param codec    图 JSON 解析
     */
    public WorkflowSubgraphResolver(WorkflowReleaseRepository releases, WorkflowDefRepository defs,
                                    WorkflowGraphCodec codec) {
        this.releases = releases;
        this.defs = defs;
        this.codec = codec;
    }

    /**
     * 从 SUBGRAPH 节点配置解析发布快照；失败给中文原因。
     *
     * @param tenantId 父运行租户
     * @param node     父图 SUBGRAPH 节点（读 {@code subWorkflowId} / {@code subReleaseId}）
     * @return 发布快照
     * @throws WorkflowNodeFailure 未绑定、版本缺失、版本不匹配、跨租户或图不可解析
     */
    public WorkflowSubgraphSnapshot resolve(long tenantId, WorkflowNode node) {
        return resolve(tenantId,
                WorkflowNodeConfig.longValue(node == null ? null : node.getConfig(), "subWorkflowId"),
                WorkflowNodeConfig.longValue(node == null ? null : node.getConfig(), "subReleaseId"));
    }

    /**
     * 按定义 / 版本主键解析发布快照。
     *
     * @param tenantId   父运行租户
     * @param workflowId 被调工作流定义主键
     * @param releaseId  钉死的发布版本主键
     * @return 发布快照
     * @throws WorkflowNodeFailure 未绑定、版本缺失、版本不匹配、跨租户或图不可解析
     */
    public WorkflowSubgraphSnapshot resolve(long tenantId, Long workflowId, Long releaseId) {
        if (workflowId == null) {
            throw new WorkflowNodeFailure("子图节点未绑定被调工作流，请选择后再运行");
        }
        if (releaseId == null) {
            throw new WorkflowNodeFailure("子图节点未选择发布版本，请重新绑定");
        }
        WorkflowRelease release = releases.findById(releaseId)
                .orElseThrow(() -> new WorkflowNodeFailure("子图绑定的发布版本已不存在，请重新选择版本"));
        if (!workflowId.equals(release.getWorkflowId())) {
            throw new WorkflowNodeFailure("子图绑定的发布版本不属于所选工作流，请重新选择版本");
        }
        WorkflowDef def = defs.findById(workflowId)
                .orElseThrow(() -> new WorkflowNodeFailure("子图绑定的工作流不存在，请重新选择"));
        if (def.getTenantId() == null || def.getTenantId() != tenantId) {
            // 跨租户一律拒绝：即使知道别的租户的 releaseId，也不能借子图跑过去
            throw new WorkflowNodeFailure("子图绑定的工作流不属于当前租户，无法运行");
        }
        WorkflowGraph graph = graphOf(release);
        return new WorkflowSubgraphSnapshot(release.getId(), def.getId(), def.getName(),
                def.getCode(), release.getVersion(), graph);
    }

    /**
     * 宽松解析：任何失败都返回空，供只读投影 / 校验器使用。
     *
     * @param tenantId   租户
     * @param workflowId 定义主键
     * @param releaseId  发布版本主键
     * @return 快照；不可用时为空
     */
    public Optional<WorkflowSubgraphSnapshot> tryResolve(long tenantId, Long workflowId, Long releaseId) {
        try {
            return Optional.of(resolve(tenantId, workflowId, releaseId));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * 发布快照 → 图。只在版本首次出现时解析一次。
     *
     * <p>缓存会校验 release.workflowId 一致；发布行本身不可变，不存在「同 id 换图」。
     *
     * @param release 发布版本
     * @return 图
     * @throws WorkflowNodeFailure 图 JSON 不可解析
     */
    private WorkflowGraph graphOf(WorkflowRelease release) {
        CachedGraph cached = graphCache.get(release.getId());
        if (cached != null && release.getWorkflowId() != null
                && release.getWorkflowId().equals(cached.workflowId())) {
            return cached.graph();
        }
        WorkflowGraph graph;
        try {
            graph = codec.parse(release.getGraphJson());
        } catch (RuntimeException ex) {
            throw new WorkflowNodeFailure("子图绑定的发布版本内容不完整，无法运行", ex);
        }
        if (graphCache.size() > CACHE_MAX) {
            graphCache.clear();
        }
        graphCache.put(release.getId(), new CachedGraph(release.getWorkflowId(), graph));
        return graph;
    }

    /**
     * 已解析的发布图缓存。
     *
     * @param workflowId 发布版本所属定义
     * @param graph      解析结果
     */
    private record CachedGraph(Long workflowId, WorkflowGraph graph) {
    }
}
