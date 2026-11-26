package com.agenthub.workflow.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 校验期只读的工具绑定快照。所属域：workflow 的 domain。
 * <p>关心工具项是否存在、是否启用，以及工具定义里的顶层必填参数：
 * 发布 / 校验要能指出「哪个节点的哪个必填参数没配来源」。
 * <p>工具实参不在本域落库；requiredInputs 只是 schema 的只读投影。
 */
@Data
@NoArgsConstructor
public class WorkflowToolBinding {

    /** tool_item.id */
    private Long id;
    /** 工具调用名，用于中文报错 */
    private String name;
    /** 1 启用 0 停用 */
    private Integer status;
    /** 顶层必填参数名：JSON Schema 的 properties ∩ required，未配置时为空列表 */
    private List<String> requiredInputs = List.of();

    /**
     * 只关心存在 / 启用时的绑定（无 schema 定义）。
     *
     * @param id     工具项 id
     * @param name   工具调用名
     * @param status 1 启用 0 停用
     */
    public WorkflowToolBinding(Long id, String name, Integer status) {
        this(id, name, status, List.of());
    }

    /**
     * 完整绑定。
     *
     * @param id             工具项 id
     * @param name           工具调用名
     * @param status         1 启用 0 停用
     * @param requiredInputs 顶层必填参数；null 按空列表处理
     */
    public WorkflowToolBinding(Long id, String name, Integer status, List<String> requiredInputs) {
        this.id = id;
        this.name = name;
        this.status = status;
        setRequiredInputs(requiredInputs);
    }

    /**
     * 写入必填参数；null 归一成空列表，避免校验器到处判空。
     *
     * @param requiredInputs 顶层必填参数
     */
    public void setRequiredInputs(List<String> requiredInputs) {
        this.requiredInputs = requiredInputs == null ? List.of() : List.copyOf(requiredInputs);
    }

    /**
     * @return 顶层必填参数；未设置时为不可变空列表
     */
    public List<String> getRequiredInputs() {
        return requiredInputs == null ? List.of() : requiredInputs;
    }
}
