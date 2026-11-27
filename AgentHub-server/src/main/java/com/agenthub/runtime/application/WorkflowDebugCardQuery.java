package com.agenthub.runtime.application;

import com.agenthub.runtime.domain.model.WorkflowRunView;
import com.agenthub.runtime.domain.service.WorkflowRunJson;
import com.agenthub.workflow.application.WorkflowGraphCodec;
import com.agenthub.workflow.domain.model.WorkflowDef;
import com.agenthub.workflow.domain.model.WorkflowGraph;
import com.agenthub.workflow.domain.model.WorkflowRun;
import com.agenthub.workflow.domain.model.WorkflowRunDetail;
import com.agenthub.workflow.domain.repository.WorkflowDefRepository;
import com.agenthub.workflow.domain.service.WorkflowOutcomeTexts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 为试跑详情装上与首页相同的工作流卡片。所属域：runtime 的 application。
 *
 * <p>职责：读 run 上的发布快照和图，交给 {@link WorkflowCardRebuilder#viewOf}
 * 现算视图，再按 {@link WorkflowRunJson#payload} 输出。不写会话事件，不改回放 enrich。
 *
 * <p>图损坏或定义被删时返回空，详情里的节点状态仍可用，不把查询打成 500。
 */
@Component
public class WorkflowDebugCardQuery {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDebugCardQuery.class);

    private final WorkflowCardRebuilder cards;
    private final WorkflowGraphCodec codec;
    private final WorkflowDefRepository defs;
    private final ObjectMapper json;

    /**
     * @param cards 按 run 现算卡片（与回放补全同一个类）
     * @param codec 图 JSON 解析
     * @param defs  定义名称 / 编码
     * @param json  全站 ObjectMapper
     */
    public WorkflowDebugCardQuery(WorkflowCardRebuilder cards, WorkflowGraphCodec codec,
                                  WorkflowDefRepository defs, ObjectMapper json) {
        this.cards = cards;
        this.codec = codec;
        this.defs = defs;
        this.json = json;
    }

    /**
     * 详情上的卡片载荷。形状与工作台 {@code workflow} 帧一致。
     *
     * @param detail 已组装的 run 详情；可空
     * @return 载荷；算不出时为 null
     */
    public Map<String, Object> payload(WorkflowRunDetail detail) {
        return payloadOf(viewOf(detail));
    }

    /**
     * 把一份已经算好的卡片转成出站载荷。直播快照直接用它，避免再解析一次图。
     *
     * @param view 卡片视图；可空
     * @return 载荷；空视图或空载荷时为 {@code null}
     */
    public Map<String, Object> payloadOf(WorkflowRunView view) {
        if (view == null) {
            return null;
        }
        Map<String, Object> body = WorkflowRunJson.payload(view);
        return body.isEmpty() ? null : body;
    }

    /**
     * 详情卡片补上直播快照里的节点文件行。
     *
     * <p>为什么需要它：{@code workflow_node_run} 没有「这个节点写了哪些文件」这一列，
     * 调试运行也没有工作台会话事件骨架。文件清单的唯一在内存记录就是
     * {@link WorkflowDebugLiveCards} 里那一帧；不补的话，刷新或者关掉调试框再打开，
     * 子图内部节点（以及回落到父框）的文件行就没了，下载入口跟着消失。
     *
     * <p>只搬 {@code files}：状态、正文、选项、时限仍以按库现算的为准，
     * 不把内存快照的整份节点覆盖回详情。合并口径与刷新回放一致
     * （{@link WorkflowCardRebuilder#carryNodeFiles}），同名文件不重复。
     *
     * @param payload 按库现算的卡片载荷；可空
     * @param live    直播快照里的卡片视图；可空
     * @return 补好文件行的载荷；没有可搬的内容时是入参本身
     */
    public Map<String, Object> withLiveFiles(Map<String, Object> payload, WorkflowRunView live) {
        if (payload == null || payload.isEmpty() || live == null || live.nodes().isEmpty()) {
            return payload;
        }
        try {
            ObjectNode target = json.valueToTree(payload);
            ObjectNode source = json.valueToTree(WorkflowRunJson.payload(live));
            WorkflowCardRebuilder.carryNodeFiles(source, target);
            return json.convertValue(target, new TypeReference<Map<String, Object>>() { });
        } catch (RuntimeException ex) {
            // 补文件是展示旁路：失败时保留按库现算的卡片，不让详情查询变红
            log.warn("调试卡片补文件行失败 runId={} type={}",
                    live.runId(), ex.getClass().getSimpleName());
            return payload;
        }
    }

    /**
     * 现算卡片。失败只记异常类型。
     *
     * @param detail run 详情；可空
     * @return 视图；没有 run 或图不可读时为 null
     */
    public WorkflowRunView viewOf(WorkflowRunDetail detail) {
        if (detail == null || detail.getRun() == null || detail.getRun().getId() == null) {
            return null;
        }
        WorkflowRun run = detail.getRun();
        try {
            WorkflowGraph graph = codec.parse(run.getGraphJson());
            WorkflowDef def = run.getWorkflowId() == null ? null : defs.findById(run.getWorkflowId()).orElse(null);
            String name = def == null || def.getName() == null ? "" : def.getName();
            String code = def == null || def.getCode() == null ? "" : def.getCode();
            String tail = WorkflowOutcomeTexts.assistantText(def, run, json);
            // 带图快照：回放 / 调试详情与直播走同一套投影，已发生的子图范围会铺成框
            return cards.viewOf(name, code, run, graph, detail.getNodeRuns(), tail);
        } catch (RuntimeException ex) {
            log.warn("调试卡片现算失败 runId={} type={}", run.getId(), ex.getClass().getSimpleName());
            return null;
        }
    }
}
