package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.ScratchBoard;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.ScratchContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排观测字段的统一组装。所属域：runtime。
 *
 * <p>为什么需要它：{@code ma.def_id / ma.code / ma.template / ma.root} 这套方案标识原本在
 * 判路由、固定、竞争、评审、路由后串行五个 ROUTE 收尾里各写一遍；共享草稿统计与子 AGENT
 * 的 {@code ma.child_run_mode} 同理。集中后改一处即可，避免某个模板漏键导致按 {@code ma.def_id}
 * 检索时查不到数据。
 *
 * <p><b>对外契约</b>：键名与插入顺序是泳道/检索的对外契约，本类只负责「原样按序放入」；
 * 本次抽取不改任何键名、不改 brief 文案。
 *
 * <p>线程相关：全部为纯函数，无状态；{@link #scratchAttrs()} 读取当前线程的草稿板，
 * 因此在池线程里调用前必须已由提交线程完成 {@code ScratchContext.callAs} 传递。
 */
public final class RouterObs {

    private RouterObs() {
    }

    /**
     * 方案级扁平 attrs：根步骤与判路由步骤共用，字段名与交付对照表一致。
     *
     * @param spec   方案来源，可空；为空时不动传入的 map
     * @param fields 目标 map
     */
    public static void maRootAttrs(MaRunSpec spec, Map<String, Object> fields) {
        if (spec == null || fields == null) {
            return;
        }
        fields.put("ma.def_id", spec.maId());
        fields.put("ma.code", spec.label());
        fields.put("ma.template", spec.template());
        // 方案就是自己的根：不再区分「绑根 / 虚拟根」，统一标注 SCHEME 供泳道识别
        fields.put("ma.root", "SCHEME");
    }

    /**
     * 多问拆分的只读打点：是否拆分、问题条数、去重后的目标数。
     *
     * <p>只写计数，<b>不写</b>问题正文与目标名：用户问句可能含业务数据，ROUTE attrs 会落进
     * 追踪表。这三个数足够核对「块位是否按提问顺序」，不必再靠日志反推。
     *
     * @param fields        目标 attrs map
     * @param questionSplit 本轮是否走了「一条消息多个问题」拆分
     * @param questionCount 问题条数（＝意图条数，不按目标去重）
     * @param topicCount    去重后的目标数（与决策块正文里的名单个数一致）
     */
    public static void questionAttrs(Map<String, Object> fields, boolean questionSplit,
                                     int questionCount, int topicCount) {
        if (fields == null) {
            return;
        }
        fields.put("ma.question_split", questionSplit);
        fields.put("ma.question_count", Math.max(0, questionCount));
        fields.put("ma.question_topic_count", Math.max(0, topicCount));
    }

    /**
     * 方案级扁平 attrs 的 map 包装：给 ROUTE 类 span 直接 putAll。
     *
     * @param spec 方案来源；可空
     * @return 可合入 attrs 的键值；spec 为空时为空 map
     */
    public static Map<String, Object> maAttrs(MaRunSpec spec) {
        Map<String, Object> fields = new LinkedHashMap<>();
        maRootAttrs(spec, fields);
        return fields;
    }

    /**
     * 虚拟根步骤名：方案名优先，缺失回退编码；都为空时给中性中文。
     *
     * @param spec 方案来源
     * @return 展示名
     */
    public static String maRootName(MaRunSpec spec) {
        if (spec == null) {
            return "编排根";
        }
        if (spec.name() != null && !spec.name().isBlank()) {
            return spec.name().trim();
        }
        return spec.label().isBlank() ? "编排根" : spec.label();
    }

    /**
     * 本轮共享草稿的只读统计，合入 ROUTE attrs。
     *
     * <p>只写键数与冲突次数，<b>不写</b>任何 key/value 正文：草稿内容可能含用户数据，
     * 落进追踪表会绕过「草稿不落库」的边界。非 SHARED_SCRATCH 时返回空 Map，
     * {@code ObsAttrs.withFields} 会原样跳过。
     *
     * @return 可合入 attrs 的扁平字段；无板时为空
     */
    public static Map<String, Object> scratchAttrs() {
        ScratchBoard board = ScratchContext.board();
        if (board == null) {
            return Map.of();
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("ma.scratch.key_count", board.keyCount());
        attrs.put("ma.scratch.conflict_count", board.conflictCount());
        return attrs;
    }

    /**
     * 子 AGENT span 的 attrs：P&E 子标注运行模式，回退用原问时标注 fallback。
     *
     * @param agent           子 Agent；可空
     * @param brief           人话摘要
     * @param subQueryFallback 是否回退用了用户原问
     * @return attrs JSON
     */
    public static String childAgentAttrs(AgentDef agent, String brief, boolean subQueryFallback) {
        return childAgentAttrs(agent, brief, subQueryFallback, null);
    }

    /**
     * 同 {@link #childAgentAttrs(AgentDef, String, boolean)}，并合入调用方附加的扁平字段。
     *
     * <p>评审环用它对子 AGENT span 标注 {@code ma.role=executor|critic} 与
     * {@code ma.critic_round}，让泳道在不改父链的前提下区分「初稿 / 评审 / 回修」。
     *
     * @param extra 附加字段；可空
     * @return attrs JSON
     */
    public static String childAgentAttrs(AgentDef agent, String brief, boolean subQueryFallback,
                                         Map<String, Object> extra) {
        Map<String, Object> fields = new LinkedHashMap<>();
        // 只在 P&E 子上打点：REACT 子是默认形态，不打点也不影响既有面板
        if (agent != null && AgentRunMode.PLAN_AND_EXECUTE.name().equals(agent.getRunMode())) {
            fields.put("ma.child_run_mode", AgentRunMode.PLAN_AND_EXECUTE.name());
        }
        if (subQueryFallback) {
            fields.put("sub_query_fallback", true);
        }
        if (extra != null && !extra.isEmpty()) {
            fields.putAll(extra);
        }
        return ObsAttrs.withFields(ObsAttrs.withBrief(null, brief), fields);
    }

    /** 外壳种类键。真专家不写；前端据此把 AGENT 显示成「工作流」或「方案」。 */
    public static final String OBS_ROLE = "obs.role";

    /**
     * 综答种类键。值为 {@code concat} 时表示这一步是拼接、没有模型调用。
     * 拓扑据此认出汇总节点，不依赖「又开了一次 LLM」。
     */
    public static final String OBS_SUMMARY = "obs.summary";

    /** {@link #OBS_SUMMARY}：按序拼接，无模型调用。 */
    public static final String SUMMARY_CONCAT = "concat";

    /** {@link #OBS_ROLE}：这一步是工作流外壳，节点仍在工作流自己的 trace 里。 */
    public static final String ROLE_WORKFLOW = "workflow";

    /** {@link #OBS_ROLE}：这一步是被路由进来的方案外壳，不是方案自己的编排根。 */
    public static final String ROLE_SCHEME = "scheme";

    /**
     * 外壳成功收口的人话摘要。真专家保持「执行子 Agent」，避免顶层单 Agent 文案被改掉。
     *
     * @param scheme   目标是另一套方案
     * @param workflow 目标是工作流
     * @return 写入 brief 的短句
     */
    public static String shellBrief(boolean scheme, boolean workflow) {
        if (workflow) {
            return "执行工作流";
        }
        if (scheme) {
            return "执行方案";
        }
        return "执行子 Agent";
    }

    /**
     * 给外壳 attrs 补上种类。真专家原样返回调用方的 extra，不新增键。
     *
     * @param extra    模板已有的扁平字段；可空
     * @param scheme   目标是另一套方案
     * @param workflow 目标是工作流
     * @return 合入种类后的新表；非外壳时就是传入的 extra
     */
    public static Map<String, Object> withShellRole(Map<String, Object> extra, boolean scheme, boolean workflow) {
        Map<String, Object> role = shellRoleFields(scheme, workflow);
        if (role.isEmpty()) {
            return extra;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (extra != null && !extra.isEmpty()) {
            fields.putAll(extra);
        }
        fields.putAll(role);
        return fields;
    }

    /**
     * 外壳种类字段。空表表示真专家，调用方不要写入。
     *
     * @param scheme   目标是另一套方案
     * @param workflow 目标是工作流
     * @return 仅含 {@link #OBS_ROLE} 的表，或空表
     */
    public static Map<String, Object> shellRoleFields(boolean scheme, boolean workflow) {
        if (workflow) {
            return Map.of(OBS_ROLE, ROLE_WORKFLOW);
        }
        if (scheme) {
            return Map.of(OBS_ROLE, ROLE_SCHEME);
        }
        return Map.of();
    }

    /**
     * 记下 CONCAT 综答这一步。不调用模型，只留一个挂在本层 ROUTE 下的节点，
     * 拓扑才能在「拼接 / 无调用」时也画出汇总，而不是只在又开了 LLM 时才有。
     *
     * @param trace     本轮 trace；可空
     * @param routeSpan 本层 ROUTE；可空（没有路由步时不记）
     */
    public static void noteConcat(ObsTraceScope trace, Long routeSpan) {
        if (trace == null || routeSpan == null) {
            return;
        }
        Long span = trace.open(SpanType.SYSTEM, "拼接", null, routeSpan, null);
        if (span == null) {
            return;
        }
        SpanClose close = new SpanClose();
        close.setStatus(ObsStatus.OK);
        close.setOutputText("按序拼接，无模型调用");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ma.aggregate", "CONCAT");
        fields.put(OBS_SUMMARY, SUMMARY_CONCAT);
        close.setAttrsJson(ObsAttrs.withFields(ObsAttrs.withBrief(null, "无调用"), fields));
        trace.close(span, close);
    }

    /**
     * 关闭虚拟根编排步骤。成功写 OK，失败写脱敏 summary；attrs 固定带 ma.def_id / code / template。
     *
     * @param trace   本轮 trace，可空
     * @param span    根步骤 id，可空（绑根默认不开，保持旧父链）
     * @param spec    方案来源，用于 attrs
     * @param failure 失败异常，可空
     */
    public static void closeMaRoot(ObsTraceScope trace, Long span, MaRunSpec spec, RuntimeException failure) {
        if (trace == null || span == null) {
            return;
        }
        SpanClose close = new SpanClose();
        Map<String, Object> fields = new LinkedHashMap<>();
        maRootAttrs(spec, fields);
        if (failure == null) {
            close.setStatus(ObsStatus.OK);
            close.setOutputText("编排完成");
            close.setAttrsJson(ObsAttrs.withFields(ObsAttrs.withBrief(null, "编排根"), fields));
        } else {
            close.setStatus(ObsStatus.ERROR);
            close.setErrorSummary(failure.getMessage());
            close.setOutputText(failure.getMessage());
            close.setAttrsJson(ObsAttrs.withFields(
                    ObsAttrs.withCause(ObsAttrs.withBrief(null, "编排失败"), CauseDigest.of(failure)), fields));
        }
        trace.close(span, close);
        trace.bindParent(null);
    }
}
