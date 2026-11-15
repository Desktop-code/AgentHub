package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.EffectiveLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次 ReAct 执行的计数与撞限检查。所属域：runtime。
 *
 * <p><b>作用域（刀口）</b>：一个实例只服务<b>一次</b> ReAct 执行，也就是<b>一个专家</b>
 * （入口专家 / 方案里的每个子专家各自新建）。连续失败计数因此天然按专家分开：
 * 「制度知识专家」把自己搜到熔断，不该连坐后面「报告撰写专家」的读文件与写稿。
 * 为把这层语义写成可测的契约，计数还带「归属」：{@link #afterTool(String, boolean, String, Throwable)}
 * 发现归属变化时清零，杜绝以后有人复用同一个 budget 跑多个专家时把计数串起来。
 * <p>
 * 线程安全；工具与 LLM 回调可能跨线程。
 */
public final class RunBudget {

    private static final Logger log = LoggerFactory.getLogger(RunBudget.class);

    private final EffectiveLimits limits;
    private final long deadlineNanos;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger consecutiveFails = new AtomicInteger();
    /** 连续失败计数当前归属的专家（空串＝未标注）；切换即清零，熔断不跨专家累积 */
    private final AtomicReference<String> failOwner = new AtomicReference<>("");
    /**
     * 本专家的**第一次**失败原因（工具名 + 脱敏摘要）。
     *
     * <p>为什么要单独留一份：熔断文案「连续失败 3 次」只说明次数，把真实根因盖掉了。
     * 有了这一份，胶囊详情与完整链路都能回看首错。
     */
    private final AtomicReference<FirstFailure> firstFailure = new AtomicReference<>();
    private volatile boolean maxRoundsHit;

    /**
     * @param limits 生效限制
     */
    public RunBudget(EffectiveLimits limits) {
        this.limits = limits;
        this.deadlineNanos = System.nanoTime() + Math.max(limits.timeoutMs(), 1000L) * 1_000_000L;
    }

    /**
     * @return 生效限制
     */
    public EffectiveLimits limits() {
        return limits;
    }

    /**
     * @return 墙钟截止（nanoTime）
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * @return 距截止剩余毫秒，至少 1
     */
    public long remainingMs() {
        long left = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(1L, left);
    }

    /**
     * 墙钟已到则抛出。
     */
    public void assertNotTimedOut() {
        if (System.nanoTime() >= deadlineNanos) {
            throw new TrialFailure("撞限：timeout（整次执行超时）", LimitHitReasons.TIMEOUT);
        }
    }

    /**
     * LLM 调用前占用一次配额。
     */
    public void beforeLlm() {
        assertNotTimedOut();
        if (llmCalls.incrementAndGet() > limits.maxLlmCalls()) {
            throw new TrialFailure("撞限：max_llm_calls（超过 " + limits.maxLlmCalls() + " 次）",
                    LimitHitReasons.MAX_LLM_CALLS);
        }
    }

    /**
     * 工具调用前占用一次配额。
     */
    public void beforeTool() {
        assertNotTimedOut();
        if (toolCalls.incrementAndGet() > limits.maxToolCalls()) {
            throw new TrialFailure("撞限：max_tool_calls（超过 " + limits.maxToolCalls() + " 次）",
                    LimitHitReasons.MAX_TOOL_CALLS);
        }
    }

    /**
     * @param success 本轮工具是否成功
     */
    public void afterTool(boolean success) {
        afterTool(failOwner.get(), success, "", null);
    }

    /**
     * 兼容旧调用点：没有工具名与异常时只计次（首错会记成「未记录到首错」）。
     *
     * @param owner   当前归属
     * @param success 是否成功
     */
    public void afterTool(String owner, boolean success) {
        afterTool(owner, success, "", null);
    }

    /**
     * 记录一次工具失败，并把连续失败计数限定在「当前专家」这个作用域内。
     *
     * <p><b>首错留存（本刀收口）</b>：同一位专家的**第一次**失败原因会被原样留下，
     * 之后再怎么连续失败、最后被 {@code consecutive_fail_abort} 打断，都能回看这条首错。
     * 原先只有「撞限：consecutive_fail_abort（连续失败 3 次）」这一句，真实根因被它盖住了——
     * 用户看到一堆红胶囊却查不到第一次到底抛了什么。
     *
     * <p>引擎常以「失败状态 + 错误正文」结束、不抛异常；那种情况下请改用
     * {@link #afterTool(String, boolean, String, Throwable, String)} 把正文一并传进来，
     * 否则首错只剩一句「未记录到首错」。
     *
     * @param owner   当前执行归属（通常是专家 id / 展示名）；空串表示不区分
     * @param success 本轮工具是否成功
     * @param tool    工具名；可空，用于日志关键字
     * @param error   失败异常；可空（引擎只给状态、不给异常时为空）
     * @throws TrialFailure 当前专家连续失败达到阈值
     */
    public void afterTool(String owner, boolean success, String tool, Throwable error) {
        afterTool(owner, success, tool, error, null);
    }

    /**
     * 记录一次工具失败，并在没有异常对象时用工具返回的错误正文顶替首错原因。
     *
     * <p>为什么必须有这一支：{@code BudgetingAgentTool} 在
     * {@code ToolResultBlock.state == ERROR} 时拿到的是一个**失败结果块**而不是异常，
     * 原先它把 error 传成 null，首错就被写成「未记录到首错（引擎只返回失败状态，没有异常对象）」。
     * 现在把结果块里的错误正文脱敏后传进来，首错就是真正的根因。
     *
     * @param owner     当前执行归属
     * @param success   本轮工具是否成功
     * @param tool      工具名
     * @param error     失败异常；可空
     * @param errorText 工具返回的错误正文；可空（有异常时优先用异常）
     * @throws TrialFailure 当前专家连续失败达到阈值
     */
    public void afterTool(String owner, boolean success, String tool, Throwable error, String errorText) {
        String scope = owner == null ? "" : owner.trim();
        String previous = failOwner.getAndSet(scope);
        // 专家切换：上一个专家的连续失败不算到这个专家头上，首错也重新计
        if (!previous.equals(scope)) {
            consecutiveFails.set(0);
            firstFailure.set(null);
        }
        if (success) {
            consecutiveFails.set(0);
            return;
        }
        rememberFirstFailure(tool, error, errorText);
        if (consecutiveFails.incrementAndGet() >= limits.consecutiveFailAbort()) {
            throw new TrialFailure(
                    "撞限：consecutive_fail_abort（连续失败 " + limits.consecutiveFailAbort() + " 次）",
                    LimitHitReasons.CONSECUTIVE_FAIL);
        }
    }

    /**
     * 留下本专家的第一次失败：已有值就不再覆盖。
     *
     * <p>原因来源按可靠度取：异常对象优先；没有异常时用工具返回的错误正文；
     * 两者都没有才写「未记录到首错」，而不是留空让人以为没失败过。
     * 绝不猜测原因（不写 Milvus / embedding 之类未经证实的推断）。
     *
     * @param tool      工具名
     * @param error     失败异常；可空
     * @param errorText 工具返回的错误正文；可空
     */
    private void rememberFirstFailure(String tool, Throwable error, String errorText) {
        if (firstFailure.get() != null) {
            return;
        }
        String name = tool == null ? "" : tool.trim();
        String body = CauseDigest.ofText(errorText);
        String detail;
        ToolFailureKind.Kind kind;
        if (error == null && body.isEmpty()) {
            detail = "未记录到首错（引擎只返回失败状态，没有异常对象）";
            kind = ToolFailureKind.Kind.UNKNOWN;
        } else if (error == null) {
            // 引擎只给失败状态 + 错误正文：正文就是首错，按正文关键字判断可否重试
            detail = body;
            kind = ToolFailureKind.ofText(body);
        } else {
            kind = ToolFailureKind.of(error, null);
            String type = error.getClass().getSimpleName();
            String message = error.getMessage() == null ? "" : error.getMessage().trim();
            String digest = CauseDigest.of(error);
            // 详情里保留「异常类型 + 原始消息 + 摘要」，这样胶囊与完整链路都能看到根因本体
            StringBuilder text = new StringBuilder();
            text.append(type);
            if (!message.isEmpty()) {
                text.append(": ").append(message);
            }
            if (digest != null && !digest.isBlank() && !digest.startsWith(type)) {
                text.append("\n").append(digest);
            }
            // 异常没有消息、但结果块里有错误正文：补上，避免只剩一个类名
            if (message.isEmpty() && !body.isEmpty()) {
                text.append(": ").append(body);
            }
            detail = text.toString();
        }
        FirstFailure snapshot = new FirstFailure(name, detail, kind.name());
        if (!firstFailure.compareAndSet(null, snapshot)) {
            return;
        }
        /*
         * 首错必须在熔断文案之前落日志：级别 ERROR（要能被默认日志级别看到），
         * 关键字带工具名与异常类型，便于按工具检索；完整栈仍由 BudgetingAgentTool 另打一条。
         */
        log.error("工具首次失败 tool={} owner={} kind={} detail={}",
                name.isEmpty() ? "-" : name, scopeOf(), snapshot.kind(), detail, error);
    }

    /**
     * @return 本专家第一次失败的原因；没失败过为空
     */
    public FirstFailure firstFailure() {
        return firstFailure.get();
    }

    /**
     * 首错快照。
     *
     * @param tool   工具名
     * @param detail 异常类型 + 消息（+ 脱敏摘要）；没捕获到异常时为「未记录到首错」
     * @param kind   失败类型：RETRYABLE / FATAL / UNKNOWN
     */
    public record FirstFailure(String tool, String detail, String kind) {
    }

    /** @return 当前归属，仅用于日志 */
    private String scopeOf() {
        String scope = failOwner.get();
        return scope == null || scope.isEmpty() ? "-" : scope;
    }

    /**
     * 引擎发出超轮事件时标记；收尾再转成终态失败。
     */
    public void markMaxRounds() {
        maxRoundsHit = true;
    }

    /**
     * @return 是否已撞 max_rounds
     */
    public boolean isMaxRoundsHit() {
        return maxRoundsHit;
    }

    /**
     * 若已撞轮次上限则抛出。
     */
    public void assertRoundsOk() {
        if (maxRoundsHit) {
            throw new TrialFailure("撞限：max_rounds（超过 " + limits.maxRounds() + " 轮）",
                    LimitHitReasons.MAX_ROUNDS);
        }
    }
}
