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
     * 工作台入口模板：平台唯一的那个入口 Router 专用类型。
     *
     * <p>为什么单列一个码而不是复用 {@link #TEMPLATE_ROUTER_PARALLEL}：
     * <ul>
     *   <li>入口是<b>数据锁定的唯一行</b>（{@code is_reception_default = 1} 的那条 ma_def），
     *       类型上必须能和普通 Router 方案（如场景级「企业综合台」）区分开，
     *       否则列表无法只对入口锁身份字段、路由池无法只拒绝入口入池；</li>
     *   <li>执行语义与 ROUTER_PARALLEL 完全一致（判路由 → 命中子并行 → 汇总），
     *       所以执行器不需要为它新写一条分支，模板判断里按「路由并行」同等对待即可。</li>
     * </ul>
     *
     * <p><b>不可由页面选择</b>：新建 / 编辑接口一律拒绝设置该值，只能由增量 SQL 把默认接待那一行迁过来；
     * 因此它不在 {@link #validTemplate(String)} 的六值白名单里，而在
     * {@link #validStoredTemplate(String)} 的「可读入库值」集合里。
     */
    public static final String TEMPLATE_ENTRY_ROUTER = "ENTRY_ROUTER";

    /**
     * 入口行固定展示名：列表 / 详情 / 抽屉标题统一显示「Router」。
     *
     * <p>为什么不是库里的中文名：入口是全平台唯一的一条网关，展示口径必须唯一且稳定，
     * 不能随着谁改了一个名字就变成别的叫法；因此名称、编码、说明这三项身份字段一并锁定，
     * 页面只读、后端同样拒绝改（见 {@code MaDefPolicy.assertEntryIdentity}）。
     */
    public static final String ENTRY_ROUTER_NAME = "Router";

    /**
     * 入口行固定说明（备注）：写清它是什么、负责什么、哪些动作被禁止。
     *
     * <p>与 {@link #ENTRY_ROUTER_NAME} 一样属于入口身份字段：页面只读，保存时不允许被改写；
     * 长度控制在 512 以内，与 {@code ma_def.description} 列宽一致。
     */
    public static final String ENTRY_ROUTER_DESCRIPTION =
            "对话入口：负责规划、选择专家或普通方案并决定是否收尾；"
                    + "不可删除、不可改名称/类型、不可加入路由池、不可作为子专家。";

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

    /**
     * 方案嵌套分发的运行时上限（含本次即将进入的那一层）。
     * <p>入口 → 方案 → 专家只进入一层方案，远低于此上限；超过才熔断。
     */
    public static final int MAX_MA_NEST_DEPTH = 3;

    /**
     * 保存时仍提交「路由后串行 + 隔离 / 共享草稿」的拒绝文案。
     * <p>只用于保存校验。读详情和运行装载遇到历史隔离时改收口成继承，不再拿这句当错误抛出。
     */
    public static final String SERIAL_CONTEXT_REJECTED =
            "路由后串行必须继承前置结果，不能改为隔离。如需 3 步以上复杂依赖或条件判断，请使用工作流。";

    /**
     * 方案套方案超过 {@link #MAX_MA_NEST_DEPTH} 层时的熔断文案。
     */
    public static final String NEST_DEPTH_REJECTED =
            "嵌套分发已超过 3 层，本轮已熔断。入口分到方案、方案再分到专家不受影响；更长的方案套方案请改用工作流。";

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
     * <p>五值模板都可选。串行模板即使选了本策略，运行时仍会把上一专家的成文结论交给下一位；
     * 草稿区只是额外的键控便签，不能代替这段交接。
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

    /** ROUTER 判路由提示词 L3 追加区硬顶：一句角色语气 + 几条要求足够，超长会挤占候选简介预算 */
    public static final int MAX_JUDGE_PROMPT_EXTRA = 2000;

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
     * <p>方案（含入口 Router）以自己为根，没有 Agent 模型槽可回落时必须显式绑定才能判路由；
     * 为空时执行器回落平台 ROUTER / CHAT 能力槽。
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
     * Router 判路由提示词引用的 ROUTER 用途模板主键（默认接待页可配）。
     * <p>与 {@link #maJudgePromptTemplateVersion} 必须成对出现：有 id 无 version 视为未钉死，
     * 执行侧按 purpose 取当前生效版。只影响 ROUTER 网关，不影响任何专家的人设模板。
     */
    private Long maJudgePromptTemplateId;

    /**
     * 与 {@link #maJudgePromptTemplateId} 配对的模板版本；版本漂移时执行侧按配置错误处理 / 回退。
     */
    private Integer maJudgePromptTemplateVersion;

    /**
     * Router 判路由提示词的 L3 追加区（append-only）。
     * <p>用于追加角色语气 / 额外要求；锁定段与核心契约仍由已发布 ROUTER 模板提供，
     * 禁止把业务提示词写回 Java。为空表示不追加。
     */
    private String maJudgePromptExtra;

    /**
     * Router 判路由提示词的 L3 soft 段覆盖 JSON（seg_key → 文案）。
     * <p>与专家配提示词同一套语义：只有 {@code mutable=1} 的段可覆盖，锁定段不可改；
     * 未钉模板时不许存在覆盖值（{@link #normalize} 会清空）。
     */
    private String maJudgePromptOverrideJson;

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
     * @return 归一化后的编排模板；白名单之外一律回落 ROUTER_PARALLEL
     * <p>入口类型 {@code ENTRY_ROUTER} 属于「库内可读值」，原样保留，不会被打回路由并行。
     */
    public String maTemplateOrDefault() {
        return validStoredTemplate(maTemplate) ? maTemplate : TEMPLATE_ROUTER_PARALLEL;
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
     * @return 串行模板是否把上一专家的成文结论交给下一位
     * <p>这是串行本身的运行时纪律，不看上下文策略开关：显式隔离或共享草稿区也不会关掉交接。
     * 并行 / 评审环恒为 false，子之间互不传结论。
     */
    public boolean priorSummaryOn() {
        return isSerialTemplate();
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
     * @return 是否是六值白名单内的合法模板（页面可选值）
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
     * 是否是工作台入口类型（{@link #TEMPLATE_ENTRY_ROUTER}）。
     *
     * <p>调用方用它做三件事：列表隐藏、路由池拒绝入池、编辑页锁定模板 / 绑定根。
     * 普通 scheme 的保存校验（{@link #validTemplate(String)}）必须继续拒绝该值。
     *
     * @param template 模板码；可空
     * @return true 表示这是平台唯一的工作台入口
     */
    public static boolean isEntryRouterTemplate(String template) {
        return TEMPLATE_ENTRY_ROUTER.equals(template);
    }

    /**
     * 把方案上的历史模板收成两种协作模式。
     *
     * <p>并行类（路由并行、固定并行、竞争选优、评审环）收成意图路由分发；
     * 串行类（固定串行、路由后串行）收成路由后串行。入口类型原样保留。
     * 候选、模型、终答模式不在这里改，只改模板码。
     * <p>Agent 自身 runtime_config 的六值模板不走这条，避免把单兵编排一起洗掉。
     *
     * @param template 库内或请求里的模板码；可空
     * @return 归一后的模板码；认不出时返回 null
     */
    public static String canonicalMaTemplate(String template) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String raw = template.trim();
        if (isEntryRouterTemplate(raw)) {
            return TEMPLATE_ENTRY_ROUTER;
        }
        if (TEMPLATE_ROUTER_THEN_SEQUENCE.equals(raw) || TEMPLATE_FIXED_SEQUENCE.equals(raw)) {
            return TEMPLATE_ROUTER_THEN_SEQUENCE;
        }
        if (TEMPLATE_ROUTER_PARALLEL.equals(raw)
                || TEMPLATE_FIXED_PARALLEL.equals(raw)
                || TEMPLATE_COMPETE.equals(raw)
                || TEMPLATE_CRITIC_LOOP.equals(raw)) {
            return TEMPLATE_ROUTER_PARALLEL;
        }
        return null;
    }

    /**
     * 列表按新协作模式筛选时，要把尚未重存的历史模板码一并算进去。
     *
     * @param requested 页面传来的模板码
     * @return 库内可能出现的模板码；请求不是两种新模式时只含原值
     */
    public static List<String> storedTemplatesForFilter(String requested) {
        if (requested == null || requested.isBlank()) {
            return List.of();
        }
        String raw = requested.trim();
        if (TEMPLATE_ROUTER_PARALLEL.equals(raw)) {
            return List.of(TEMPLATE_ROUTER_PARALLEL, TEMPLATE_FIXED_PARALLEL, TEMPLATE_COMPETE, TEMPLATE_CRITIC_LOOP);
        }
        if (TEMPLATE_ROUTER_THEN_SEQUENCE.equals(raw)) {
            return List.of(TEMPLATE_ROUTER_THEN_SEQUENCE, TEMPLATE_FIXED_SEQUENCE);
        }
        return List.of(raw);
    }

    /**
     * 是否是「可以出现在库里」的模板值：六值白名单 + 入口类型。
     *
     * <p>与 {@link #validTemplate(String)} 的分工：
     * 前者是「页面能选什么」，后者是「从库里读出来算不算合法」。
     * 归一化必须用后者，否则入口行一保存就被洗成普通路由并行。
     *
     * @param template 模板码；可空
     * @return true 表示可原样保留
     */
    public static boolean validStoredTemplate(String template) {
        return validTemplate(template) || isEntryRouterTemplate(template);
    }

    /**
     * 该模板是否「先判路由」。
     *
     * <p>刀5：只有会判路由的模板才能把另一套编排方案当作候选——固定模板 / 评审环 / 竞争选优
     * 都不判路由，挂一个「按方案再跑一层」的目标没有语义，也会让「路由目标」这个概念失真。
     *
     * @param template 模板码
     * @return true 表示 ROUTER_PARALLEL / ROUTER_THEN_SEQUENCE
     */
    public static boolean validRouterTemplate(String template) {
        return TEMPLATE_ROUTER_PARALLEL.equals(template)
                || TEMPLATE_ROUTER_THEN_SEQUENCE.equals(template);
    }

    /**
     * 该模板能否「向外派发到别的方案 / 工作流」——即作为父方案时是否具备判路由能力。
     *
     * <p>与 {@link #validRouterTemplate(String)} 的区别：入口 {@code ENTRY_ROUTER} 的执行语义就是
     * 路由并行，它<b>可以</b>把普通 Router 方案当候选（多级分流：入口 → 场景方案 → 专家），
     * 但它自己<b>不能</b>作为任何方案的目标，所以「目标侧」的过滤继续只用三值白名单。
     *
     * @param template 模板码
     * @return true 表示会判路由，可以把方案 / 工作流挂进自己的候选
     */
    public static boolean routesByJudge(String template) {
        return validRouterTemplate(template) || isEntryRouterTemplate(template);
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
        // 模板白名单：显式选了固定 / 评审 / 路由后串行模板就原样落库；
        // 入口类型 ENTRY_ROUTER 也原样保留（它只能由迁移写入），非法 / 空才回落 ROUTER_PARALLEL
        String template = validStoredTemplate(source.getMaTemplate())
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
        // ROUTER 提示词引用：id + version 成对才生效；只给一半时丢弃，避免执行侧读到「半钉死」状态
        Long promptTemplateId = source.getMaJudgePromptTemplateId();
        Integer promptTemplateVersion = source.getMaJudgePromptTemplateVersion();
        if (promptTemplateId != null && promptTemplateVersion != null && promptTemplateVersion > 0) {
            out.setMaJudgePromptTemplateId(promptTemplateId);
            out.setMaJudgePromptTemplateVersion(promptTemplateVersion);
        }
        // L3 追加区：只追加不覆盖，空串归一成 null 便于前端判空；硬顶 2000 字防呆
        String promptExtra = source.getMaJudgePromptExtra();
        if (promptExtra != null) {
            String trimmed = promptExtra.trim();
            if (trimmed.length() > MAX_JUDGE_PROMPT_EXTRA) {
                trimmed = trimmed.substring(0, MAX_JUDGE_PROMPT_EXTRA);
            }
            out.setMaJudgePromptExtra(trimmed.isEmpty() ? null : trimmed);
        }
        // soft 段覆盖只在「已钉死模板版本」时有意义：未钉模板时保留覆盖值会变成永不生效的脏数据
        if (out.getMaJudgePromptTemplateId() != null) {
            String overrideJson = source.getMaJudgePromptOverrideJson();
            out.setMaJudgePromptOverrideJson(overrideJson == null || overrideJson.isBlank()
                    ? null : overrideJson.trim());
        }
        // 竞争选优策略：白名单外（含空）回落 JUDGE_PICK；非竞争模板也保留，便于切回时恢复选择
        out.setCompeteStrategy(validCompeteStrategy(source.getCompeteStrategy())
                ? source.getCompeteStrategy()
                : COMPETE_STRATEGY_JUDGE_PICK);
        // 子运行模式：无论请求里给没给，都按白名单清洗并补 REACT；不存在「空集合」这种落库值
        out.setMaChildRunModesAllowed(new ArrayList<>(normalizeChildRunModes(source.getMaChildRunModesAllowed())));
        return out;
    }
}
