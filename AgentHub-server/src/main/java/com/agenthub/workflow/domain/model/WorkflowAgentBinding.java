package com.agenthub.workflow.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 校验期只读的 Agent 绑定快照。所属域：workflow 的 domain。
 * <p>由应用层一次性查出，避免校验器依赖 Spring / 仓储；运行模式用于拦截 ROUTER。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowAgentBinding {

    /** agent_def.id */
    private Long id;
    /** Agent 名称，用于中文报错 */
    private String name;
    /** REACT / ROUTER / PLAN_AND_EXECUTE */
    private String runMode;
    /** 1 启用 0 停用 */
    private Integer status;
}
