package com.agenthub.workflow.application;

import com.agenthub.workflow.domain.enums.WorkflowEdgeType;
import com.agenthub.workflow.domain.enums.WorkflowNodeType;
import com.agenthub.workflow.domain.model.WorkflowEdge;
import com.agenthub.workflow.domain.model.WorkflowNode;
import com.agenthub.workflow.domain.service.JsonLogicEvaluator;
import com.agenthub.workflow.domain.service.WorkflowLoopPolicy;
import com.agenthub.workflow.domain.service.WorkflowNodeConfig;
import com.agenthub.workflow.domain.service.WorkflowNodeInputs;
import com.agenthub.workflow.infra.WorkflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * 无外部出站的控制类节点执行器。所属域：workflow 的 application。
 *
 * <p>从 {@code WorkflowEngine.executeNode} 的 switch 搬出：START / END / CONDITION /
 * PARALLEL_FORK / PARALLEL_JOIN 五类节点只做「算」——算入参 JSON、产出 JSON、
 * 选中边或失败文案；**不写库、不发布卡片、不迁移 run 状态**。
 *
 * <p>为什么只算不写：落库（{@code node_runs.update}）与卡片发布（{@code publisher.node}）
 * 是引擎的公共前后处理，必须保持「先落库、后发布」与异常分类（ERROR 边优先于 FAIL_RUN）
 * 只有一处实现。执行器把结果交回引擎，由引擎统一走 {@code succeed} / {@code handleFailure}，
 * 顺序与搬出前逐字一致。
 *
 * <p>边界：AGENT / TOOL 有外部出站、HUMAN 要写待办并暂停 run，都不进本类。
 */
public class WorkflowNodeExecutors {

    /** 全站 ObjectMapper：组装节点入参 / 产出 JSON。 */
    private final ObjectMapper json;

    /** 图 / 上下文读写：入参映射、默认出边。 */
    private final WorkflowContextSupport context;

    /** 工作流配置：并行分叉硬顶。 */
    private final WorkflowProperties properties;

    /**
     * @param json       全站 ObjectMapper
     * @param context    图 / 上下文读写
     * @param properties 工作流配置（并行分支上限）
     */
    public WorkflowNodeExecutors(ObjectMapper json, WorkflowContextSupport context,
                                 WorkflowProperties properties) {
        this.json = json;
        this.context = context;
        this.properties = properties;
    }

    /**
     * 执行一个控制类节点，返回「该写什么」而不是直接写库。
     *
     * @param type    节点类型，只接受 START / END / CONDITION / PARALLEL_FORK / PARALLEL_JOIN
     * @param runtime 图
     * @param node    节点定义
     * @param ctx     本轮求值上下文（输入 / 已完成产出）
     * @return 计算结果；成功带产出与选中边，失败只带文案
     * @throws IllegalArgumentException 传入非控制类节点（分派错误，不是业务失败）
     */
    public ControlOutcome execute(WorkflowNodeType type, WorkflowRuntimeGraph runtime,
                                  WorkflowNode node, ObjectNode ctx) {
        return switch (type) {
            case START -> start(runtime, node, ctx);
            case END -> end(node, ctx);
            case CONDITION -> condition(runtime, node, ctx);
            case PARALLEL_FORK -> fork(runtime, node, ctx);
            case PARALLEL_JOIN -> join(runtime, node, ctx);
            /*
             * AGENT / TOOL 有出站、HUMAN 要写待办，仍由引擎执行。
             * 走到这里说明分派写错了，直接抛给引擎的 RuntimeException 分支明确失败，不静默跳过。
             */
            default -> throw new IllegalArgumentException("非控制类节点不应进入控制节点执行器：" + type);
        };
    }

    /**
     * START：入参取整份上下文输入，产出原样透传 {@code ctx.input}。
     */
    private ControlOutcome start(WorkflowRuntimeGraph runtime, WorkflowNode node, ObjectNode ctx) {
        String input = context.inputOf(ctx, null).toString();
        // 没有输入时给空对象：保证 output_json 是可解析 JSON，卡片与回放不用处理 null
        String output = ctx.get("input") == null
                ? json.createObjectNode().toString()
                : ctx.get("input").toString();
        return ControlOutcome.succeeded(input, output, context.defaultEdges(runtime, node));
    }

    /**
     * END：产出按 {@code outputMapping} 组装，没配映射给「节点编码 + 输入快照」兜底。
     */
    private ControlOutcome end(WorkflowNode node, ObjectNode ctx) {
        String input = context.inputOf(ctx, node.getConfig()).toString();
        ObjectNode result = WorkflowNodeInputs.mapped(ctx,
                WorkflowNodeConfig.objectValue(node.getConfig(), "outputMapping"));
        if (result.isEmpty()) {
            // 没配映射就给节点编码＋输入快照，保证 run 输出不是空对象
            result.set("nodeCode", json.getNodeFactory().textNode(node.getCode()));
            result.set("input", ctx.get("input"));
        }
        return ControlOutcome.succeeded(input, result.toString(), List.of());
    }

    /**
     * CONDITION：按图内顺序求值 COND 边，选中第一条为真的边；都不为真走 DEFAULT。
     */
    private ControlOutcome condition(WorkflowRuntimeGraph runtime, WorkflowNode node, ObjectNode ctx) {
        String input = context.inputOf(ctx, node.getConfig()).toString();
        List<WorkflowEdge> chosen = chooseConditionEdge(runtime, node, ctx);
        if (chosen.isEmpty()) {
            // 与搬出前一致：先算入参再判失败，由引擎决定是否落 input_json
            return ControlOutcome.failed(input,
                    "条件「" + node.getCode() + "」所有 COND 都为假且没有 DEFAULT 出口");
        }
        ObjectNode probe = json.createObjectNode();
        probe.put("matchedEdgeId", chosen.get(0).getId());
        return ControlOutcome.succeeded(input, probe.toString(), chosen);
    }

    /**
     * PARALLEL_FORK：先核对分支数硬顶，再铺开全部非 ERROR 出边。
     *
     * <p>分支数按非 ERROR 出边计；超额直接失败该 FORK，错误边可接管，否则整 run FAILED
     * （比「静默排队」更可预期、可测）。
     */
    private ControlOutcome fork(WorkflowRuntimeGraph runtime, WorkflowNode node, ObjectNode ctx) {
        List<WorkflowEdge> edges = context.defaultEdges(runtime, node);
        int branches = edges.size();
        int limit = properties.effectiveMaxParallelBranches();
        if (branches > limit) {
            /*
             * 超额失败不写入参：与搬出前一致（失败行只带 error_summary 与错误边），
             * 所以这里 inputJson 传 null，由引擎跳过 setInputJson。
             */
            return ControlOutcome.failed(null,
                    "并行分叉「" + node.getCode() + "」有 " + branches
                            + " 条分支，超过上限 " + limit
                            + "（agenthub.workflow.max-parallel-branches）；已拒绝本次扇出");
        }
        return ControlOutcome.succeeded(context.inputOf(ctx, node.getConfig()).toString(),
                json.createObjectNode().toString(), edges);
    }

    /**
     * PARALLEL_JOIN：入边由图调度等齐（{@code pickNext} 的 allDecided / anySelected），
     * 到这里只需产出空对象并选中全部非 ERROR 出边。
     */
    private ControlOutcome join(WorkflowRuntimeGraph runtime, WorkflowNode node, ObjectNode ctx) {
        return ControlOutcome.succeeded(context.inputOf(ctx, node.getConfig()).toString(),
                json.createObjectNode().toString(), context.defaultEdges(runtime, node));
    }

    /**
     * CONDITION 选路：按图内顺序求值 COND 边，第一条为真即走；都不为真走 DEFAULT。
     */
    private List<WorkflowEdge> chooseConditionEdge(WorkflowRuntimeGraph runtime, WorkflowNode node,
                                                   ObjectNode ctx) {
        WorkflowEdge fallback = null;
        for (WorkflowEdge edge : runtime.outgoing(node.getId())) {
            WorkflowEdgeType type = WorkflowEdgeType.parseOrDefault(edge.getType());
            if (type == WorkflowEdgeType.DEFAULT || WorkflowLoopPolicy.isFallbackEdge(type)) {
                // DEFAULT 与声明回边都是「条件不成立」的那一条；校验保证一个条件节点只有一条
                if (fallback == null) {
                    fallback = edge;
                }
                continue;
            }
            if (type != WorkflowEdgeType.COND) {
                continue;
            }
            JsonNode rule = conditionOf(edge);
            if (rule == null) {
                continue;
            }
            if (JsonLogicEvaluator.test(rule, ctx)) {
                return List.of(edge);
            }
        }
        return fallback == null ? List.of() : List.of(fallback);
    }

    /**
     * 条件既可能在边上 config.condition，也兼容刀1 前端只写 label 的历史数据；
     * 后者没有表达式，按「不成立」处理，由 DEFAULT 兜底。
     */
    private JsonNode conditionOf(WorkflowEdge edge) {
        if (edge.getConfig() == null || !edge.getConfig().isObject()) {
            return null;
        }
        JsonNode rule = edge.getConfig().get("condition");
        return rule == null || rule.isNull() ? null : rule;
    }

    /**
     * 控制类节点的计算结果：只描述「该写入什么」，由引擎负责落库与发布。
     *
     * @param inputJson     节点入参 JSON；null 表示本次不写 input_json（并行分叉超额失败时保持原值）
     * @param outputJson    节点产出 JSON；失败时为空
     * @param selectedEdges 选中边；失败时为空
     * @param failure       失败文案；null 表示成功
     */
    public record ControlOutcome(String inputJson, String outputJson,
                                 List<WorkflowEdge> selectedEdges, String failure) {

        /**
         * 成功结果。
         *
         * @param inputJson     入参 JSON
         * @param outputJson    产出 JSON
         * @param selectedEdges 选中边
         * @return 无失败文案的结果
         */
        public static ControlOutcome succeeded(String inputJson, String outputJson,
                                               List<WorkflowEdge> selectedEdges) {
            return new ControlOutcome(inputJson, outputJson, selectedEdges, null);
        }

        /**
         * 失败结果（交回引擎走 ERROR 边优先 / FAIL_RUN 收口）。
         *
         * @param inputJson 入参 JSON；null 表示不写入参
         * @param failure   失败文案
         * @return 带失败文案的结果
         */
        public static ControlOutcome failed(String inputJson, String failure) {
            return new ControlOutcome(inputJson, null, List.of(), failure);
        }
    }
}
