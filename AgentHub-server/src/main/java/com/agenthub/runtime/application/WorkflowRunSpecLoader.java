package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code ma_candidate} 里的工作流候选装载成本轮只读执行来源（刀5B）。所属域：runtime。
 *
 * <p>为什么单独一层：Router 一轮可能在池线程上跑，执行路径不允许边跑边查 {@code workflow_def}；
 * 装载阶段一次性把「可运行」的目标翻译成 {@link WorkflowRunSpec}，执行器只认快照。
 *
 * <p><b>只有满足下列条件的定义才进表</b>（不满足的候选在执行器里按「已跳过」展示）：
 * <ol>
 *   <li>定义存在、未删除、同租户；</li>
 *   <li>{@code status=HAS_RELEASE} 且 {@code default_release_id} 非空——草稿态跑不起来；</li>
 *   <li>说明非空——说明是判路由的唯一意图来源，空说明即使命中也没法解释；</li>
 * </ol>
 *
 * <p>与写入侧 {@code MaDefPolicy.assertWorkflowTargets} / 默认接待下拉共用同一套口径；
 * 这里多一层「装载时再校验」，是为了覆盖「加进池之后定义被取消发布」的窗口。
 *
 * <p>线程：无状态，可被请求线程直接调用；不缓存，跨轮取最新。
 */
@Component
public class WorkflowRunSpecLoader {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunSpecLoader.class);

    /** 定义状态：至少有一个已发布版本（与 workflow_def.status 现网取值一致） */
    private static final String STATUS_HAS_RELEASE = "HAS_RELEASE";

    private final WorkflowDefRepository defs;

    /**
     * @param defs 工作流定义仓储（只读）
     */
    public WorkflowRunSpecLoader(WorkflowDefRepository defs) {
        this.defs = defs;
    }

    /**
     * 装载一批工作流候选。
     *
     * <p>同一工作流被重复挂在池里时只保留第一条（写入侧已去重，这里防脏数据）；
     * 装载失败的目标只记 debug 日志，不打断其它候选。
     *
     * @param candidates 本方案的启用候选
     * @param tenantId   当前租户
     * @return workflow_def.id → 执行来源；没有工作流候选时为空表
     */
    public Map<Long, WorkflowRunSpec> load(List<MaCandidate> candidates, long tenantId) {
        Map<Long, WorkflowRunSpec> targets = new LinkedHashMap<>();
        if (candidates == null || candidates.isEmpty()) {
            return targets;
        }
        for (MaCandidate item : candidates) {
            if (!item.isWorkflowTarget() || item.getWorkflowDefId() == null) {
                continue;
            }
            Long workflowId = item.getWorkflowDefId();
            if (targets.containsKey(workflowId)) {
                continue;
            }
            WorkflowDef def = defs.findById(workflowId).orElse(null);
            if (def == null || def.getTenantId() == null || def.getTenantId() != tenantId) {
                log.debug("工作流目标不存在或跨租户 workflowId={}", workflowId);
                continue;
            }
            // 未发布 / 无默认发布指针：工作台禁止跑草稿，这里直接不进目标表
            if (!STATUS_HAS_RELEASE.equals(def.getStatus()) || def.getDefaultReleaseId() == null) {
                log.debug("工作流目标无可用发布 workflowId={} status={}", workflowId, def.getStatus());
                continue;
            }
            if (def.getDescription() == null || def.getDescription().isBlank()) {
                log.debug("工作流目标说明为空 workflowId={}", workflowId);
                continue;
            }
            targets.put(workflowId, new WorkflowRunSpec(def.getId(), def.getCode(), def.getName(),
                    def.getDescription(), def.getDefaultReleaseId()));
        }
        return targets;
    }

    /**
     * 单个工作流是否仍可作为路由目标（换线 / 选项展示用）。
     *
     * @param workflowId 定义主键
     * @param tenantId   当前租户
     * @return 执行来源；不可用时为空
     */
    public java.util.Optional<WorkflowRunSpec> find(Long workflowId, long tenantId) {
        if (workflowId == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(
                load(List.of(singleCandidate(workflowId)), tenantId).get(workflowId));
    }

    /** 造一条只含 workflowDefId 的临时候选，复用 {@link #load} 的同一套门闩。 */
    private static MaCandidate singleCandidate(Long workflowId) {
        MaCandidate candidate = new MaCandidate();
        candidate.setTargetType(MaCandidate.TARGET_WORKFLOW);
        candidate.setWorkflowDefId(workflowId);
        candidate.setEnabled(1);
        return candidate;
    }
}
