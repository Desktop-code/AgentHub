package com.agenthub.runtime.application.router;

import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.service.CauseDigest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 根 ROUTE 步骤的开与收。所属域：runtime。
 *
 * <p>为什么需要它：六套编排模板各自都要开一个同名 ROUTE、在成功/失败两条路径上写
 * {@code status / outputText / route_decision / brief / attrs}，原本这 10 段代码逐字重复。
 * 收进这里后，各模板只保留「自己差异的部分」：输出文案、决策 JSON、模板专属 attrs 键。
 *
 * <p>父步骤：普通顶层单方案保持空。入口智能网关这一层由 {@link #judgeHangsOnRoot(boolean)}
 * 打开后，judge 挂到网关根上。已经进入目标方案时同样挂当前方案根。
 * 没有网关的单方案不要打开这个开关，否则 judge 会变成方案根的子节点。
 *
 * <p><b>对外契约</b>：attrs 的组装方式（brief 在前、模板字段在后）与 {@code closed} 只生效一次
 * 的语义与拆分前完全一致；键名、键顺序、brief 文案由调用方传入，本类不得二次加工。
 *
 * <p>线程相关：本类非线程安全，一个实例只服务一次执行（在单条执行链内创建）。
 */
public final class RouteSpan {

    /**
     * 仅入口智能网关：本层 ROUTE 挂到当前方案根。
     * 普通单方案不设置。嵌套是否挂根另看 {@link MaNestedContext}，不靠这个开关。
     */
    private static final ThreadLocal<Boolean> JUDGE_ON_ROOT = new ThreadLocal<>();

    /** 本轮 trace；可空（未开追踪时所有方法都是空操作） */
    private final ObsTraceScope trace;

    /** ROUTE 步骤 id；trace 为空时也为空 */
    private final Long id;

    /** 成功收尾只能生效一次：失败分支据此判断「是否已经写过 OK」 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private RouteSpan(ObsTraceScope trace, Long id) {
        this.trace = trace;
        this.id = id;
    }

    /**
     * 开一个 ROUTE 步骤并把它设为当前父步骤。
     *
     * @param trace   本轮 trace，可空
     * @param name    步骤名（各模板固定字符串，如 fixed-parallel / compete / critic-loop）
     * @param agentId 绑根的 Agent id；虚拟根同样传合成壳的 id
     * @return 收尾句柄；trace 为空时返回空句柄，后续调用全部安全空转
     */
    public static RouteSpan open(ObsTraceScope trace, String name, Long agentId) {
        if (trace == null) {
            return new RouteSpan(null, null);
        }
        // 普通顶层单方案：父保持空。入口网关或已经进入目标方案时，栈父就是该挂的那条根。
        Long parent = hangRouteOnCurrentRoot() ? trace.implicitParent() : null;
        Long span = trace.open(SpanType.ROUTE, name, agentId, parent, null);
        // 先链后扇：ROUTE 之后的子 AGENT 默认挂到它下面，模板里不必再各自记住这一步
        trace.bindParent(span);
        return new RouteSpan(trace, span);
    }

    /**
     * 入口智能网关这一层是否把 judge 挂到网关根上。
     * 只在 {@code executeRun} 里按模板开关，退出时还原进入前的值，避免串到内层普通方案。
     *
     * @param on true 表示本层 ROUTE 使用当前栈父
     */
    public static void judgeHangsOnRoot(boolean on) {
        if (on) {
            JUDGE_ON_ROOT.set(Boolean.TRUE);
            return;
        }
        JUDGE_ON_ROOT.remove();
    }

    /**
     * @return 入口网关开关是否打开；未设置时为 false
     */
    public static boolean judgeHangsOnRoot() {
        return Boolean.TRUE.equals(JUDGE_ON_ROOT.get());
    }

    /**
     * 本层 ROUTE 是否应挂到当前栈父。嵌套方案与入口网关挂上，普通单方案不挂。
     *
     * @return true 时父取 {@link ObsTraceScope#implicitParent()}
     */
    private static boolean hangRouteOnCurrentRoot() {
        return MaNestedContext.inside() || judgeHangsOnRoot();
    }

    /**
     * @return ROUTE 步骤 id；可空。子 AGENT 需要显式指定父步骤时用它。
     */
    public Long id() {
        return id;
    }

    /**
     * @return 是否已写过收尾（成功或失败）
     */
    public boolean closed() {
        return closed.get();
    }

    /**
     * 成功收尾：写 OK 状态、输出文案、可选决策块与单选子 Agent、以及调用方攒好的模板 attrs。
     *
     * @param outputText    输出文案；各模板保留自己的措辞（如「固定并行完成 3 子」）
     * @param routeDecision 决策块 JSON；评审环恒为 null（不写 route_decision）
     * @param childAgentId  单意图时标注命中的子 Agent；多子或模板不适用时传 null
     * @param brief         人话摘要，写在 attrs 最前
     * @param fields        模板专属扁平字段；调用方按既有顺序构建，本类不重排
     */
    public void ok(String outputText, String routeDecision, Long childAgentId, String brief,
                   Map<String, Object> fields) {
        if (trace == null || id == null || closed.get()) {
            return;
        }
        SpanClose close = new SpanClose();
        close.setStatus(ObsStatus.OK);
        close.setOutputText(outputText);
        if (routeDecision != null) {
            close.setRouteDecision(routeDecision);
        }
        if (childAgentId != null) {
            close.setChildAgentId(childAgentId);
        }
        close.setAttrsJson(ObsAttrs.withFields(ObsAttrs.withBrief(null, brief), fields));
        finish(close);
    }

    /**
     * 失败收尾：写 ERROR 状态与脱敏 cause。若成功收尾已写过则空转（与拆分前 {@code !routeClosed} 判断一致）。
     *
     * @param brief 失败摘要（如「竞争选优失败」）
     * @param ex    原始异常；其 message 作为 errorSummary 与 outputText
     */
    public void error(String brief, RuntimeException ex) {
        if (trace == null || id == null || closed.get()) {
            return;
        }
        SpanClose close = new SpanClose();
        close.setStatus(ObsStatus.ERROR);
        close.setErrorSummary(ex.getMessage());
        close.setOutputText(ex.getMessage());
        close.setAttrsJson(ObsAttrs.withCause(ObsAttrs.withBrief(null, brief), CauseDigest.of(ex)));
        finish(close);
    }

    /**
     * 解除父步骤绑定，供模板的 {@code finally} 调用：避免线程复用时把父链带进下一次执行。
     *
     * @param trace 本轮 trace；可空
     */
    public static void unbind(ObsTraceScope trace) {
        if (trace != null) {
            trace.bindParent(null);
        }
    }

    /**
     * 真正的落库时机：先写 trace 再打标记，保证异常时标记不为「已收尾」而实际没落库。
     *
     * @param close 已填好的收尾帧
     */
    private void finish(SpanClose close) {
        trace.close(id, close);
        closed.set(true);
    }
}
