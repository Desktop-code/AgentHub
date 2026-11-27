package com.agenthub.workflow.domain.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 校验期只读的子图绑定快照。所属域：workflow 的 domain。
 * <p>子图节点钉死「被调工作流 + 某个已发布版本」；快照按 releaseId 建索引，
 * 由应用层查出该版本的 graph_json 后投影出 START 入参声明的必填键。
 * <p>为什么按 releaseId 而不是 workflowId：同一个被调工作流可以在一张图里被钉在不同版本上，
 * 同名键的必填口径必须按各自那一版算。
 * <p>requiredInputs 只是声明投影，不表示子图本刀可执行。
 */
@Data
@NoArgsConstructor
public class WorkflowSubgraphBinding {

    /** workflow_release.id（钉死的版本） */
    private Long releaseId;
    /** 该版本所属的 workflow_def.id */
    private Long workflowId;
    /** 版本号，用于中文报错 */
    private Integer version;
    /** 被调工作流名称，用于中文报错 */
    private String workflowName;
    /** 该版本 START 声明里的必填键；无声明时为空列表 */
    private List<String> requiredInputs = List.of();

    /**
     * 完整绑定。
     *
     * @param releaseId      发布版本 id
     * @param workflowId     所属工作流 id
     * @param version        版本号
     * @param workflowName   工作流名称
     * @param requiredInputs 该版本 START 声明的必填键；null 按空列表
     */
    public WorkflowSubgraphBinding(Long releaseId, Long workflowId, Integer version,
                                   String workflowName, List<String> requiredInputs) {
        this.releaseId = releaseId;
        this.workflowId = workflowId;
        this.version = version;
        this.workflowName = workflowName;
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
