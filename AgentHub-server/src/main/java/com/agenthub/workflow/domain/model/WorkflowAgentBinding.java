package com.agenthub.workflow.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 校验期只读的 Agent 绑定快照。所属域：workflow 的 domain。
 * <p>由应用层一次性查出，避免校验器依赖 Spring / 仓储；运行模式用于拦截 ROUTER。
 * <p>requiredInputs 是 Agent 定义上「入参声明」的只读投影：有声明时，画布按声明出槽，
 * 声明里的必填键没配来源要在发布 / 校验时点名。
 */
@Data
@NoArgsConstructor
public class WorkflowAgentBinding {

    /** agent_def.id */
    private Long id;
    /** Agent 名称，用于中文报错 */
    private String name;
    /** REACT / ROUTER / PLAN_AND_EXECUTE */
    private String runMode;
    /** 1 启用 0 停用 */
    private Integer status;
    /** 入参声明里的必填键；无声明时为空列表 */
    private List<String> requiredInputs = List.of();

    /**
     * 只关心存在 / 启用 / 运行模式时的绑定（无入参声明）。
     *
     * @param id      Agent id
     * @param name    Agent 名称
     * @param runMode 运行模式
     * @param status  1 启用 0 停用
     */
    public WorkflowAgentBinding(Long id, String name, String runMode, Integer status) {
        this(id, name, runMode, status, List.of());
    }

    /**
     * 完整绑定。
     *
     * @param id             Agent id
     * @param name           Agent 名称
     * @param runMode        运行模式
     * @param status         1 启用 0 停用
     * @param requiredInputs 入参声明必填键；null 按空列表
     */
    public WorkflowAgentBinding(Long id, String name, String runMode, Integer status,
                                List<String> requiredInputs) {
        this.id = id;
        this.name = name;
        this.runMode = runMode;
        this.status = status;
        setRequiredInputs(requiredInputs);
    }

    /**
     * 写入必填键；null 归一成空列表，避免校验器到处判空。
     *
     * @param requiredInputs 必填键
     */
    public void setRequiredInputs(List<String> requiredInputs) {
        this.requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
    }

    /**
     * @return 必填键；未设置时为不可变空列表
     */
    public List<String> getRequiredInputs() {
        return requiredInputs == null ? List.of() : requiredInputs;
    }
}
