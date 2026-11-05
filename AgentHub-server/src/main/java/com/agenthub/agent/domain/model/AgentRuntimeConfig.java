package com.agenthub.agent.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行配置（JSON 落库到 agent_def.runtime_config_json）。所属域：agent。
 * 一份结构同时承载 ROUTER 的编排开关与 PLAN_AND_EXECUTE 的计划开关：
 * 按运行模式只读取自己那组字段，另一组保持默认值，互不覆盖。
 * 空库值由 {@link #defaultsFor(String)} 补齐；硬顶见 {@link #MAX_ROUTE_CHILDREN_CAP} 与
 * {@link #MAX_PLAN_STEPS_CAP}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentRuntimeConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 默认编排模板：判路由 → 多子并行 → 汇总（现网语义，回归基线）。
     */
    public static final String TEMPLATE_ROUTER_PARALLEL = "ROUTER_PARALLEL";

    /**
     * 固定串行模板：跳过判路由，按 sort_order 依次执行全部启用候选 S1→S2→…→Sn，再汇总。
     * <p>后一步可见「用户原问 + 前置步骤终答摘要」（{@link #CONTEXT_PRIOR_SUMMARY_ONLY}）。
     */
    public static final String TEMPLATE_FIXED_SEQUENCE = "FIXED_SEQUENCE";

    /**
     * 固定并行模板：跳过判路由，全部启用候选同一份用户原问并行执行，再汇总。
     * <p>超出 {@code maxRouteChildren} 的候选按序标 SKIPPED_QUOTA，不参与执行。
     */
    public static final String TEMPLATE_FIXED_PARALLEL = "FIXED_PARALLEL";

    /**
     * 评审环模板：跳过判路由，恰好 1 个执行者出稿 → 指定评审专家判定 →
     * （REVISE 时）执行者有限回修；终答＝最后一版执行者输出，不做多段拼接。
     * <p>轮次由 {@link #maCriticMaxRounds} 控制（默认 1，硬顶 3）；评审者由
     * {@link #maCriticAgentId} 指定，且必须与执行者不同。
     */
    public static final String TEMPLATE_CRITIC_LOOP = "CRITIC_LOOP";

    /**
     * 路由后串行模板：与 {@link #TEMPLATE_ROUTER_PARALLEL} 共用同一份判路由契约，
     * 但命中的子按<b>判定数组顺序</b>串行执行（不是候选 sort_order），再汇总。
     * <p>后一步可见「用户原问 + 前置步骤终答摘要」（{@link #CONTEXT_PRIOR_SUMMARY_ONLY}），
     * 摘要硬顶与 {@link #TEMPLATE_FIXED_SEQUENCE} 完全一致（复用 {@code FixedSequenceContext}）。
     * <p>与并行模板的区别只有一个：扇出方式；判路由 LLM 仍必须调用，不允许「无判路由」混淆。
     */
    public static final String TEMPLATE_ROUTER_THEN_SEQUENCE = "ROUTER_THEN_SEQUENCE";

    /**
     * 竞争选优模板：同一份用户原问并发派给多个启用候选（不做判路由切分），
     * 等齐后按 {@link #competeStrategyOrDefault()} 选优，只取 1 个子终答作为终稿。
     * <p>与 {@link #TEMPLATE_FIXED_PARALLEL} 的唯一区别：不做多段拼接，而是「选一个赢家」。
     * <p>最少参与候选数见 {@link #MIN_COMPETE_CANDIDATES}；不做多轮辩论 / 投票委员会（后续）。
     */
    public static final String TEMPLATE_COMPETE = "COMPETE";

    /** 竞争选优策略：评选模型选优（默认）。产出 winner + 短理由，失败降级 FIRST_SUCCESS */
    public static final String COMPETE_STRATEGY_JUDGE_PICK = "JUDGE_PICK";

    /** 竞争选优策略：第一个成功子的终答（按候选 sort_order，不按完成先后），不做评选调用 */
    public static final String COMPETE_STRATEGY_FIRST_SUCCESS = "FIRST_SUCCESS";

    /** 竞争选优最少参与候选数：1 个候选谈不上「竞争」，保存与运行都按此拦 */
    public static final int MIN_COMPETE_CANDIDATES = 2;

    /** 上下文策略：子 Agent 互相隔离，只吃 sub_query / 用户原问（现网多意图硬规则） */
    public static final String CONTEXT_ISOLATED = "ISOLATED";

    /**
     * 上下文策略：仅串行模板（{@link #TEMPLATE_FIXED_SEQUENCE} / {@link #TEMPLATE_ROUTER_THEN_SEQUENCE}）
     * 生效——后一步可见前置步骤的终答摘要。
     * <p>只截断拼接，不额外调用摘要模型；硬顶见 {@code FixedSequenceContext}。
     */
    public static final String CONTEXT_PRIOR_SUMMARY_ONLY = "PRIOR_SUMMARY_ONLY";

    /**
     * 上下文策略：本轮编排内提供键控可读写草稿区（{@link ScratchBoard}），子经
     * {@code ma_scratch_get} / {@code ma_scratch_put} 主动读写。
     * <p>五值模板都可选；与 {@link #CONTEXT_PRIOR_SUMMARY_ONLY} 是同一个字段的单选值，
     * 因此串行模板选了本策略就<b>不再</b>叠加前置摘要自动拼接。
     * <p>同键只允许首次写入（拒绝双写），不做覆盖；草稿仅内存、随本轮 run 结束丢弃。
     */
    public static final String CONTEXT_SHARED_SCRATCH = "SHARED_SCRATCH";

    /** 平台硬顶：每轮最多并行子 Agent 数 */
    public static final int MAX_ROUTE_CHILDREN_CAP = 5;

    /** 子运行模式：ReAct（默认，恒在允许集合里） */
    public static final String CHILD_MODE_REACT = "REACT";

    /** 子运行模式：计划执行（P&E），需编排根显式允许 */
    public static final String CHILD_MODE_PLAN = "PLAN_AND_EXECUTE";

    /** 默认每轮最多子数 */
    public static final int DEFAULT_MAX_ROUTE_CHILDREN = 3;

    /** 计划步数默认上限 */
    public static final int DEFAULT_MAX_PLAN_STEPS = 8;

    /** 计划步数平台硬顶。再多就不是「一份可读的计划」，而且串行执行必然撞墙钟 */
    public static final int MAX_PLAN_STEPS_CAP = 20;

    /** 单步失败重试平台硬顶 */
    public static final int MAX_STEP_RETRIES_CAP = 3;

    /** 评审环默认轮次：P1 只跑一轮（执行者初稿 → 评审 → 至多一次回修） */
    public static final int DEFAULT_CRITIC_MAX_ROUNDS = 1;

    /** 评审环轮次平台硬顶：再多就不是「有限回修」，容易变成死循环 */
    public static final int MAX_CRITIC_ROUNDS_CAP = 3;

    /**
     * 编排模板；六值可持久：ROUTER_PARALLEL | FIXED_SEQUENCE | FIXED_PARALLEL | CRITIC_LOOP |
     * ROUTER_THEN_SEQUENCE | COMPETE。
     * <p>非法/缺失值在归一化时回落 {@link #TEMPLATE_ROUTER_PARALLEL}，保证旧数据（无此字段）
     * 读出来仍是合法且可执行的模板。
     */
    private String maTemplate;

    /**
     * 上下文策略；只认 ISOLATED | PRIOR_SUMMARY_ONLY | SHARED_SCRATCH。
     * <p>PRIOR_SUMMARY_ONLY 只对串行模板（FIXED_SEQUENCE / ROUTER_THEN_SEQUENCE）有意义，
     * 其它模板归一化时收敛回 ISOLATED，避免「配置写着摘要继承、执行却是并行隔离」的错觉。
     * <p>SHARED_SCRATCH 对五种模板都有效（并行更常见），与摘要策略是同一字段的单选值。
     */
    private String maContextPolicy;

    /**
     * 单意图/单子时子是否继承父会话历史；现网行为是继承（兼容追问）。
     * <p>三种模板在「只有 1 个参与子」时都读这个开关：开＝继承父历史，关＝硬隔离只看用户原问。
     */
    private Boolean maAllowParentHistoryWhenSingle;

    /** 允许多意图路由；新建 ROUTER 默认 true */
    private Boolean multiIntentEnabled;

    /**
     * CONCAT / SUMMARIZE / VOTE；默认 CONCAT。
     * <p>VOTE（投票选优）只对「有末段汇总」的模板成立，见 {@link #supportsVote(String)}；
     * 其它模板归一化时回落 CONCAT，避免配置写着投票、执行却是串行直出。
     */
    private String answerMode;

    /** 每轮最多 N 个子；空则默认 3，写入时夹到 1～硬顶 */
    private Integer maxRouteChildren;

    /**
     * PLAN_AND_EXECUTE：true＝规划后停住，等试跑页「批准」再执行；false＝规划完直接执行。
     * 默认 false，即新建即为「自动执行」。
     */
    private Boolean requirePlanConfirm;

    /** PLAN_AND_EXECUTE：计划步数上限，夹到 1～{@link #MAX_PLAN_STEPS_CAP} */
    private Integer maxPlanSteps;

    /** PLAN_AND_EXECUTE：单步失败重试次数，夹到 0～{@link #MAX_STEP_RETRIES_CAP} */
    private Integer maxStepRetries;

    /**
     * 编排根允许把哪些运行模式的 Agent 挂为候选子；只认 REACT | PLAN_AND_EXECUTE。
     * <p>缺省 / 空 / 全非法时回落 `["REACT"]`，即与「子 P&E」交付前行为一致；
     * 只有显式含 {@link #CHILD_MODE_PLAN} 时才允许把 P&E 叶子挂为子并真跑。
     * <p>结果恒含 {@link #CHILD_MODE_REACT}，避免手改 JSON 把 ReAct 也关掉导致零可用子。
     */
    private List<String> maChildRunModesAllowed;

    /**
     * 评审环（{@link #TEMPLATE_CRITIC_LOOP}）的最大评审轮次；默认 1，夹到 [1, 3]。
     * <p>一轮 = 一次「评审判定 +（若 REVISE）执行者回修」；与嵌套 {@code multiAgent.critic}
     * 无关，读写只落这个扁平键。
     */
    private Integer maCriticMaxRounds;

    /**
     * 评审环的评审专家 agentId；模板为 CRITIC_LOOP 时必填。
     * <p>评审者不必出现在 {@code agent_route} 候选表，但必须是启用的叶子 Agent，
     * 且不能与执行者相同。非评审环模板也保留该值，切回评审环时可直接复用。
     */
    private Long maCriticAgentId;

    /**
     * 判路由 / 终答汇总用的模型实例；MA-P2 刀2 起执行器从 {@code ma_def.config_json} 读它。
     * <p>虚拟根（{@code root_agent_id IS NULL}）没有 Agent 模型槽，必须靠它才能判路由；
     * 绑根方案为空时回落该 ROUTER Agent 的 ROUTER / CHAT 槽，保持旧行为。
     * <p>值语义：{@code model_instance.id}；不写库内密码、不参与出站凭证解析。
     */
    private Long maJudgeModelInstanceId;

    /**
     * 判路由模型的兼容别名（配置文件 / 脚本可直接写模型名）。
     * <p>纯数字按 {@link #maJudgeModelInstanceId} 解释；其它值按启用中实例的
     * {@code model_id} 或展示名大小写不敏感匹配。两者同时存在时以 id 为准。
     */
    private String maJudgeModel;

    /**
     * 竞争选优（{@link #TEMPLATE_COMPETE}）的选优策略：JUDGE_PICK | FIRST_SUCCESS。
     * <p>缺省 / 非法回落 {@link #COMPETE_STRATEGY_JUDGE_PICK}；非竞争模板也保留该值，
     * 便于用户在模板之间来回切换时恢复上一次选择（执行侧只在模板=COMPETE 时读取）。
     */
    private String competeStrategy;

    /**
     * 按运行模式给出默认配置。三种模式都返回完整对象，便于前端统一绑定。
     *
     * @param runMode REACT / ROUTER / PLAN_AND_EXECUTE
     */
    public static AgentRuntimeConfig defaultsFor(String runMode) {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        // 模板默认「路由并行」：老 Agent 与新建 Agent 都是现网行为，固定模板需用户显式选
        config.setMaTemplate(TEMPLATE_ROUTER_PARALLEL);
        // 上下文策略默认隔离：多意图并行是现网硬规则，写进库让编排页有稳定回显
        config.setMaContextPolicy(CONTEXT_ISOLATED);
        // 单意图继承父历史是现网行为，默认开；关掉也暂不改变执行，只作为配置位
        config.setMaAllowParentHistoryWhenSingle(true);
        // ROUTER 新建默认开多意图；其它模式该字段无意义但保持同结构
        config.setMultiIntentEnabled(true);
        config.setAnswerMode(RouterAnswerMode.CONCAT.name());
        config.setMaxRouteChildren(DEFAULT_MAX_ROUTE_CHILDREN);
        // 计划默认全自动执行，避免新建 Agent 首次试跑卡在等人点批准
        config.setRequirePlanConfirm(false);
        config.setMaxPlanSteps(DEFAULT_MAX_PLAN_STEPS);
        config.setMaxStepRetries(0);
        // 默认只允许 ReAct 子：与「子 P&E」交付前的行为完全一致，P&E 必须显式勾选
        config.setMaChildRunModesAllowed(new ArrayList<>(List.of(CHILD_MODE_REACT)));
        // 评审环默认 1 轮、未选评审者：显式选评审环但没选人时由保存校验报中文 error
        config.setMaCriticMaxRounds(DEFAULT_CRITIC_MAX_ROUNDS);
        config.setMaCriticAgentId(null);
        // 竞争选优默认「评选模型选优」：不选策略时也必须是可执行且企业向的默认值
        config.setCompeteStrategy(COMPETE_STRATEGY_JUDGE_PICK);
        return config;
    }

    /**
     * 解析库内 JSON；空或非法则按模式默认。
     *
     * @param raw     runtime_config_json
     * @param runMode 当前运行模式
     * @return 已规范化的配置
     */
    public static AgentRuntimeConfig parse(String raw, String runMode) {
        AgentRuntimeConfig defaults = defaultsFor(runMode);
        if (raw == null || raw.isBlank()) {
            return defaults;
        }
        try {
            AgentRuntimeConfig parsed = JSON.readValue(raw.trim(), AgentRuntimeConfig.class);
            return normalize(parsed, defaults);
        } catch (Exception ex) {
            return defaults;
        }
    }

    /**
     * 校验并序列化；非法字段回落默认后入库。
     *
     * @param source  请求体，可空
     * @param runMode 运行模式
     * @return 可入库 JSON；始终非空对象串
     */
    public static String toStorageJson(AgentRuntimeConfig source, String runMode) {
        AgentRuntimeConfig normalized = normalize(source == null ? new AgentRuntimeConfig() : source,
                defaultsFor(runMode));
        try {
            return JSON.writeValueAsString(normalized);
        } catch (Exception ex) {
            try {
                return JSON.writeValueAsString(defaultsFor(runMode));
            } catch (Exception ignored) {
                return "{\"maTemplate\":\"ROUTER_PARALLEL\",\"maContextPolicy\":\"ISOLATED\","
                        + "\"maAllowParentHistoryWhenSingle\":true,\"multiIntentEnabled\":true,"
                        + "\"answerMode\":\"CONCAT\",\"maxRouteChildren\":3,\"requirePlanConfirm\":false,"
                        + "\"maxPlanSteps\":8,\"maxStepRetries\":0,"
                        + "\"maChildRunModesAllowed\":[\"REACT\"],"
                        + "\"maCriticMaxRounds\":1,\"maCriticAgentId\":null}";
            }
        }
    }

    /**
     * @return 是否允许多意图（空视为 true，与新建默认一致）
     */
    public boolean multiIntentOn() {
        return multiIntentEnabled == null || Boolean.TRUE.equals(multiIntentEnabled);
    }

    /**
     * @return 归一化后的编排模板；六值白名单之外一律回落 ROUTER_PARALLEL
     */
    public String maTemplateOrDefault() {
        return validTemplate(maTemplate) ? maTemplate : TEMPLATE_ROUTER_PARALLEL;
    }

    /**
     * @return 归一化后的上下文策略；白名单之外、或 PRIOR_SUMMARY_ONLY 用在非串行模板时回落 ISOLATED
     */
    public String maContextPolicyOrDefault() {
        if (CONTEXT_SHARED_SCRATCH.equals(maContextPolicy)) {
            // 共享草稿区与模板无关：并行 / 串行 / 评审环都成立
            return CONTEXT_SHARED_SCRATCH;
        }
        return CONTEXT_PRIOR_SUMMARY_ONLY.equals(maContextPolicy) && isSerialTemplate()
                ? CONTEXT_PRIOR_SUMMARY_ONLY
                : CONTEXT_ISOLATED;
    }

    /**
     * @return 本轮是否启用共享草稿区（决定是否给子注入 ma_scratch_get / ma_scratch_put）
     */
    public boolean scratchOn() {
        return CONTEXT_SHARED_SCRATCH.equals(maContextPolicyOrDefault());
    }

    /**
     * @return 是否固定串行模板（决定后一步是否吃前置摘要）
     */
    public boolean isFixedSequence() {
        return TEMPLATE_FIXED_SEQUENCE.equals(maTemplateOrDefault());
    }

    /**
     * @param template 模板码
     * @return 该模板是否为串行模板（FIXED_SEQUENCE / ROUTER_THEN_SEQUENCE），共同点是后步可吃前置摘要
     */
    public static boolean isSerialTemplateCode(String template) {
        return TEMPLATE_FIXED_SEQUENCE.equals(template) || TEMPLATE_ROUTER_THEN_SEQUENCE.equals(template);
    }

    /**
     * @return 是否串行模板（FIXED_SEQUENCE / ROUTER_THEN_SEQUENCE）
     */
    public boolean isSerialTemplate() {
        return isSerialTemplateCode(maTemplateOrDefault());
    }

    /**
     * @return 是否「先判路由、再按判定序串行」模板（有 JUDGE，但扇出是串行）
     */
    public boolean isRouterThenSequence() {
        return TEMPLATE_ROUTER_THEN_SEQUENCE.equals(maTemplateOrDefault());
    }

    /**
     * @return 是否固定并行模板（决定是否跳过判路由并全量扇出）
     */
    public boolean isFixedParallel() {
        return TEMPLATE_FIXED_PARALLEL.equals(maTemplateOrDefault());
    }

    /**
     * @return 是否评审环模板（跳过判路由，执行者 ↔ 评审者有限回修）
     */
    public boolean isCriticLoop() {
        return TEMPLATE_CRITIC_LOOP.equals(maTemplateOrDefault());
    }

    /**
     * @return 是否固定模板（FIXED_SEQUENCE / FIXED_PARALLEL），两者都跳过判路由 LLM
     */
    public boolean isFixedTemplate() {
        return isFixedSequence() || isFixedParallel();
    }

    /**
     * @return 执行期是否按「前置摘要」拼串行输入；只有串行模板 + PRIOR_SUMMARY_ONLY 才为 true
     */
    public boolean priorSummaryOn() {
        return isSerialTemplate() && CONTEXT_PRIOR_SUMMARY_ONLY.equals(maContextPolicyOrDefault());
    }

    /**
     * @return 是否竞争选优模板（同题并行 → 选优，只取 1 个子终答）
     */
    public boolean isCompete() {
        return TEMPLATE_COMPETE.equals(maTemplateOrDefault());
    }

    /**
     * @return 归一化后的选优策略；白名单外（含空）回落 {@link #COMPETE_STRATEGY_JUDGE_PICK}
     */
    public String competeStrategyOrDefault() {
        return COMPETE_STRATEGY_FIRST_SUCCESS.equals(competeStrategy)
                ? COMPETE_STRATEGY_FIRST_SUCCESS
                : COMPETE_STRATEGY_JUDGE_PICK;
    }

    /**
     * @param strategy 策略码
     * @return true 表示是竞争选优白名单内的策略
     */
    public static boolean validCompeteStrategy(String strategy) {
        return COMPETE_STRATEGY_JUDGE_PICK.equals(strategy) || COMPETE_STRATEGY_FIRST_SUCCESS.equals(strategy);
    }

    /**
     * @param template 模板码
     * @return 是否是六值白名单内的合法模板
     */
    public static boolean validTemplate(String template) {
        return TEMPLATE_ROUTER_PARALLEL.equals(template)
                || TEMPLATE_FIXED_SEQUENCE.equals(template)
                || TEMPLATE_FIXED_PARALLEL.equals(template)
                || TEMPLATE_CRITIC_LOOP.equals(template)
                || TEMPLATE_ROUTER_THEN_SEQUENCE.equals(template)
                || TEMPLATE_COMPETE.equals(template);
    }

    /**
     * 该模板是否支持「投票选优」（{@link RouterAnswerMode#VOTE}）作为终答汇总方式。
     *
     * <p>白名单只放「等齐后统一出终答」的模板：
     * <ul>
     *   <li>ROUTER_PARALLEL / FIXED_PARALLEL：多子并行，票池天然 ≥1；</li>
     *   <li>ROUTER_THEN_SEQUENCE：判路由后串行，末段仍有汇总点。</li>
     * </ul>
     * COMPETE 的选优由模板自身（PICK）负责、CRITIC_LOOP 不拼接、FIXED_SEQUENCE 串行主语义保持不变，
     * 三者都不接受 VOTE。
     *
     * @param template 已归一的模板码
     * @return true＝可配 VOTE
     */
    public static boolean supportsVote(String template) {
        return TEMPLATE_ROUTER_PARALLEL.equals(template)
                || TEMPLATE_FIXED_PARALLEL.equals(template)
                || TEMPLATE_ROUTER_THEN_SEQUENCE.equals(template);
    }

    /**
     * @return 本方案是否启用投票选优（模板合法且汇总码为 VOTE）
     */
    public boolean voteModeOn() {
        return answerModeEnum() == RouterAnswerMode.VOTE;
    }

    /**
     * @return 单意图是否继承父历史；空视为 true（与现网一致）
     */
    public boolean allowParentHistoryWhenSingleOn() {
        return maAllowParentHistoryWhenSingle == null || Boolean.TRUE.equals(maAllowParentHistoryWhenSingle);
    }

    /**
     * @return 终答模式枚举；VOTE 只在支持投票的模板上生效，其它模板按 CONCAT（与归一化同口径）
     */
    public RouterAnswerMode answerModeEnum() {
        return answerModeFor(maTemplateOrDefault(), answerMode);
    }

    /**
     * @return 夹紧后的每轮最多子数
     */
    public int maxRouteChildrenOrDefault() {
        int n = maxRouteChildren == null ? DEFAULT_MAX_ROUTE_CHILDREN : maxRouteChildren;
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_ROUTE_CHILDREN_CAP);
    }

    /**
     * @return 规划后是否需要人工批准才执行；空视为 false（自动执行）
     */
    public boolean requirePlanConfirmOn() {
        return Boolean.TRUE.equals(requirePlanConfirm);
    }

    /**
     * 归一化后的「允许的子运行模式」集合；恒含 REACT，最多两项。
     *
     * @return 只读列表，顺序固定 REACT 在前
     */
    public List<String> childRunModesAllowedOrDefault() {
        return normalizeChildRunModes(maChildRunModesAllowed);
    }

    /**
     * @param runMode 候选子的运行模式
     * @return 该模式是否被本编排根允许挂为子（ROUTER 恒不允许）
     */
    public boolean allowsChildRunMode(String runMode) {
        return runMode != null && childRunModesAllowedOrDefault().contains(runMode);
    }

    /**
     * @return 是否显式允许把 PLAN_AND_EXECUTE 叶子挂为子
     */
    public boolean allowsPlanChild() {
        return allowsChildRunMode(CHILD_MODE_PLAN);
    }

    /**
     * @param mode 运行模式
     * @return 是否是合法的「子运行模式」白名单值（ROUTER 明确非法）
     */
    public static boolean validChildRunMode(String mode) {
        return CHILD_MODE_REACT.equals(mode) || CHILD_MODE_PLAN.equals(mode);
    }

    /**
     * 清洗「允许的子运行模式」：白名单过滤、去重、补 REACT、固定顺序。
     *
     * <p>容错两种入库形态：JSON 数组（正常），以及被写成单个逗号串的历史脏值
     * （如 {@code ["REACT,PLAN_AND_EXECUTE"]}）。ROUTER 与任何第四值一律丢弃，
     * 因为「子 ROUTER」本就被刀3 的 allow_orchestrated 门闩禁止，这里再做一层兜底。
     *
     * @param raw 原始值，可空
     * @return 至少含 REACT 的只读列表
     */
    public static List<String> normalizeChildRunModes(List<String> raw) {
        List<String> out = new ArrayList<>(2);
        out.add(CHILD_MODE_REACT);
        if (raw == null || raw.isEmpty()) {
            return List.copyOf(out);
        }
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            for (String token : item.split("[,;\\s]+")) {
                String mode = token.trim().toUpperCase();
                if (CHILD_MODE_PLAN.equals(mode) && !out.contains(CHILD_MODE_PLAN)) {
                    out.add(CHILD_MODE_PLAN);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * @return 夹紧后的计划步数上限
     */
    public int maxPlanStepsOrDefault() {
        int n = maxPlanSteps == null ? DEFAULT_MAX_PLAN_STEPS : maxPlanSteps;
        if (n < 1) {
            return 1;
        }
        return Math.min(n, MAX_PLAN_STEPS_CAP);
    }

    /**
     * @return 夹紧后的单步失败重试次数
     */
    public int maxStepRetriesOrDefault() {
        int n = maxStepRetries == null ? 0 : maxStepRetries;
        if (n < 0) {
            return 0;
        }
        return Math.min(n, MAX_STEP_RETRIES_CAP);
    }

    /**
     * @return 夹紧后的评审环最大轮次；缺省/非法回落 1，范围 [1, 3]
     */
    public int criticMaxRoundsOrDefault() {
        int n = maCriticMaxRounds == null ? DEFAULT_CRITIC_MAX_ROUNDS : maCriticMaxRounds;
        if (n < 1) {
            return DEFAULT_CRITIC_MAX_ROUNDS;
        }
        return Math.min(n, MAX_CRITIC_ROUNDS_CAP);
    }

    /**
     * 终答模式归一：先按枚举解析，再按模板能力收敛。
     *
     * <p>VOTE 只保留在支持投票的模板上；其它模板回落 CONCAT。缺省仍是 CONCAT，
     * 因此旧配置读出来行为不变。
     *
     * @param template 已归一的模板码
     * @param raw      raw 配置值，可空
     * @return 可执行且与模板自洽的终答模式
     */
    private static RouterAnswerMode answerModeFor(String template, String raw) {
        RouterAnswerMode mode = RouterAnswerMode.from(raw);
        if (mode == RouterAnswerMode.VOTE && !supportsVote(template)) {
            return RouterAnswerMode.CONCAT;
        }
        return mode;
    }

    private static AgentRuntimeConfig normalize(AgentRuntimeConfig source, AgentRuntimeConfig defaults) {
        AgentRuntimeConfig out = new AgentRuntimeConfig();
        // 模板五值可持久：显式选了固定/评审/路由后串行模板就原样落库，非法/空才回落 ROUTER_PARALLEL
        String template = validTemplate(source.getMaTemplate())
                ? source.getMaTemplate()
                : TEMPLATE_ROUTER_PARALLEL;
        out.setMaTemplate(template);
        // 上下文策略：PRIOR_SUMMARY_ONLY 只对串行模板有意义；SHARED_SCRATCH 与模板无关
        String policy = source.getMaContextPolicy();
        boolean serial = isSerialTemplateCode(template);
        if (CONTEXT_PRIOR_SUMMARY_ONLY.equals(policy)) {
            out.setMaContextPolicy(serial ? CONTEXT_PRIOR_SUMMARY_ONLY : CONTEXT_ISOLATED);
        } else if (CONTEXT_SHARED_SCRATCH.equals(policy)) {
            // 用户显式选共享草稿：原样落库，任一模板都可用（串行时不再叠加前置摘要）
            out.setMaContextPolicy(CONTEXT_SHARED_SCRATCH);
        } else if (CONTEXT_ISOLATED.equals(policy)) {
            out.setMaContextPolicy(CONTEXT_ISOLATED);
        } else {
            out.setMaContextPolicy(serial ? CONTEXT_PRIOR_SUMMARY_ONLY : CONTEXT_ISOLATED);
        }
        out.setMaAllowParentHistoryWhenSingle(source.getMaAllowParentHistoryWhenSingle() == null
                ? defaults.getMaAllowParentHistoryWhenSingle()
                : source.getMaAllowParentHistoryWhenSingle());
        out.setMultiIntentEnabled(source.getMultiIntentEnabled() == null
                ? defaults.getMultiIntentEnabled()
                : source.getMultiIntentEnabled());
        out.setAnswerMode(answerModeFor(template, source.getAnswerMode() == null
                ? defaults.getAnswerMode()
                : source.getAnswerMode()).name());
        Integer max = source.getMaxRouteChildren() == null
                ? defaults.getMaxRouteChildren()
                : source.getMaxRouteChildren();
        if (max == null || max < 1) {
            max = DEFAULT_MAX_ROUTE_CHILDREN;
        }
        out.setMaxRouteChildren(Math.min(max, MAX_ROUTE_CHILDREN_CAP));
        out.setRequirePlanConfirm(source.getRequirePlanConfirm() == null
                ? defaults.getRequirePlanConfirm()
                : source.getRequirePlanConfirm());
        Integer steps = source.getMaxPlanSteps() == null ? defaults.getMaxPlanSteps() : source.getMaxPlanSteps();
        if (steps == null || steps < 1) {
            steps = DEFAULT_MAX_PLAN_STEPS;
        }
        out.setMaxPlanSteps(Math.min(steps, MAX_PLAN_STEPS_CAP));
        Integer retries = source.getMaxStepRetries() == null ? defaults.getMaxStepRetries() : source.getMaxStepRetries();
        if (retries == null || retries < 0) {
            retries = 0;
        }
        out.setMaxStepRetries(Math.min(retries, MAX_STEP_RETRIES_CAP));
        // 评审环轮次：缺省/非法统一回落 1，继续夹到 [1, 3]；评审者 id 原样保留（非评审环也存着，
        // 便于用户在模板之间来回切换时恢复上一次选择，执行侧只在模板=CRITIC_LOOP 时读取）。
        Integer criticRounds = source.getMaCriticMaxRounds() == null
                ? defaults.getMaCriticMaxRounds()
                : source.getMaCriticMaxRounds();
        if (criticRounds == null || criticRounds < 1) {
            criticRounds = DEFAULT_CRITIC_MAX_ROUNDS;
        }
        out.setMaCriticMaxRounds(Math.min(criticRounds, MAX_CRITIC_ROUNDS_CAP));
        out.setMaCriticAgentId(source.getMaCriticAgentId());
        // 判路由模型：只做原样透传，是否可用在开跑时按实例启停与凭证校验，避免保存即锁死
        out.setMaJudgeModelInstanceId(source.getMaJudgeModelInstanceId());
        out.setMaJudgeModel(source.getMaJudgeModel() == null ? null : source.getMaJudgeModel().trim());
        // 竞争选优策略：白名单外（含空）回落 JUDGE_PICK；非竞争模板也保留，便于切回时恢复选择
        out.setCompeteStrategy(validCompeteStrategy(source.getCompeteStrategy())
                ? source.getCompeteStrategy()
                : COMPETE_STRATEGY_JUDGE_PICK);
        // 子运行模式：无论请求里给没给，都按白名单清洗并补 REACT；不存在「空集合」这种落库值
        out.setMaChildRunModesAllowed(new ArrayList<>(normalizeChildRunModes(source.getMaChildRunModesAllowed())));
        return out;
    }
}
