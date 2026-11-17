package com.agenthub.runtime.domain.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮 AgentScope 执行的可落库结果。所属域：runtime。
 * traces 按引擎顺序，只含真实的思考和工具结果。工具不带实参。
 */
@Data
public class TrialTurn {

    private String assistantText = "";
    private Integer promptTokens;
    private Integer completionTokens;
    /**
     * 本轮生效的工具结果回灌上限。时间线展示与回灌同截；≤0 表示展示侧不按该项截。
     * obs TOOL span 仍用 traces 里的脱敏全文，不读此字段。
     */
    private int toolResultMaxChars;
    /** 思考与工具。没有思考时不含 REASONING */
    private List<TrialTrace> traces = new ArrayList<>();
    /**
     * 计划待批准：本轮只产出计划，没有终答。
     * 为 true 时调用方不写 assistant 事件、也不把空助手正文推进记忆窗口，
     * 避免时间线里出现一条空回复、也避免窗口摘要被空内容推进。
     */
    private boolean assistantSuppressed;
    /**
     * 本轮写进工作区、可供下载的文件。
     * 与 traces 分开存放：traces 是会被截断的过程记录，产物要原样保留文件名与体积。
     * 同一参与者重复写同一路径时按最后一次覆盖，避免刷出一排相同卡片；不同参与者各留一份。
     */
    private List<GeneratedFile> files = new ArrayList<>();

    /**
     * 合并另一轮结果里的产物（Router 多子 / 计划多步）。
     * 同一参与者写同名文件按最后一次覆盖（前端也是如此去重）；不同参与者同名各留一份。
     *
     * @param more 另一轮结果里的产物，可空
     */
    public void mergeFiles(List<GeneratedFile> more) {
        if (more == null || more.isEmpty()) {
            return;
        }
        // files 可能由 setFiles 塞进来一个不可变列表（如 sink 的只读快照）：
        // 先复制成可变副本，避免 removeIf 抛 UnsupportedOperationException
        List<GeneratedFile> merged = new ArrayList<>(files);
        for (GeneratedFile one : more) {
            // 去重键带参与者：两位专家各写同名文件是两份产物，不能互相覆盖（否则文件会改挂作者）
            merged.removeIf(existing -> sameSlot(existing, one));
            merged.add(one);
        }
        this.files = merged;
    }

    /**
     * 两份产物是否占同一个槽位：同一参与者 + 同一文件名。
     *
     * <p>历史数据没有参与者身份（空串），按空身份比较，仍保持旧的「同名覆盖」。
     *
     * @param left  已收集的产物
     * @param right 新来的产物
     * @return true 表示后者应覆盖前者
     */
    private static boolean sameSlot(GeneratedFile left, GeneratedFile right) {
        String leftOwner = left.participantId() == null ? "" : left.participantId();
        String rightOwner = right.participantId() == null ? "" : right.participantId();
        return leftOwner.equals(rightOwner) && left.fileName().equals(right.fileName());
    }
}
