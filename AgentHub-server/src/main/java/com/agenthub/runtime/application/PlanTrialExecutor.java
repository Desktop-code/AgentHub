package com.agenthub.runtime.application;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.common.text.UserFacingText;
import com.agenthub.obs.domain.ObsTraceHolder;
import com.agenthub.obs.domain.ObsTraceScope;
import com.agenthub.obs.domain.model.ObsStatus;
import com.agenthub.obs.domain.model.SpanClose;
import com.agenthub.obs.domain.model.SpanType;
import com.agenthub.obs.domain.service.ObsAttrs;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.GeneratedFile;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.PendingPlan;
import com.agenthub.runtime.domain.model.PlanState;
import com.agenthub.runtime.domain.model.PlanStep;
import com.agenthub.runtime.domain.model.PlanView;
import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.StepState;
import com.agenthub.runtime.domain.model.StepView;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTrace;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.PlanOutputParser;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.ReActTrialExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plan-and-Execute 外层编排。所属域：runtime。
 *
 * <p>职责边界：只做「规划一次 → 按 seq 串行执行 → 汇总终答」这三段编排，
 * 每一步的实际推理仍交给 {@link ReActTrialExecutor} 跑官方 ReActAgent，
 * 因此模型调用、工具注册、限制拦截、计费与埋点全部复用现网那一套，没有第二份实现。
 *
 * <p>三条硬约束（与定稿一致）：
 * <ol>
 *   <li>不做假并行：步骤严格串行，后一步能看到前面已完成步骤的结果摘要；</li>
 *   <li>出站仍走 ACL Adapter + 统一 SSE：本类只往 progress 推增量，不直接写 HTTP；</li>
 *   <li>每一步都是一次独立模型调用，计费按调用次数累计，不做合并。</li>
 * </ol>
 *
 * <p>失败语义：单步按 max_step_retries 重试；用尽仍失败则整轮失败，并把已完成步骤摘要带进错误说明。
 */
@Component
public class PlanTrialExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanTrialExecutor.class);

    /** 已完成步骤进入「前面步骤结果」时的单条摘要上限，防止长结果把后续提示词顶爆 */
    private static final int DONE_SUMMARY_CHARS = 600;

    private final TrialBindingLoader loader;
    private final ReActTrialExecutor react;
    private final PlanPromptFactory prompts;
    private final RuntimeLimitResolver limitResolver;
    private final TrialMemoryCoordinator memory;

    /**
     * @param loader        ReAct 装配（CHAT 槽、工具、技能）
     * @param react         官方 ReAct 执行；规划与每步执行都复用它
     * @param prompts       规划 / 单步 / 汇总三段提示词
     * @param limitResolver 墙钟与轮次
     * @param memory        工作记忆与用户偏好注入
     */
    public PlanTrialExecutor(TrialBindingLoader loader, ReActTrialExecutor react, PlanPromptFactory prompts,
                             RuntimeLimitResolver limitResolver, TrialMemoryCoordinator memory) {
        this.loader = loader;
        this.react = react;
        this.prompts = prompts;
        this.limitResolver = limitResolver;
        this.memory = memory;
    }

    /**
     * 规划：产出有序步骤并落一条 PLAN 事件（由调用方写入），不执行任何步骤。
     *
     * @param agent     已启用的 PLAN_AND_EXECUTE Agent
     * @param userText  用户原文
     * @param modelUser 已注入记忆的用户侧文本（规划与执行复用同一份）
     * @param userId    平台用户
     * @param sessionId 试跑会话
     * @param progress  可空；用于推 phase 与结构化计划
     * @return 计划快照（PENDING 或 EXECUTING 由调用方按配置决定）+ 后续执行所需上下文
     * @throws TrialFailure 没有 CHAT 模型、模型调用失败或计划为空
     */
    public PlannedTurn plan(AgentDef agent, String userText, String modelUser, long userId, long sessionId,
                            TrialProgress progress) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse(agent.getRuntimeConfigJson(), agent.getRunMode());
        int maxSteps = runtime.maxPlanStepsOrDefault();
        EffectiveLimits limits = limitResolver.resolve(agent);
        TrialPlan plan = loader.loadSingleAgent(agent, userText);
        // 记忆段与「本轮已注入的长期记忆」一次取回，规划与各步执行都用同一份依据
        TrialMemoryCoordinator.Augmented augmented = memory.augmentSystemWithFacts(
                memorySession(agent.getId(), userId, sessionId), plan.getSystemPrompt());
        plan.setSystemPrompt(augmented.systemPrompt());
        plan.setToolArgsFacts(augmented.injectedFacts());
        memory.publishInjection(plan);
        loader.tightenTimeout(plan, limits.timeoutMs());

        if (progress != null) {
            progress.phase("planning");
        }
        String planId = "plan-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ObsTraceScope trace = ObsTraceHolder.current();
        Long planSpan = null;
        Long previousParent = trace == null ? null : trace.implicitParent();
        if (trace != null) {
            // 挂当前链的父（调用方开的 AGENT 根），保证 PLAN 不是孤立根节点
            planSpan = trace.open(SpanType.PLAN, "规划", agent.getId(), previousParent,
                    truncate(userText, 200));
            trace.bindParent(planSpan);
        }
        try {
            // 规划用已注入摘要/近轮/短期记忆的用户侧文本，追问「那明天呢」也能接上上文。
            // 传入 progress 让规划阶段的思考也进时间线：它是独立的一次模型调用，
            // 与逐步执行的推理分开折叠，否则「出计划」阶段界面上会是空白。
            String reply = react.plan(
                    MeterScope.console(agent.getId(), plan.getModelInstanceId(), sessionId, userId),
                    prompts.planningPrompt(plan.getSystemPrompt(), maxSteps), modelUser, plan.getLimits(), progress);
            List<PlanStep> steps = PlanOutputParser.parse(reply, maxSteps);
            if (steps.isEmpty()) {
                throw new TrialFailure("计划为空：模型没有返回可执行步骤");
            }
            PlanState state = runtime.requirePlanConfirmOn() ? PlanState.PENDING : PlanState.EXECUTING;
            PlanView view = new PlanView(planId, state, steps);
            if (progress != null) {
                progress.plan(view);
            }
            if (trace != null) {
                SpanClose ok = new SpanClose();
                ok.setStatus(ObsStatus.OK);
                ok.setOutputText(steps.size() + " 步计划");
                ok.setAttrsJson(ObsAttrs.withBrief(null, "生成计划 · " + steps.size() + " 步"));
                trace.close(planSpan, ok);
            }
            return new PlannedTurn(planId, steps, state, plan, modelUser);
        } catch (RuntimeException ex) {
            if (trace != null) {
                SpanClose err = new SpanClose();
                err.setStatus(ObsStatus.ERROR);
                err.setErrorSummary(ex.getMessage());
                err.setOutputText(ex.getMessage());
                err.setAttrsJson(ObsAttrs.withBrief(null, "规划失败"));
                trace.close(planSpan, err);
            }
            throw ex;
        } finally {
            // 收尾回到进入本方法前的父，避免把后续 LLM/TOOL 挂到已关闭的 PLAN 下
            if (trace != null) {
                trace.bindParent(previousParent);
            }
        }
    }

    /**
     * 按 seq 串行执行整份计划，最后汇总成一次终答。
     *
     * @param agent      Agent 定义
     * @param pending    已批准或自动执行的计划（含复用所需的用户侧文本）
     * @param progress   可空；推 step / reasoning / tool / assistant
     * @return 终答与 token 合计、各步 traces
     * @throws TrialFailure 某步重试耗尽（错误说明里带已完成摘要）或墙钟超时
     */
    public ExecutedTurn execute(AgentDef agent, PendingPlan pending, TrialProgress progress) {
        AgentRuntimeConfig runtime = AgentRuntimeConfig.parse(agent.getRuntimeConfigJson(), agent.getRunMode());
        int maxRetries = runtime.maxStepRetriesOrDefault();
        EffectiveLimits limits = limitResolver.resolve(agent);
        long deadlineNanos = System.nanoTime() + Math.max(limits.timeoutMs(), 1000L) * 1_000_000L;

        ObsTraceScope trace = ObsTraceHolder.current();
        Long rootSpan = null;
        Long previousParent = trace == null ? null : trace.implicitParent();
        if (trace != null) {
            // 复用调用方已挂的父（AGENT 根或 PLAN）；没有父时才自己开根
            rootSpan = trace.open(SpanType.AGENT, agent.getName(), agent.getId(), previousParent, null);
            trace.bindParent(rootSpan);
        }
        TrialTurn merged = new TrialTurn();
        List<String> doneResults = new ArrayList<>();
        List<PlanStep> steps = pending.steps();
        try {
            assertDeadline(deadlineNanos);
            // 状态推进：让前端把「待批准」那张卡改成「执行中」，而不是新插一张。
            // 自动执行模式下规划阶段已推过 EXECUTING，这里再来一条是幂等的（前端按 planId 原位更新），
            // 但批准路径必须靠它把 PENDING 改成 EXECUTING，故两条路径都保留。
            pushPlan(progress, new PlanView(pending.planId(), PlanState.EXECUTING, List.of()));

            for (PlanStep step : steps) {
                assertDeadline(deadlineNanos);
                StepView running = new StepView(step.seq(), step.title(), StepState.RUNNING, "");
                if (progress != null) {
                    progress.phase("tool");
                    progress.step(running);
                }
                // 单步结果通过 STEP 事件（RUNNING → DONE/FAILED）呈现，不再把该步助手正文也推出去：
                // 否则这些片段会先于终答进助手块，时间线上就出现「终答草稿抢在计划卡/下一步之前」。
                String result = runStepWithRetry(agent, pending, step, steps, doneResults, maxRetries,
                        deadlineNanos, muteAssistant(progress), merged);
                doneResults.add(truncate(result, DONE_SUMMARY_CHARS));
                if (progress != null) {
                    progress.step(running.with(StepState.DONE, truncate(result, DONE_SUMMARY_CHARS)));
                }
            }

            assertDeadline(deadlineNanos);
            if (progress != null) {
                progress.phase("answering");
            }
            String answer = summarize(agent, pending, doneResults, deadlineNanos);
            merged.setAssistantText(answer);
            // 先把计划卡收成「已完成」，再下发终答：
            // 反过来的话，终答会在卡片仍显示「执行中」时先出现，读起来像还没跑完就给了结论。
            pushPlan(progress, new PlanView(pending.planId(), PlanState.DONE, List.of()));
            // 终答也推给前端：汇总是一次单轮调用，本身不带增量流，
            // 这里整段下发，页面就能在计划卡下方立刻出现终答，而不是等整轮落库后才刷出来。
            if (progress != null) {
                progress.assistant(answer);
            }
            if (trace != null) {
                closeSpan(trace, rootSpan, ObsStatus.OK, "计划执行完成", null);
            }
            return new ExecutedTurn(merged, pending.planId(), List.copyOf(doneResults));
        } catch (TrialFailure ex) {
            pushPlan(progress, new PlanView(pending.planId(), PlanState.FAILED, List.of()));
            if (trace != null) {
                closeSpan(trace, rootSpan, ObsStatus.ERROR, ex.getMessage(), ex.getMessage());
            }
            throw ex;
        } catch (RuntimeException ex) {
            pushPlan(progress, new PlanView(pending.planId(), PlanState.FAILED, List.of()));
            if (trace != null) {
                closeSpan(trace, rootSpan, ObsStatus.ERROR, ex.getMessage(), ex.getMessage());
            }
            throw ex;
        } finally {
            // 回到进入本方法前的父，供调用方后续步骤继续挂在正确层级
            if (trace != null) {
                trace.bindParent(previousParent);
            }
        }
    }

    /**
     * 执行单步；失败按配置重试，重试也失败则抛出带已完成摘要的失败。
     *
     * @param doneResults 已完成步骤结果，供失败说明引用
     * @return 该步结果正文
     */
    private String runStepWithRetry(AgentDef agent, PendingPlan pending, PlanStep step, List<PlanStep> steps,
                                    List<String> doneResults, int maxRetries, long deadlineNanos,
                                    TrialProgress progress, TrialTurn merged) {
        int attempts = Math.max(0, maxRetries) + 1;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            assertDeadline(deadlineNanos);
            ObsTraceScope trace = ObsTraceHolder.current();
            Long stepSpan = null;
            Long previousParent = trace == null ? null : trace.implicitParent();
            if (trace != null) {
                // 每步挂一个 AGENT，与 LLM/TOOL 构成子树；步之间是串行兄弟，禁止标成并行扇出
                stepSpan = trace.open(SpanType.AGENT, step.displayLine(), agent.getId(), previousParent,
                        truncate(step.detail().isEmpty() ? step.title() : step.detail(), 200));
                trace.bindParent(stepSpan);
            }
            try {
                // 每步重装提示词：用本轮用户原文判触发关键词，保证硬触发的 Skill 在每一步都在场
                TrialPlan plan = loader.loadSingleAgent(agent, pending.userText());
                TrialMemoryCoordinator.Augmented augmented = memory.augmentSystemWithFacts(
                        memorySession(agent.getId(), pending.userId(), pending.sessionId()), plan.getSystemPrompt());
                // 本步提示在已装配提示末尾追加「只做这一步」，平台前缀仍在最前
                plan.setSystemPrompt(prompts.stepPrompt(augmented.systemPrompt(), steps, step,
                        pending.userText(), doneResults));
                plan.setToolArgsFacts(augmented.injectedFacts());
                memory.publishInjection(plan);
                long remainMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
                loader.tightenTimeout(plan, Math.min(plan.getLimits().timeoutMs(), remainMs));
                TrialTurn turn = react.execute(plan, stepInstruction(step), pending.userId(), pending.sessionId(),
                        progress);
                collect(merged, turn);
                if (trace != null) {
                    closeSpan(trace, stepSpan, ObsStatus.OK, "该步完成", null);
                }
                return turn.getAssistantText();
            } catch (TrialFailure ex) {
                last = ex;
                if (trace != null) {
                    closeSpan(trace, stepSpan, ObsStatus.ERROR, ex.getMessage(), ex.getMessage());
                }
                // 撞墙钟没有重试价值：重试只会更快撞下一次
                if (LimitHitReasons.TIMEOUT.equals(ex.getFinishReason())) {
                    throw ex;
                }
            } catch (RuntimeException ex) {
                last = ex;
                if (trace != null) {
                    closeSpan(trace, stepSpan, ObsStatus.ERROR, ex.getMessage(), ex.getMessage());
                }
            } finally {
                // 回到该步之前的父，重试与后续步骤都挂回同一层
                if (trace != null) {
                    trace.bindParent(previousParent);
                }
            }
            if (attempt < attempts) {
                log.info("计划步骤重试 sessionId={} seq={} attempt={}", pending.sessionId(), step.seq(), attempt);
                if (progress != null) {
                    progress.step(new StepView(step.seq(), step.title(), StepState.RETRYING,
                            "第 " + attempt + " 次尝试失败，准备重试"));
                }
            }
        }
        // 失败原因会进 STEP 事件与计划失败说明（用户可见）：基础设施类故障统一换成友好短句
        String reason = UserFacingText.safeMessage(last, "该步执行失败");
        StepView failed = new StepView(step.seq(), step.title(), StepState.FAILED, reason);
        if (progress != null) {
            progress.step(failed);
        }
        throw new TrialFailure(failureText(step, reason, doneResults));
    }

    /**
     * 汇总终答：用同一 CHAT 槽模型做一轮无工具整合。失败直接抛出，不静默降级成拼接——
     * 计划执行模式下各步结果常是片段，硬拼会得到一篇没有结论的文本。
     *
     * <p>用户侧输入用规划时那份已注入摘要/近轮/短期记忆的文本，而不是裸原问：
     * 汇总阶段不能再调一次 {@code prepareUser}（会把窗口摘要推进两遍），
     * 但「那明天呢」这类追问缺上文时终答会跑题。
     */
    private String summarize(AgentDef agent, PendingPlan pending, List<String> doneResults, long deadlineNanos) {
        // 汇总同样按原问判触发，硬触发 Skill 的规则要贯彻到终答
        TrialPlan plan = loader.loadSingleAgent(agent, pending.userText());
        long remainMs = Math.max(1000L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        EffectiveLimits limits = plan.getLimits().withTimeoutMs(Math.min(plan.getLimits().timeoutMs(), remainMs));
        String answer = react.summarize(
                MeterScope.console(agent.getId(), plan.getModelInstanceId(), pending.sessionId(), pending.userId()),
                prompts.summaryPrompt(plan.getSystemPrompt(), pending.userText(), doneResults),
                prompts.summaryUser(pending.modelUser(), doneResults), limits);
        if (answer == null || answer.isBlank()) {
            throw new TrialFailure("终答汇总没有正文");
        }
        return answer.trim();
    }

    /**
     * 单步执行时送给模型的用户消息：标题 + 说明 + 用户原始请求。
     * 单独拼一份，避免模型把整段历史当成本步要处理的内容。
     */
    private String stepInstruction(PlanStep step) {
        StringBuilder text = new StringBuilder();
        text.append("请完成计划第 ").append(step.seq()).append(" 步：").append(step.title());
        if (!step.detail().isEmpty()) {
            text.append("\n补充要求：").append(step.detail());
        }
        return text.toString();
    }

    private String failureText(PlanStep step, String reason, List<String> doneResults) {
        StringBuilder text = new StringBuilder();
        text.append("第 ").append(step.seq()).append(" 步「").append(step.title()).append("」执行失败：").append(reason);
        if (!doneResults.isEmpty()) {
            text.append("；已完成 ").append(doneResults.size()).append(" 步：");
            for (int i = 0; i < doneResults.size(); i++) {
                if (i > 0) {
                    text.append(" / ");
                }
                text.append(truncate(doneResults.get(i), 80));
            }
        }
        return text.toString();
    }

    /** 合并各步 token 与 traces，供落库与时间线使用。 */
    private void collect(TrialTurn merged, TrialTurn one) {
        if (one == null) {
            return;
        }
        merged.setPromptTokens(sum(merged.getPromptTokens(), one.getPromptTokens()));
        merged.setCompletionTokens(sum(merged.getCompletionTokens(), one.getCompletionTokens()));
            merged.setToolResultMaxChars(Math.max(merged.getToolResultMaxChars(), one.getToolResultMaxChars()));
            merged.mergeFiles(one.getFiles());
            if (one.getTraces() != null) {
            for (TrialTrace trace : one.getTraces()) {
                merged.getTraces().add(trace);
            }
        }
    }

    private Integer sum(Integer left, Integer right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left + right;
    }

    private void pushPlan(TrialProgress progress, PlanView view) {
        if (progress != null && view != null) {
            progress.plan(view);
        }
    }

    /**
     * 屏蔽助手正文增量，其余（阶段 / 思考 / 工具）原样透传。
     *
     * <p>为什么单步要静音助手：每步 ReActAgent 都会产出一段「本步结果」正文，
     * 这些片段若进 SSE，前端会在计划卡之后先长出一段段助手文字，
     * 看起来就像终答草稿插在计划与步骤之间。单步结果已经由 STEP 事件承载，
     * 终答由汇总阶段单独推送，中间这些碎片一律不出站。
     *
     * @param progress 原始增量出口，可空
     * @return 静音后的出口；入参为空时返回 null（调用方按「无出口」处理）
     */
    private TrialProgress muteAssistant(TrialProgress progress) {
        if (progress == null) {
            return null;
        }
        return new TrialProgress() {
            @Override
            public void phase(String phase) {
                progress.phase(phase);
            }

            @Override
            public void route(String summary) {
                progress.route(summary);
            }

            @Override
            public void reasoning(String delta) {
                progress.reasoning(delta);
            }

            @Override
            public void tool(String name, String arguments, String result, boolean success) {
                progress.tool(name, arguments, result, success);
            }

            @Override
            public void assistant(String delta) {
                // 单步正文由 STEP 事件承载，这里刻意不转发
            }

            @Override
            public void plan(PlanView plan) {
                progress.plan(plan);
            }

            @Override
            public void step(StepView step) {
                progress.step(step);
            }

            /**
             * 产物必须透传：静音的是单步助手正文，不是文件生成。
             * 漏掉这个覆写会让计划执行模式下的下载卡片凭空消失（默认实现是空操作）。
             */
            @Override
            public void file(GeneratedFile file) {
                progress.file(file);
            }
        };
    }

    private void closeSpan(ObsTraceScope trace, Long spanId, ObsStatus status, String output, String error) {
        if (trace == null || spanId == null) {
            return;
        }
        SpanClose close = new SpanClose();
        close.setStatus(status);
        close.setOutputText(output);
        if (error != null) {
            close.setErrorSummary(error);
        }
        trace.close(spanId, close);
    }

    private void assertDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new TrialFailure("撞限：timeout（计划执行整次墙钟超时）", LimitHitReasons.TIMEOUT);
        }
    }

    /**
     * 记忆策略挂在用户选中的 Agent 上，不改用别的 id。
     */
    private RunSession memorySession(Long agentId, long userId, long sessionId) {
        RunSession session = new RunSession();
        session.setId(sessionId);
        session.setAgentId(agentId);
        session.setUserId(userId);
        return session;
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        return text.length() <= max ? text : text.substring(0, Math.max(1, max - 1)) + "…";
    }

    /**
     * 规划结果。所属域：runtime。
     *
     * @param planId   计划标识，后续状态事件沿用
     * @param steps    有序步骤
     * @param state    规划完成后立即的状态（PENDING 待批准 / EXECUTING 自动执行）
     * @param plan     已装配的 ReAct 计划，供调用方读取 notices 与 limits
     * @param modelUser 已注入记忆的用户侧文本，执行阶段复用
     */
    public record PlannedTurn(String planId, List<PlanStep> steps, PlanState state, TrialPlan plan,
                              String modelUser) {
    }

    /**
     * 执行结果。所属域：runtime。
     *
     * @param turn        合并后的终答与 token
     * @param planId      计划标识
     * @param stepResults 各步结果（已截断），用于落 STEP 事件
     */
    public record ExecutedTurn(TrialTurn turn, String planId, List<String> stepResults) {
    }
}
