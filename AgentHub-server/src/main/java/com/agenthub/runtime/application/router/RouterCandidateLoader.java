package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.agent.domain.model.AgentRoute;
import com.agenthub.agent.domain.model.AgentRunMode;
import com.agenthub.agent.domain.model.AgentRuntimeConfig;
import com.agenthub.agent.domain.model.AgentStatus;
import com.agenthub.agent.domain.model.MaCandidate;
import com.agenthub.agent.domain.repository.AgentDefRepository;
import com.agenthub.agent.domain.repository.AgentRouteRepository;
import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.WorkflowRunSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 路由候选装载。所属域：runtime（方案运行编排）。
 *
 * <p>从 {@code RouterTrialExecutor} 搬出，职责只有一件事：按「旧 Agent 路由行」或
 * 「方案候选表 {@code ma_candidate}」把本轮可执行候选与「已跳过」候选分开。
 * 不参与判路由、不执行子、不写事件——执行器只拿结果。
 *
 * <p>口径保持不变：
 * <ul>
 *   <li>旧口径按仓储返回顺序（{@code sort_order}、{@code id}）遍历，装载时不再重排；</li>
 *   <li>四道过滤（路由启用 → 子启用且模式合法 → 本编排允许该子模式 → 子允许被编排）
 *       与 blocked 文案逐字不变；</li>
 *   <li>方案候选读源只认 {@code ma_candidate}，迁移副本去重与自包装过滤保持静默跳过；</li>
 *   <li>MA 目标到达嵌套上限时给熔断文案，不写成「已停用」。</li>
 * </ul>
 */
public class RouterCandidateLoader {

    private static final Logger log = LoggerFactory.getLogger(RouterCandidateLoader.class);

    /** 历史迁移脚本生成的方案编码前缀；与 {@code MaTrialSpecLoader} 同口径。 */
    static final String MIGRATED_MA_CODE_PREFIX = "ma_from_agent_";

    /** 迁移脚本给方案名追加的后缀；比较名称前去掉。 */
    static final String MIGRATED_MA_NAME_SUFFIX = "（迁移）";

    /** 旧口径：子路由行。 */
    private final AgentRouteRepository routes;

    /** 子 Agent 定义（只读）。 */
    private final AgentDefRepository agents;

    /**
     * @param routes 子路由行仓储
     * @param agents 子 Agent 定义仓储（只读）
     */
    public RouterCandidateLoader(AgentRouteRepository routes, AgentDefRepository agents) {
        this.routes = routes;
        this.agents = agents;
    }

    /**
     * 装载候选（旧口径：按 Agent 根的路由行）。
     *
     * <p>四道过滤：路由行启用 → 子未删且启用 → 子 run_mode ∈ {REACT, PLAN_AND_EXECUTE} →
     * 子 allow_orchestrated=1 → 子 run_mode ∈ 父 {@code maChildRunModesAllowed}。
     * 后两道单独收集而不是静默丢弃：上层要在路由时间线写「已跳过」原因，
     * 并在过滤后零可用时给出明确中文失败，而不是让用户对着「没有可用子」猜原因。
     *
     * <p>子 ROUTER 仍静默丢弃（结构上就不该存在；写入侧已被 AgentRoutePolicy 挡住）。
     *
     * @param parentId 编排根主键
     * @param runtime  编排根运行配置（读 maChildRunModesAllowed）
     * @param spec     方案执行来源；非空时只读方案候选表
     * @return usable=可执行候选；blocked=被开关/子模式挡下的候选
     */
    public Candidates load(Long parentId, AgentRuntimeConfig runtime, MaRunSpec spec) {
        // 方案链路走专表：候选可能是子 Agent，也可能是另一套方案（刀5）
        if (spec != null) {
            return loadSpecCandidates(spec, runtime);
        }
        List<Candidate> usable = new ArrayList<>();
        List<BlockedCandidate> blocked = new ArrayList<>();
        List<AgentRoute> routeRows = routes.listByParentId(parentId);
        for (AgentRoute route : routeRows) {
            if (route.getStatus() == null || route.getStatus() != AgentStatus.ENABLED) {
                continue;
            }
            Optional<AgentDef> found = agents.findById(route.getChildAgentId())
                    .filter(item -> item.getStatus() != null && item.getStatus() == AgentStatus.ENABLED)
                    .filter(item -> AgentRuntimeConfig.validChildRunMode(item.getRunMode()));
            if (found.isEmpty()) {
                continue;
            }
            Candidate candidate = new Candidate(route, found.get());
            String runMode = found.get().getRunMode();
            // 子运行模式必须先被本编排允许，再看被编排开关：两者都关时才给一条最贴近原因的跳过文案
            if (!runtime.allowsChildRunMode(runMode)) {
                blocked.add(new BlockedCandidate(candidate, runMode == null ? "" : runMode,
                        "本编排未允许子使用" + childModeText(runMode) + "模式"));
            } else if (!found.get().allowOrchestratedOn()) {
                blocked.add(new BlockedCandidate(candidate, runMode, "专家未允许被编排调用"));
            } else {
                usable.add(candidate);
            }
        }
        return new Candidates(usable, blocked);
    }

    /**
     * 方案候选装载（含 MA / 工作流目标）。
     *
     * <p>读源纪律：只认 {@code ma_candidate}，已按启用过滤、按 sort_no 排好序，不再重排。
     * 三类候选的门闩不同：
     * <ul>
     *   <li>AGENT：子未删且启用 → run_mode 合法 → 本编排允许该子模式 → 允许被编排（与旧口径一致）；</li>
     *   <li>MA：只要求目标方案的执行来源已装载（装载时已校验启用 / 同租户 / 非默认接待 / 只一层）。
     *       目标方案若在装载后被停用或删除，本轮按「已跳过」处理，不静默换成别的候选。</li>
     *   <li>WORKFLOW（刀5B）：只要求目标工作流的执行来源已装载（装载时已校验同租户 / 已发布 /
     *       有默认发布指针 / 说明非空）。取消发布或移出池后同样按「已跳过」处理。</li>
     * </ul>
     *
     * <p><b>同一轮不许把「自己」再包一层</b>：历史迁移数据里存在 `ma_from_agent_{agentId}`
     * 这类「旧 Router 包装出来的方案」。它若被当成某个方案的子候选，就会在时间线上再出现
     * 一次同名综合台（同一条链里两条综合台同时在跑，用户看到的就是「企业综合台出现两次」）。
     * 这里在装载时把它挡掉：目标方案与当前方案同主键、或目标只是「当前方案/其根的迁移副本」时，
     * 一律按 `blocked` 跳过并留中文原因。
     *
     * @param spec    方案执行来源（非空）
     * @param runtime 编排根运行配置（读 maChildRunModesAllowed）
     * @return usable=可执行候选；blocked=被门闩挡下的候选
     */
    private Candidates loadSpecCandidates(MaRunSpec spec, AgentRuntimeConfig runtime) {
        List<Candidate> usable = new ArrayList<>();
        List<BlockedCandidate> blocked = new ArrayList<>();
        /*
         * 同一轮不跑迁移副本：先收集「非迁移候选」的展示名。
         * 历史迁移把旧 ROUTER Agent 包装成 ma_from_agent_* 方案，它往往和真方案同名并列在候选池里；
         * 只靠 isSelfWrappedTarget（目标是不是当前方案的副本）挡不住「入口方案 → 子方案」这种层间副本。
         * 这里按名字判断：池里已有真的一份时，迁移副本静默跳过，不画「已跳过」行。
         */
        Set<String> realNames = new HashSet<>();
        for (MaCandidate item : spec.candidates()) {
            if (item.getEnabled() == null || item.getEnabled() != AgentStatus.ENABLED) {
                continue;
            }
            if (!item.isMaTarget()) {
                continue;
            }
            MaRunSpec target = spec.maTarget(item.getMaDefId()).orElse(null);
            if (target == null || target.code() == null || !target.code().startsWith(MIGRATED_MA_CODE_PREFIX)) {
                realNames.add(normalizeSchemeName(target == null ? item.getRouteKey() : target.name()));
            }
        }
        for (MaCandidate item : spec.candidates()) {
            if (item.getEnabled() == null || item.getEnabled() != AgentStatus.ENABLED) {
                continue;
            }
            AgentRoute route = routeOf(item);
            // MA 目标：装载时就定好执行来源，缺失只能跳过，不能再下探第二层
            if (item.isMaTarget()) {
                AgentDef shell = maShell(spec, item);
                Candidate candidate = new Candidate(route, shell, spec.maTarget(item.getMaDefId()).orElse(null));
                // 装载时没把超限的下一跳放进目标表；这里给出熔断文案，不要写成「已停用」
                if (MaNestedContext.depth() >= AgentRuntimeConfig.MAX_MA_NEST_DEPTH) {
                    blocked.add(new BlockedCandidate(candidate, MaCandidate.TARGET_MA,
                            AgentRuntimeConfig.NEST_DEPTH_REJECTED));
                    continue;
                }
                if (candidate.maTarget() == null) {
                    blocked.add(new BlockedCandidate(candidate, MaCandidate.TARGET_MA,
                            "目标编排方案「" + childLabel(shell) + "」已停用、已删除或不允许作为路由目标"));
                    continue;
                }
                /*
                 * 迁移副本：候选池里若同时有同名的真方案，本轮只跑真方案。
                 * 静默跳过（不进 blocked）：留一行「已跳过 企业综合台（迁移）」等于让综合台在页面上出现两次。
                 */
                String targetName = normalizeSchemeName(candidate.maTarget().name());
                String targetCode = candidate.maTarget().code() == null ? "" : candidate.maTarget().code().trim();
                if (targetCode.startsWith(MIGRATED_MA_CODE_PREFIX) && !targetName.isEmpty()
                        && realNames.contains(targetName)) {
                    log.warn("方案候选含迁移副本且已有同名真方案，已跳过 maId={} targetCode={}",
                            spec.maId(), targetCode);
                    continue;
                }
                // 自指 / 迁移副本：同一轮里不该再套一层同名方案
                if (isSelfWrappedTarget(spec, candidate.maTarget())) {
                    /*
                     * 静默跳过，不进 blocked：
                     * blocked 会在时间线上画一条「已跳过 企业综合台（迁移）」，
                     * 用户仍然会看到综合台出现两次。这里只记日志，页面保持「同一轮只有一行综合台」。
                     */
                    log.warn("方案候选指向自己的迁移副本，已跳过 maId={} targetCode={}",
                            spec.maId(), candidate.maTarget().code());
                    continue;
                }
                usable.add(candidate);
                continue;
            }
            // 工作流目标（刀5B）：只认装载成功的目标；定义被取消发布 / 移出池都按「已跳过」处理
            if (item.isWorkflowTarget()) {
                WorkflowRunSpec target = spec.workflowTarget(item.getWorkflowDefId()).orElse(null);
                AgentDef shell = workflowShell(item, target);
                Candidate candidate = new Candidate(route, shell, null, target);
                if (target == null) {
                    blocked.add(new BlockedCandidate(candidate, MaCandidate.TARGET_WORKFLOW,
                            "目标工作流「" + childLabel(shell) + "」未发布、已删除或已被移出路由池"));
                } else {
                    usable.add(candidate);
                }
                continue;
            }
            Optional<AgentDef> found = agents.findById(item.getAgentId())
                    .filter(agent -> agent.getStatus() != null && agent.getStatus() == AgentStatus.ENABLED)
                    .filter(agent -> AgentRuntimeConfig.validChildRunMode(agent.getRunMode()));
            if (found.isEmpty()) {
                continue;
            }
            Candidate candidate = new Candidate(route, found.get());
            String runMode = found.get().getRunMode();
            if (!runtime.allowsChildRunMode(runMode)) {
                blocked.add(new BlockedCandidate(candidate, runMode == null ? "" : runMode,
                        "本编排未允许子使用" + childModeText(runMode) + "模式"));
            } else if (!found.get().allowOrchestratedOn()) {
                blocked.add(new BlockedCandidate(candidate, runMode, "专家未允许被编排调用"));
            } else {
                usable.add(candidate);
            }
        }
        return new Candidates(usable, blocked);
    }

    /**
     * 按 route_key 找候选（大小写不敏感）；找不到返回 null。
     *
     * <p>手动换线与沿用线路都要按「方案里的键」定位候选，不允许调用方自己拼匹配逻辑，
     * 否则大小写 / 首尾空格的差异会让同一个键一处命中、一处落空。
     *
     * @param candidates 可用候选
     * @param routeKey   目标键；可空
     * @return 命中的候选；无匹配时为空
     */
    public static Candidate byRouteKey(List<Candidate> candidates, String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return null;
        }
        String wanted = routeKey.trim().toLowerCase();
        for (Candidate candidate : candidates) {
            String key = candidate.route().getRouteKey();
            if (key != null && key.trim().toLowerCase().equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 目标方案是否只是「当前方案自己」的包装副本。
     *
     * <p>历史迁移脚本会把每个 ROUTER 类型 Agent 迁成 `ma_from_agent_{agentId}` 方案。
     * 若手工方案里又把这份迁移副本当子候选加进池，同一条链就会出现两个同名综合台。
     * 判定只用已有事实：
     * <ul>
     *   <li>同主键：显式自指；</li>
     *   <li>目标编码是 {@code ma_from_agent_} 前缀，且它的名称与当前方案同名
     *       （迁移脚本生成的名字是「原名（迁移）」，这里按去掉后缀后比较）。</li>
     * </ul>
     *
     * @param current 当前正在执行的方案
     * @param target  候选指向的目标方案
     * @return true 表示是「自己」的包装副本，本轮应跳过
     */
    private static boolean isSelfWrappedTarget(MaRunSpec current, MaRunSpec target) {
        if (current == null || target == null) {
            return false;
        }
        if (current.maId() != null && current.maId().equals(target.maId())) {
            return true;
        }
        String code = target.code() == null ? "" : target.code().trim();
        if (!code.startsWith(MIGRATED_MA_CODE_PREFIX)) {
            return false;
        }
        return sameSchemeName(current.name(), target.name());
    }

    /**
     * 迁移方案的名称比较：迁移脚本写成「原名（迁移）」，比较时去掉这个后缀。
     *
     * @param left  当前方案名
     * @param right 迁移方案名
     * @return true 表示指向同一位方案
     */
    private static boolean sameSchemeName(String left, String right) {
        String a = normalizeSchemeName(left);
        String b = normalizeSchemeName(right);
        return !a.isEmpty() && a.equals(b);
    }

    /**
     * @param name 方案名
     * @return 去掉迁移后缀与空白后的名称；空名为空串
     */
    private static String normalizeSchemeName(String name) {
        String text = name == null ? "" : name.trim();
        if (text.endsWith(MIGRATED_MA_NAME_SUFFIX)) {
            text = text.substring(0, text.length() - MIGRATED_MA_NAME_SUFFIX.length()).trim();
        }
        return text;
    }

    /**
     * MA 候选的只读外壳：让判路由提示词、时间线标题与汇总都按 Agent 口径复用同一套文案工具。
     *
     * <p>外壳主键为空、不落库：执行分支按 {@link Candidate#isMaTarget()} 直接委托给目标方案，
     * 任何按 agentId 回查 Agent 的地方都不会走到这里。
     *
     * @param parent 当前方案（拼兜底文案用）
     * @param item   MA 候选行
     * @return 展示用外壳（名称 / 编码 / 说明 / ROUTER 模式）
     */
    private AgentDef maShell(MaRunSpec parent, MaCandidate item) {
        MaRunSpec target = parent.maTarget(item.getMaDefId()).orElse(null);
        AgentDef shell = new AgentDef();
        shell.setRunMode(AgentRunMode.ROUTER.name());
        shell.setStatus(AgentStatus.ENABLED);
        if (target != null) {
            shell.setName(target.name());
            shell.setCode(target.code());
            shell.setDescription(target.description());
        }
        if (shell.getName() == null || shell.getName().isBlank()) {
            shell.setName(item.getRouteKey() == null ? "编排方案" : item.getRouteKey().trim());
        }
        return shell;
    }

    /**
     * 工作流候选的只读外壳（刀5B）：让判路由提示词、时间线标题与汇总按同一套 Agent 口径复用。
     *
     * <p>外壳主键为空、不落库；{@code runMode} 填 ROUTER 只是为了与方案外壳保持一致的展示口径，
     * 执行分支在读取运行模式之前就按 {@link Candidate#isWorkflowTarget()} 委托出去。
     * 职责简介取 {@code workflow_def.description}（意图说明唯一真源）。
     *
     * @param item   工作流候选行
     * @param target 目标工作流快照；未装载时为空（外壳仍要能显示名称，用于「已跳过」文案）
     * @return 展示用外壳
     */
    private AgentDef workflowShell(MaCandidate item, WorkflowRunSpec target) {
        AgentDef shell = new AgentDef();
        shell.setRunMode(AgentRunMode.ROUTER.name());
        shell.setStatus(AgentStatus.ENABLED);
        if (target != null) {
            shell.setName(target.name());
            shell.setCode(target.code());
            shell.setDescription(target.description());
        }
        if (shell.getName() == null || shell.getName().isBlank()) {
            shell.setName(item.getRouteKey() == null ? "工作流" : item.getRouteKey().trim());
        }
        return shell;
    }

    /**
     * 方案候选 → 执行器内部认的路由行（单条）。
     *
     * <p>只借 {@link AgentRoute} 的展示字段（route_key / sort_order）：
     * {@code parentAgentId} 填方案 id 只是为了日志可读，执行器不会再用它查路由。
     * MA 候选的 {@code childAgentId} 恒为 null（没有子 Agent），按 route_key 定位。
     *
     * @param item 方案候选
     * @return 路由行
     */
    private static AgentRoute routeOf(MaCandidate item) {
        AgentRoute route = new AgentRoute();
        route.setId(item.getId());
        route.setParentAgentId(item.getMaId());
        route.setChildAgentId(item.getAgentId());
        route.setRouteKey(item.getRouteKey());
        route.setSortOrder(item.getSortNo());
        route.setStatus(AgentStatus.ENABLED);
        return route;
    }

    /**
     * 子 Agent 显示名：名称 → 编码 → 兜底文案。口径单一真源在 {@link RouterTexts#childLabel}.
     *
     * @param agent 子 Agent；可空
     * @return 非空展示名
     */
    private static String childLabel(AgentDef agent) {
        return RouterTexts.childLabel(agent);
    }

    /**
     * @param runMode REACT / PLAN_AND_EXECUTE
     * @return 中文模式名
     */
    public static String childModeText(String runMode) {
        return AgentRunMode.PLAN_AND_EXECUTE.name().equals(runMode) ? "计划执行" : "ReAct";
    }
}
