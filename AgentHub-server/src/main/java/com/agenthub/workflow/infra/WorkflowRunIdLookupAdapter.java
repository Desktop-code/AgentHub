package com.agenthub.workflow.infra;

import com.agenthub.obs.domain.spi.WorkflowRunLookup;
import com.agenthub.workflow.domain.repository.WorkflowRunRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * obs 侧「工作流定义 → 最近 run id」端口的工作流实现。所属域：workflow 的 infra。
 *
 * <p>为什么实现放在这里：obs 是横切域，不能反向依赖 workflow 的仓储；workflow 已经依赖 obs
 * 的写入端口（{@code TraceRecorder}），这个方向再加一个只读适配不会产生环。
 *
 * <p>边界：只读、无事务；结果用于 {@code obs_trace.workflow_run_id IN (...)}，
 * 因此上限钳在 500，避免把超长 IN 列表拼进 SQL。
 */
@Component
public class WorkflowRunIdLookupAdapter implements WorkflowRunLookup {

    /** 单次过滤最多反查的 run 数：够覆盖近期排障，又不至于把 SQL 撑爆 */
    private static final int MAX_RUNS = 500;

    private final WorkflowRunRepository runs;

    /**
     * @param runs run 仓储（只取 id 列）
     */
    public WorkflowRunIdLookupAdapter(WorkflowRunRepository runs) {
        this.runs = runs;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> recentRunIds(long workflowDefId, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_RUNS);
        return runs.recentIdsByWorkflowId(workflowDefId, size).stream()
                .map(String::valueOf)
                .toList();
    }
}
