package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.RunBudget;
import com.agenthub.runtime.domain.service.ToolFailureKind;
import com.agenthub.runtime.domain.service.TrialFailure;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 套在已注册工具外的配额包装。所属域：runtime。
 * 计次、连续失败中止；结果正文不截断（截断只发生在进模型消息路径）。
 * <p>
 * 熔断作用域：连败计数按 {@code owner}（本轮专家）归属，专家切换即清零。
 * 一把 budget 只服务一个专家，这里显式传 owner 是为了把「不跨专家连坐」写成契约。
 */
public final class BudgetingAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BudgetingAgentTool.class);

    private final AgentTool delegate;
    private final RunBudget budget;
    /** 本轮专家归属（id / 展示名），用于把连续失败计数限定在本专家内 */
    private final String owner;
    /**
     * 本工具是否已熔断。
     *
     * <p>引擎会把同一位专家的同名工具并行扇出多路；第一路撞 {@code consecutive_fail_abort}
     * 之后，其余各路必须立刻停，否则页面上会堆一片红胶囊、把真正的首错淹掉。
     */
    private final java.util.concurrent.atomic.AtomicBoolean aborted = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * @param delegate 原工具
     * @param budget   本轮配额
     */
    public BudgetingAgentTool(AgentTool delegate, RunBudget budget) {
        this(delegate, budget, "");
    }

    /**
     * @param delegate 原工具
     * @param budget   本轮配额
     * @param owner    本轮专家归属；空串表示不区分（老调用点）
     */
    public BudgetingAgentTool(AgentTool delegate, RunBudget budget, String owner) {
        this.delegate = delegate;
        this.budget = budget;
        this.owner = owner == null ? "" : owner.trim();
    }

    /** {@inheritDoc} */
    @Override
    public String getName() {
        return delegate.getName();
    }

    /** {@inheritDoc} */
    @Override
    public String getDescription() {
        return delegate.getDescription();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getParameters() {
        return delegate.getParameters();
    }

    /** {@inheritDoc} */
    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> getOutputSchema() {
        return delegate.getOutputSchema();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isReturnDirect() {
        return delegate.isReturnDirect();
    }

    /**
     * 先占配额再调原工具；失败计入连续失败，并留下首错。
     *
     * <p><b>首错留存</b>：引擎的工具失败有两种形态——
     * <ol>
     *   <li>抛异常：{@code doOnError} 分支，异常直接进首错；</li>
     *   <li>返回 {@code state=ERROR} 的结果块：没有异常对象，把结果块里的错误正文抽出来，
     *       作为首错原因交给 {@link RunBudget}。</li>
     * </ol>
     * 首错只落一次，后续熔断文案不得覆盖它。
     *
     * <p><b>成功判定（本刀收口）</b>：只有「异常」与 {@code state=ERROR} 才算失败。
     * {@code RUNNING} 等非终态、以及缺省 state 的结果块，只要正文是健康内容就按成功计——
     * 检索命中、空目录提示都不是错误。{@code state} 原值一律打进日志，保留可排查性。
     *
     * @param param 引擎入参
     * @return 原结果；撞限时以错误 Mono 结束
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        // 已经熔断过：同类后续路直接短路，不再发起调用（避免并行多路把红条堆满）
        if (aborted.get()) {
            return Mono.error(new TrialFailure(abortMessage(), LimitHitReasons.CONSECUTIVE_FAIL));
        }
        try {
            budget.beforeTool();
        } catch (TrialFailure ex) {
            aborted.set(true);
            return Mono.error(ex);
        }
        return delegate.callAsync(param)
                .doOnNext(result -> {
                    boolean success = isSuccess(result);
                    // state 原值是排查「为什么被记成失败」的唯一依据：非 SUCCESS/null 一律留痕
                    logState(result, success);
                    // 失败但没有异常：结果块正文就是唯一可得的根因，必须带走
                    record(budget, owner, success, null, success ? "" : errorTextOf(result));
                })
                .doOnError(error -> {
                    if (!(error instanceof TrialFailure)) {
                        record(budget, owner, false, error, "");
                    }
                    // 工具链里的异常常被引擎转成错误块丢掉栈：这里带 Throwable 打一次，
                    // 才能看出是参数、网络还是工具内部实现炸了
                    log.warn("工具执行异常 tool={} type={} cause={}",
                            safeName(), error.getClass().getSimpleName(), CauseDigest.of(error), error);
                });
    }

    /**
     * 结果块的 state 原值留痕：只对「非 SUCCESS 且非 null」打，正常调用不产生噪音。
     *
     * @param result  工具结果块；可空
     * @param success 本次按 {@link #isSuccess(ToolResultBlock)} 判定的结果
     */
    private void logState(ToolResultBlock result, boolean success) {
        ToolResultState state = result == null ? null : result.getState();
        if (state == null || state == ToolResultState.SUCCESS) {
            return;
        }
        log.info("工具结果 state={} tool={} owner={} hasBody={} judgedSuccess={}",
                state, safeName(), owner.isEmpty() ? "-" : owner, !errorTextOf(result).isBlank(), success);
    }

    /**
     * 记账：成功清零；失败记首错并计次。
     *
     * <p><b>不可重试失败立即停</b>：参数非法 / 资源不存在 / 权限拒绝这类错误再试也不会变好，
     * 只是把页面堆满红胶囊。这里把它标记为已熔断，让同一专家的后续路直接短路，
     * 同时不放大 {@code consecutive_fail_abort} 阈值本身（阈值仍是 3）。
     * 判断依据：有异常看异常，没异常就看引擎给的错误正文（{@code errorText}）。
     *
     * @param budget    本轮配额
     * @param owner     专家归属
     * @param success   是否成功
     * @param error     失败异常；可空
     * @param errorText 工具返回的错误正文；可空
     */
    private void record(RunBudget budget, String owner, boolean success, Throwable error, String errorText) {
        try {
            budget.afterTool(owner, success, safeName(), error, errorText);
        } catch (TrialFailure limit) {
            // 撞限：后续同名路不再发起
            aborted.set(true);
            throw limit;
        }
        if (success) {
            return;
        }
        // 不可重试：后续路一并短路（可重试的交给引擎 / 上层按既有配额再试）
        ToolFailureKind.Kind kind = error != null
                ? ToolFailureKind.of(error, null)
                : ToolFailureKind.ofText(errorText);
        if (kind != ToolFailureKind.Kind.RETRYABLE && (error != null || !(errorText == null || errorText.isBlank()))) {
            aborted.set(true);
        }
    }

    /**
     * 熔断短路时的错误文案。
     *
     * <p>不再只写「consecutive_fail_abort（连续失败 N 次）」：用户看到的第一条失败
     * 和这条短路文案是同一条胶囊，必须让人一眼知道**为什么停**、**第一次错在哪**。
     * 阈值与 finish_reason 都没变，只把首错带上。
     *
     * @return 可读短路说明
     */
    private String abortMessage() {
        RunBudget.FirstFailure first = budget.firstFailure();
        if (first == null || first.detail().isBlank()) {
            return "撞限：consecutive_fail_abort（连续失败 " + budget.limits().consecutiveFailAbort() + " 次）";
        }
        return "已停止继续调用 " + safeName() + "：首次失败原因（" + first.kind() + "）：" + first.detail();
    }

    /**
     * 从失败结果块里抽出错误正文。
     *
     * <p>只取文本块，按到达顺序拼接；没有文本时返回空串（调用方按「未记录到首错」处理）。
     * 不做二次加工与猜测，正文本身已在工具侧清洗过。
     *
     * @param result 工具结果块；可空
     * @return 错误正文；没有时为空串
     */
    private static String errorTextOf(ToolResultBlock result) {
        if (result == null) {
            return "";
        }
        List<ContentBlock> output = result.getOutput();
        if (output == null || output.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock textBlock) {
                String value = textBlock.getText();
                if (value != null && !value.isBlank()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(value.trim());
                }
            }
        }
        return text.toString();
    }

    /**
     * @return 工具名；委托对象抛异常时退回占位，日志本身不允许再失败
     */
    private String safeName() {
        try {
            return delegate.getName();
        } catch (RuntimeException ex) {
            return "未知工具";
        }
    }

    /**
     * 工具结果是否按成功记账。
     *
     * <p>判据只认「引擎明确失败」：{@code state=ERROR}，异常由 {@code doOnError} 单独处理。
     * {@code RUNNING} / 缺省 / 其它非终态**不再**当失败——线上真实踩到的就是这类结果块：
     * {@code searchKnowledgeBase} 正常返回 15 条命中、{@code workspaceListFiles} 返回
     * 「当前会话工作区还没有任何文件」，两者都被记成连续失败，三次就把该专家的工具链短路。
     *
     * <p>其余态以结果正文为辅助：有正文（或 state 明确 SUCCESS）即成功；正文也空时按成功计，
     * 因为没有任何证据说明它失败，不能凭状态枚举猜错。
     *
     * @param result 工具结果块；可空
     * @return true 表示按成功计
     */
    private static boolean isSuccess(ToolResultBlock result) {
        if (result == null) {
            return false;
        }
        // ERROR 是引擎唯一明确的失败态；异常另由 doOnError 记账
        if (result.getState() == ToolResultState.ERROR) {
            return false;
        }
        /*
         * SUCCESS / RUNNING / 缺省都按成功计：
         * 结果正文是否为空只作日志辅助（见 logState），不拿状态枚举反推失败。
         */
        return true;
    }
}
