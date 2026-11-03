package com.agenthub.workflow.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 校验期只读的工具绑定快照。所属域：workflow 的 domain。
 * <p>只关心工具项是否存在、是否启用；工具实参不在本域落库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowToolBinding {

    /** tool_item.id */
    private Long id;
    /** 工具调用名，用于中文报错 */
    private String name;
    /** 1 启用 0 停用 */
    private Integer status;
}
