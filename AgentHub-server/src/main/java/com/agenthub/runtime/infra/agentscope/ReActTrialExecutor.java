package com.agenthub.runtime.infra.agentscope;

import com.agenthub.rag.application.RagToolContext;
import com.agenthub.runtime.domain.model.EffectiveLimits;
import com.agenthub.runtime.domain.model.MemoryFact;
import com.agenthub.runtime.domain.model.MeterScope;
import com.agenthub.runtime.domain.model.TrialPlan;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.CauseDigest;
import com.agenthub.runtime.domain.service.FactKeyCanonicalizer;
import com.agenthub.runtime.domain.service.LimitHitReasons;
import com.agenthub.runtime.domain.service.ModelCallErrors;
import com.agenthub.runtime.domain.service.RequiredToolArgs;
import com.agenthub.runtime.domain.service.RunBudget;
import com.agenthub.runtime.domain.service.RuntimeLimitResolver;
import com.agenthub.runtime.domain.service.ScratchContext;
import com.agenthub.runtime.domain.service.TextToolCallGuard;
import com.agenthub.runtime.domain.service.TrialFailure;
import com.agenthub.runtime.domain.service.TrialProgress;
import com.agenthub.runtime.infra.agentscope.builtin.MaScratchTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用 AgentScope ReActAgent 跑一轮。所属域：runtime 的 infra。
 * 推理循环在引擎内部。这里只装配模型、工具和系统提示，并按生效限制拦截。
 * 思考只来自 ThinkingBlock；模型没返回思考块时不写 reasoning。
 * Skill 正文由装配器拼进 system prompt：库里是 skill_def.content_md，不是目录型 SKILL.md，
 * 因此不走 AgentScope SkillRepository。
 * 系统提示必须已经由 SystemPromptAssembler 带上平台前缀，这里不再用另一句默认提示替换空段。
 */
@Component
public class ReActTrialExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReActTrialExecutor.class);

    private final MeteredModelOpener models;
    private final InMemoryAgentStateStore states;
    private final BuiltinToolkitAssembler tools;
    private final McpToolkitAssembler mcpTools;
    private final AssembledPromptGuard prompts;
    private final RuntimeLimitResolver limitResolver;
    private final FactKeyCanonicalizer factKeys;
    private final RequiredArgProperties requiredArgProperties;
    /** 超长工具结果落盘；与文件工具共用同一个工作区沙箱 */
    private final ToolResultOffloadStore offload;
    /** 落盘阈值与摘要长度来自 trial 配置 */
    private final TrialProperties trialProperties;

    /**
     * @param models         打开模型并按轮记账
     * @param states         进程内短期记忆
     * @param tools          按目录名注册 @Tool Bean
     * @param mcpTools       按绑定注册 MCP 可调用句柄
     * @param prompts        创建前确认平台前缀仍在最前
     * @param limitResolver  判定用限制（无 Agent 覆盖时）
     * @param factKeys       参数名/事实键归一，供必填参数闸门补参
     * @param requiredArgs   必填参数闸门开关与实体字段口径
     * @param offload        超长工具结果落盘（阈值见 {@link TrialProperties}）
     * @param trialProperties 试跑参数（句柄化阈值与摘要长度）
     */
    public ReActTrialExecutor(MeteredModelOpener models, InMemoryAgentStateStore states,
                              BuiltinToolkitAssembler tools, McpToolkitAssembler mcpTools,
                              AssembledPromptGuard prompts, RuntimeLimitResolver limitResolver,
                              FactKeyCanonicalizer factKeys, RequiredArgProperties requiredArgs,
                              ToolResultOffloadStore offload, TrialProperties trialProperties) {
        this.models = models;
        this.states = states;
        this.tools = tools;
        this.mcpTools = mcpTools;
        this.prompts = prompts;
        this.limitResolver = limitResolver;
        this.factKeys = factKeys;
        this.requiredArgProperties = requiredArgs;
        this.offload = offload;
        this.trialProperties = trialProperties;
    }

    /**
     * 组装本轮事实来源。当前用户话与会话上下文都来自已拼好的模型输入，
     * 因此「用户说过」与「会话已确认」两档天然包含在同一段文本里；长期记忆只取本轮真正注入的。
     *
     * @param plan 已装配计划
     * @param userText 本轮送进模型的用户侧文本
     * @return 供必填参数闸门使用
     */
    private RequiredToolArgs.Evidence evidenceOf(TrialPlan plan, String userText) {
        return new RequiredToolArgs.Evidence(
                userText == null ? "" : userText,
                userText == null ? "" : userText,
                plan == null ? List.of() : plan.getToolArgsFacts());
    }

    /**
     * 合并配置与内置的实体参数口径；配置为空时用内置默认（城市/地点/时区 + 居住城市）。
     *
     * @return 闸门规则
     */
    private RequiredToolArgs.Rules rules() {
        RequiredToolArgs.Rules defaults = RequiredToolArgs.Rules.defaults();
        if (requiredArgProperties.getEntityParamNames().isEmpty()
                && requiredArgProperties.getEntityFactKeys().isEmpty()) {
            return defaults;
        }
        Set<String> params = new HashSet<>(defaults.entityParamNames());
        Set<String> keys = new HashSet<>(defaults.entityFactKeys());
        for (String param : requiredArgProperties.getEntityParamNames()) {
            if (param != null && !param.isBlank()) {
                params.add(param.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
            }
        }
        for (String key : requiredArgProperties.getEntityFactKeys()) {
            if (key != null && !key.isBlank()) {
                keys.add(key.trim());
            }
        }
        return new RequiredToolArgs.Rules(params, keys);
    }

    /**
     * 执行用户输入。状态键带上 agentId，避免 Router 切换子 Agent 时串记忆。
     *
     * @param plan      已装配的 ReAct 计划
     * @param userText  用户原文，只送进引擎
     * @param userId    平台用户
     * @param sessionId 试跑会话
     * @return 助手文本、token、真实思考和工具结果
     * @throws TrialFailure 超时或引擎没有可用结果
     */
    public TrialTurn execute(TrialPlan plan, String userText, long userId, long sessionId) {
        return execute(plan, userText, userId, sessionId, null);
    }

    /**
     * 同 {@link #execute(TrialPlan, String, long, long)}，边跑边把增量交给 progress。
     *
     * @param progress 可空。空则只在结束时返回整轮
     * @return 助手文本、token、真实思考和工具结果
     * @throws TrialFailure 超时、撞限或引擎没有可用结果
     */
    public TrialTurn execute(TrialPlan plan, String userText, long userId, long sessionId, TrialProgress progress) {
        EffectiveLimits limits = plan.getLimits() != null ? plan.getLimits() : limitResolver.resolve(null);
        RunBudget budget = new RunBudget(limits);
        // 先注册工具。BUILTIN 缺 Bean 或 MCP 无法建句柄时直接失败，不必先解密模型 Key。
        Toolkit toolkit = tools.assemble(plan.getTools());
        // 共享草稿区：只有编排根选中 SHARED_SCRATCH 时上下文里才有板。
        // 板随本轮 run 生命周期，工具实例直接持有板，因此工具跑在 boundedElastic 上也不会串轮。
        ScratchContext.Scope scratch = ScratchContext.current();
        if (scratch != null) {
            toolkit = MaScratchTool.install(toolkit, scratch.board(), scratch.ownerRouteKey());
        }
        mcpTools.register(toolkit, plan.getMcpTools());
        toolkit = ToolkitBudget.wrap(toolkit, budget);
        // 必填参数闸门：被拦下的调用不执行、不占工具配额，也不产生任何出站
        toolkit = ToolkitRequiredArgs.wrap(toolkit, evidenceOf(plan, userText), rules(), factKeys::canonical,
                new RequiredArgBlocks(), requiredArgProperties.isEnabled());
        // 产物标记拦截：必须在交给引擎之前、且在配额/闸门之外，
        // 这样「哪个工具写了文件」都能被看见，标记也从模型上下文里被物理剔除。
        // 同一个 sink 既实时推给前端，也收集起来供整轮结束后落成 FILE 事件（刷新后回放要用）。
        GeneratedFileSink generated = new GeneratedFileSink(progress);
        // 超长结果在这里就落盘：出站时只看到一堆 messages，已经分不清「哪条结果来自哪个工具」，
        // 也无法把落盘路径写回时间线给用户看。工具返回那一刻落盘，信息最全。
        // 统计对象随包装一起注入：工具跑在 boundedElastic 上，ThreadLocal 取不到。
        toolkit = ToolkitFileMarker.wrap(toolkit, generated, offload,
                trialProperties.offloadChars(),
                plan.getInjection());
        // 最外层重绑 RagToolContext：ToolExecutor 在 boundedElastic 上跑工具，主线程 ThreadLocal 不可见
        toolkit = ToolkitRagContext.wrap(toolkit, plan.getAgentId());
        Model model = new LimitingModel(
                models.open(MeterScope.console(plan.getAgentId(), plan.getModelInstanceId(), sessionId, userId)),
                budget);
        Duration wall = Duration.ofMillis(Math.max(limits.timeoutMs(), 1000L));
        Duration llmTimeout = Duration.ofMillis(Math.min(Math.max(limits.llmTimeoutMs(), 1000L), wall.toMillis()));
        Duration toolTimeout = Duration.ofMillis(Math.min(Math.max(limits.toolTimeoutMs(), 1000L), wall.toMillis()));
        // maxAttempts = 首次 + 额外重试
        int toolAttempts = Math.max(1, limits.toolMaxRetries() + 1);
        ExecutionConfig modelExec = ExecutionConfig.builder().timeout(llmTimeout).maxAttempts(1).build();
        ExecutionConfig toolExec = ExecutionConfig.builder().timeout(toolTimeout).maxAttempts(toolAttempts).build();
        String prompt = prompts.require(plan.getSystemPrompt(), plan.getLayout());
        if (scratch != null) {
            // 平台前缀与锁定段已在上方校验过；共享草稿段只追加在工具目录之后，不改受保护前缀
            prompt = prompt + MaScratchTool.promptSection();
        }
        // RAG 检索工具只能从上下文取 Agent：@Tool 方法签名里没有 agentId。
        // 必须在 finally 清掉，否则线程池会把上一个 Agent 的身份带到下一次试跑（跨库串数据）。
        RagToolContext.bind(plan.getAgentId());
        try (ReActAgent agent = ReActAgent.builder()
                .name(agentName(plan))
                .sysPrompt(prompt)
                .model(model)
                .toolkit(toolkit)
                .maxIters(Math.max(limits.maxRounds(), 1))
                .stateStore(states)
                .modelExecutionConfig(modelExec)
                .toolExecutionConfig(toolExec)
                .build()) {
            // 近轮已经由 Redis 窗口拼进用户消息。这里每轮换一把状态键，避免引擎再把旧对话拼一次
            RuntimeContext context = RuntimeContext.builder()
                    .userId(Long.toString(userId))
                    .sessionId("trial-" + sessionId + "-agent-" + plan.getAgentId() + "-" + System.nanoTime())
                    .build();
            TrialEventCollector collector = new TrialEventCollector(progress, budget);
            List<AgentEvent> events = agent.streamEvents(List.of(new UserMessage(userText)), context)
                    .doOnNext(collector::accept)
                    .collectList()
                    .block(wall.plusSeconds(2));
            budget.assertNotTimedOut();
            budget.assertRoundsOk();
            if (events == null || events.isEmpty()) {
                throw new TrialFailure("模型没有返回结果");
            }
            TrialTurn turn = collector.finish();
            // 产物随本轮结果带走：调用方据此落 FILE 事件，刷新后仍能回放出下载卡片
            turn.setFiles(generated.snapshot());
            budget.assertRoundsOk();
            // 文本假 tool_call 且无真工具结果：失败收束，禁止半截当成功
            TextToolCallGuard.assertNoOrphanTextToolCall(turn);
            if (turn.getAssistantText() == null || turn.getAssistantText().isBlank()) {
                throw new TrialFailure("模型没有返回文本");
            }
            return turn;
        } catch (TrialFailure ex) {
            // 已映射的失败：只有挂了 cause 才有排障价值（视觉/工具出站被包成人话），
            // 纯业务拒绝（未绑模型、撞限文案）不再重复刷栈
            logIfCaused("ReAct 执行失败（已映射） agentId=" + plan.getAgentId(), ex);
            throw ex;
        } catch (RuntimeException ex) {
            // 带 Throwable 打全栈：上层只会看到「模型调用失败」一类人话，真实 cause 只在这里可见
            log.warn("ReAct 执行失败 agentId={} type={} root={} cause={}",
                    plan.getAgentId(), ex.getClass().getSimpleName(), rootType(ex), CauseDigest.of(ex), ex);
            if (isTimeout(ex) || System.nanoTime() >= budget.deadlineNanos()) {
                throw new TrialFailure("撞限：timeout（试跑超时）", LimitHitReasons.TIMEOUT, ex);
            }
            Throwable cause = ex;
            while (cause != null) {
                if (cause instanceof TrialFailure failure) {
                    throw failure;
                }
                cause = cause.getCause();
            }
            // 网络、鉴权等按类型转成人话；不拼异常原文，避免 URL/Key 进 error_summary；
            // 但把原始异常挂成 cause，日志与追踪才能看到真正的失败点
            throw new TrialFailure(ModelCallErrors.describe(ex), LimitHitReasons.ERROR, ex);
        } finally {
            // 必须清：线程池复用会把上一个 Agent 的 id 带到下一轮，导致 RAG 工具查错库
            RagToolContext.clear();
        }
    }

    /**
     * 父 Router 只问一次该选哪些 route_key（可多意图 JSON）。不挂工具，也不写入会话状态。
     *
     * @param scope    父 Agent、父模型和当前会话。判定本身也是一轮模型调用
     * @param prompt   候选列表，不含密钥
     * @param userText 用户原文
     * @param limits   父 Agent 生效限制；可空则用平台默认
     * @return 模型原文；超时或失败时抛 TrialFailure
     */
    public String judge(MeterScope scope, String prompt, String userText, EffectiveLimits limits) {
        return oneShot(scope, "trial-router", prompt, userText, limits, "父模型判定超时", "路由判定失败", null);
    }

    /**
     * 多子终答汇总：用父 Router 模型做一轮连贯答复。不挂工具。
     * LLM span 挂在调用方当前 bind 的父步骤下（通常是 ROUTE）。
     *
     * @param scope    父 Agent、ROUTER/CHAT 模型与会话
     * @param prompt   汇总系统提示（已含平台前缀）
     * @param userText 用户原问 + 各子终答拼接
     * @param limits   父限制
     * @return 汇总正文
     */
    public String summarize(MeterScope scope, String prompt, String userText, EffectiveLimits limits) {
        return oneShot(scope, "trial-router-summarize", prompt, userText, limits,
                "父模型汇总超时", "终答汇总失败", null);
    }

    /**
     * 多子终答汇总（流式版）：同一轮父模型调用，但正文按增量推给页面。
     *
     * <p>为什么单独开一条：汇总往往要两三秒且是整轮最后一步，用户等的是「看着字出来」；
     * 一次性推全文在 MA 三栏里表现为「最后整块闪现」。这里只改投递方式，
     * 提示词、模型、限制、计量与 LLM 步骤落库与 {@link #summarize} 完全同源。
     *
     * @param scope    父 Agent、ROUTER/CHAT 模型与会话
     * @param prompt   汇总系统提示（已含平台前缀）
     * @param userText 用户原问 + 各子终答拼接
     * @param limits   父限制
     * @param progress 增量出口；非空时正文边收边推，调用方不得再整段补推
     * @return 汇总正文 + 是否已投递（true 表示正文已经推给 progress）
     * @throws TrialFailure 超时或调用失败
     */
    public StreamedAnswer summarizeStreamed(MeterScope scope, String prompt, String userText,
                                            EffectiveLimits limits, TrialProgress progress) {
        return oneShotStreamed(scope, "trial-router-summarize", prompt, userText, limits,
                "父模型汇总超时", "终答汇总失败", progress);
    }

    /**
     * 计划执行模式的规划调用：不挂工具，单轮产出有序步骤 JSON。
     * 与 {@link #judge} 同一条无工具路径，只是名字与失败文案不同，便于链路上一眼区分。
     *
     * <p>带 progress 是为了把规划阶段的思考也交给时间线：规划本身是一次独立的模型调用，
     * 它的推理属于「计划阶段」，与后续逐步执行的推理是两回事，前端要分开折叠展示。
     *
     * @param scope    Agent、CHAT 槽模型与会话。规划本身也是一次模型调用，照常计量
     * @param prompt   规划提示（已含平台前缀与步数上限）
     * @param userText 用户原文
     * @param limits   本 Agent 生效限制
     * @param progress 可空的增量出口；用于推出规划阶段的思考
     * @return 模型原文；调用方用 {@link com.agenthub.runtime.domain.service.PlanOutputParser} 解析
     * @throws TrialFailure 超时或调用失败
     */
    public String plan(MeterScope scope, String prompt, String userText, EffectiveLimits limits,
                       TrialProgress progress) {
        return oneShot(scope, "trial-plan", prompt, userText, limits, "规划超时", "计划生成失败", progress);
    }

    /**
     * 无工具、单轮 ReAct 调用；判路由、规划与没有增量出口时的汇总共用。
     * 思考块只在引擎真的给出时经 progress 推出（规划阶段也要有可折叠思考），
     * 拿不到就不推，不编造。
     *
     * <p>要连正文一起流式推给页面时用 {@link #oneShotStreamed}，装配口径与本方法一致。
     *
     * @param progress 可空的增量出口；用于把该次单轮调用的思考交给时间线
     */
    private String oneShot(MeterScope scope, String agentName, String prompt, String userText,
                           EffectiveLimits limits, String timeoutLabel, String failLabel,
                           TrialProgress progress) {
        EffectiveLimits effective = limits != null ? limits : limitResolver.resolve(null);
        RunBudget budget = new RunBudget(effective);
        Model model = new LimitingModel(models.open(scope), budget);
        Duration timeout = Duration.ofMillis(Math.min(
                Math.max(effective.llmTimeoutMs(), 1000L),
                Math.max(effective.timeoutMs(), 1000L)));
        ExecutionConfig execution = ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build();
        try (ReActAgent agent = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(prompts.require(prompt))
                .model(model)
                .toolkit(new Toolkit())
                .maxIters(1)
                .modelExecutionConfig(execution)
                .build()) {
            Msg reply = agent.call(userText, RuntimeContext.builder().sessionId("trial-route").build())
                    .block(timeout);
            if (reply == null) {
                return "";
            }
            emitThinking(reply, progress);
            return reply.getTextContent();
        } catch (TrialFailure ex) {
            logIfCaused(failLabel + "（已映射）", ex);
            throw ex;
        } catch (RuntimeException ex) {
            // 单轮调用（判路由 / 规划 / 汇总）失败：同样带栈，否则父模型这步只会剩一句人话
            log.warn("{} type={} root={} cause={}", failLabel, ex.getClass().getSimpleName(),
                    rootType(ex), CauseDigest.of(ex), ex);
            if (isTimeout(ex)) {
                throw new TrialFailure("撞限：timeout（" + timeoutLabel + "）", LimitHitReasons.TIMEOUT, ex);
            }
            throw new TrialFailure(ModelCallErrors.describe(ex), LimitHitReasons.ERROR, ex);
        }
    }

    /**
     * 单轮流式调用：与 {@link #oneShot} 同一套装配，只把投递换成「边收边推」。
     *
     * <p>复用 {@link TrialEventCollector}：正文增量、思考增量都走它已有的口径
     * （ThinkingBlock 才算思考、正文只进助手回答），这里不再写第二套解析。
     * collector 在收尾时若发现流里没推过正文，会自己补推一次整段，
     * 因此返回的 {@code delivered} 按「progress 非空且拿到了正文」判定：
     * 调用方据此决定是否还需要自己补推。
     *
     * @param scope      Agent、模型与会话
     * @param agentName  引擎侧 Agent 名，便于日志排查
     * @param prompt     系统提示（已含平台前缀）
     * @param userText   用户侧输入
     * @param limits     生效限制；空则用平台默认
     * @param timeoutLabel 超时文案前缀
     * @param failLabel  失败日志标签
     * @param progress   增量出口；可空，空则退化成整段返回
     * @return 正文 + 是否已投递给 progress
     * @throws TrialFailure 超时或调用失败
     */
    private StreamedAnswer oneShotStreamed(MeterScope scope, String agentName, String prompt, String userText,
                                          EffectiveLimits limits, String timeoutLabel, String failLabel,
                                          TrialProgress progress) {
        EffectiveLimits effective = limits != null ? limits : limitResolver.resolve(null);
        RunBudget budget = new RunBudget(effective);
        Model model = new LimitingModel(models.open(scope), budget);
        Duration timeout = Duration.ofMillis(Math.min(
                Math.max(effective.llmTimeoutMs(), 1000L),
                Math.max(effective.timeoutMs(), 1000L)));
        ExecutionConfig execution = ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build();
        try (ReActAgent agent = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(prompts.require(prompt))
                .model(model)
                .toolkit(new Toolkit())
                .maxIters(1)
                .modelExecutionConfig(execution)
                .build()) {
            TrialEventCollector collector = new TrialEventCollector(progress, budget);
            List<AgentEvent> events = agent
                    .streamEvents(List.of(new UserMessage(userText)),
                            RuntimeContext.builder().sessionId("trial-route").build())
                    .doOnNext(collector::accept)
                    .collectList()
                    .block(timeout);
            // 超时由预算统一判定：与 execute 一致，避免只看 block 结果而放过内部已超时
            budget.assertNotTimedOut();
            if (events == null || events.isEmpty()) {
                return StreamedAnswer.empty();
            }
            // 收尾会把「流里没推过的正文 / 思考 / 工具」补齐，因此这里拿到的就是最终正文
            TrialTurn turn = collector.finish();
            String text = turn.getAssistantText() == null ? "" : turn.getAssistantText();
            return new StreamedAnswer(text, progress != null && !text.isBlank());
        } catch (TrialFailure ex) {
            logIfCaused(failLabel + "（已映射）", ex);
            throw ex;
        } catch (RuntimeException ex) {
            // 与 oneShot 同口径：带栈记录真实的出站失败点
            log.warn("{} type={} root={} cause={}", failLabel, ex.getClass().getSimpleName(),
                    rootType(ex), CauseDigest.of(ex), ex);
            if (isTimeout(ex) || System.nanoTime() >= budget.deadlineNanos()) {
                throw new TrialFailure("撞限：timeout（" + timeoutLabel + "）", LimitHitReasons.TIMEOUT, ex);
            }
            throw new TrialFailure(ModelCallErrors.describe(ex), LimitHitReasons.ERROR, ex);
        }
    }

    /**
     * 单轮流式调用的结果。
     *
     * @param text      正文；空串表示模型没给出可用内容
     * @param delivered 正文是否已经推给 progress（true 时调用方禁止再整段补推，否则页面重影）
     */
    public record StreamedAnswer(String text, boolean delivered) {

        /**
         * 流里一条事件都没有：既没有正文也没有可投递内容。
         *
         * @return 空结果
         */
        public static StreamedAnswer empty() {
            return new StreamedAnswer("", false);
        }
    }

    /**
     * 单轮调用不带增量流，思考只可能出现在最终消息的 ThinkingBlock 上。
     * 这里把它作为一段 reasoning 推出，让「规划阶段」在页面上有可折叠的思考区；
     * 模型没给思考块时什么都不推。
     *
     * @param reply    模型最终消息
     * @param progress 可空的增量出口
     */
    private void emitThinking(Msg reply, TrialProgress progress) {
        if (progress == null || reply == null) {
            return;
        }
        List<ContentBlock> blocks = reply.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        for (ContentBlock block : blocks) {
            // 只认 ThinkingBlock：普通正文即使像思考也只属于回答，不能混进思考区
            if (block instanceof ThinkingBlock thinking
                    && thinking.getThinking() != null
                    && !thinking.getThinking().isBlank()) {
                progress.phase("reasoning");
                progress.reasoning(thinking.getThinking());
            }
        }
    }

    /**
     * 只在失败带了原始 cause 时补一条带栈 WARN。
     *
     * <p>为什么要区分：{@link TrialFailure} 既可能是「撞限」「未绑模型」这类正常业务拒绝，
     * 也可能是上游真炸了才被包成人话。前者打栈只会把控制台刷满噪音，后者没有栈就查不出原因。
     *
     * @param label 日志标签，含定位 id
     * @param failure 已映射的失败
     */
    private static void logIfCaused(String label, TrialFailure failure) {
        if (failure == null || failure.getCause() == null) {
            return;
        }
        log.warn("{} msg={} cause={}", label, failure.getMessage(), CauseDigest.of(failure.getCause()), failure);
    }

    /**
     * 日志只记最内层类型名，方便对照 obs 的 SocketException 等，不打 message。
     */
    private static String rootType(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName();
    }

    private String agentName(TrialPlan plan) {
        String raw = plan.getAgentName() == null ? "" : plan.getAgentName().trim();
        String cleaned = raw.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (cleaned.isBlank() || cleaned.chars().allMatch(ch -> ch == '_')) {
            return "agent-" + plan.getAgentId();
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    /**
     * 丢掉该试跑会话在进程内的短期记忆。键前缀与执行时的 sessionId 一致。
     *
     * @param userId    平台用户
     * @param sessionId 试跑会话
     */
    public void forget(long userId, long sessionId) {
        String user = Long.toString(userId);
        String prefix = "trial-" + sessionId + "-";
        for (String key : List.copyOf(states.listSessionIds(user))) {
            if (key != null && key.startsWith(prefix)) {
                states.delete(user, key);
            }
        }
    }

    private boolean isTimeout(RuntimeException ex) {
        if (ex.getClass().getName().contains("Timeout")) {
            return true;
        }
        String message = ex.getMessage();
        return message != null && message.startsWith("Timeout on blocking read");
    }
}
