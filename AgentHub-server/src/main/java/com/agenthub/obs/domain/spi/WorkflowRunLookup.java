package com.agenthub.obs.domain.spi;

import java.util.List;

/**
 * obs 查询工作流 trace 时的「定义 → run」反查端口。所属域：obs 的 domain（SPI）。
 *
 * <p>为什么用端口而不是直接 join {@code workflow_run}：obs 是横切域，不该反向依赖 workflow 的仓储；
 * 实现方放在 workflow 的 infra（那个方向本来就依赖 obs 的写入端口），obs 只认这个最小接口。
 *
 * <p>线程 / 事务注意：只读，无事务要求；返回值只用于 {@code obs_trace.workflow_run_id IN (...)}。
 */
public interface WorkflowRunLookup {

    /**
     * 某工作流定义最近若干次 run 的 id（字符串形式，对齐 obs_trace.workflow_run_id）。
     *
     * @param workflowDefId workflow_def.id
     * @param limit         最多返回条数；实现方须自行钳制上限
     * @return run id 列表；没有实现或查不到时返回空列表，禁止返回 null
     */
    List<String> recentRunIds(long workflowDefId, int limit);
}
