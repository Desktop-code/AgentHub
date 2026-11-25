package com.agenthub.workflow.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作流域配置。所属域：workflow 的 infra。
 * <p>只由 {@link WorkflowInfrastructure} 的 {@code @EnableConfigurationProperties} 绑定，
 * 不标 {@code @Component}，避免同一配置出现两个 bean。
 * <p>硬顶口径（WF-P0 刀3）：
 * <ul>
 *   <li>{@code agenthub.workflow.wall-ms}：单次调度的墙钟上限（平台硬顶）；HUMAN 与「等待用户回复」
 *       都不计入，工作流自身的「整段上限 / 单节点上限」不得大于它；</li>
 *   <li>{@code agenthub.workflow.max-parallel-branches}：单个 FORK 允许的分支数，
 *       超出按「该 FORK 失败」处理（错误边可接管，否则整 run FAILED）；</li>
 *   <li>{@code agenthub.workflow.max-nodes-per-graph}：校验 / 发布时的节点硬顶；</li>
   *   <li>{@code agenthub.workflow.max-node-runs-per-run}：一次 run 最多多少条 node_run（含循环再次进入）。</li>
 *   <li>{@code agenthub.workflow.max-loop-iterations}：单个循环最多进入循环体几次，图上填写不得超过它。</li>
 * </ul>
 * <p>所有值都会被夹紧到安全区间，YAML 写错不会把系统配成「不可执行」或「无上限」。
 */
@ConfigurationProperties(prefix = "agenthub.workflow")
public class WorkflowProperties {

    /**
     * 单次调度墙钟的绝对硬顶（毫秒）：只作「写错不会变成无上限」的兜底，
     * 业务上的平台硬顶是 {@code agenthub.workflow.wall-ms} 的实际生效值。
     *
     * <p>为什么不设成 5 分钟：代码生成类工作流一次跑十几分钟是常态，
     * 旧值 300000 会把 YAML 里写的 600000 静默夹回 5 分钟，用户看到的与配置的对不上。
     */
    public static final long WALL_CAP_MS = 6 * 60 * 60 * 1_000L;

    /** 旧名兼容：刀2 的 {@code trial-wall-ms} 与墙钟同义 */
    public static final long TRIAL_WALL_CAP_MS = WALL_CAP_MS;

    /** 并行分支绝对硬顶：画布再夸张也不允许一个 FORK 扇出过多 */
    public static final int MAX_PARALLEL_BRANCHES_CAP = 8;

    /** 单图节点数绝对硬顶（含 YAML 覆盖） */
    public static final int MAX_NODES_CAP = 2_000;

    /** 单 run 的 node_run 绝对硬顶（含 YAML 覆盖） */
    public static final int MAX_NODE_RUNS_CAP = 5_000;

    /** 循环次数的绝对硬顶：图上填写和 YAML 都不能超过它 */
    public static final int MAX_LOOP_ITERATIONS_CAP = 20;

    /** 默认墙钟：10 分钟，足以跑完代码生成类工作流；HUMAN / 等待回复不计入 */
    public static final long DEFAULT_WALL_MS = 600_000L;

    /** 默认单 FORK 分支上限 */
    public static final int DEFAULT_MAX_PARALLEL_BRANCHES = 3;

    /** 默认单图节点上限（与 WorkflowGraphValidator 的历史常量一致） */
    public static final int DEFAULT_MAX_NODES_PER_GRAPH = 500;

    /** 默认单 run 的 node_run 上限 */
    public static final int DEFAULT_MAX_NODE_RUNS_PER_RUN = 500;

    /** 默认循环次数硬顶，与绝对上限相同；YAML 只能把它调低 */
    public static final int DEFAULT_MAX_LOOP_ITERATIONS = MAX_LOOP_ITERATIONS_CAP;

    /** 当前租户；一期 sys_user 无 tenant_id，先用配置收口，默认 1 */
    private long currentTenantId = 1L;

    /** 单次调度的墙钟上限（新键）；<=0 时回落到旧键 trialWallMs */
    private long wallMs = 0L;

    /** 旧键：刀2 的单次调度墙钟，保留兼容（wall-ms<=0 时才回落到这里） */
    private long trialWallMs = DEFAULT_WALL_MS;

    /** HUMAN 节点未配 timeoutMs 时的兜底等待时长（毫秒），默认 24 小时 */
    private long humanDefaultTimeoutMs = 86_400_000L;

    /** 单个 FORK 允许的分支数（NORMAL/COND/DEFAULT 出边，不含 ERROR） */
    private int maxParallelBranches = DEFAULT_MAX_PARALLEL_BRANCHES;

    /** 校验 / 发布允许的节点数 */
    private int maxNodesPerGraph = DEFAULT_MAX_NODES_PER_GRAPH;

    /** 单次 run 允许的 node_run 条数（含循环再次进入插入的行） */
    private int maxNodeRunsPerRun = DEFAULT_MAX_NODE_RUNS_PER_RUN;

    /** 单个循环进入循环体的次数硬顶；大于 {@link #MAX_LOOP_ITERATIONS_CAP} 会被夹回 */
    private int maxLoopIterations = DEFAULT_MAX_LOOP_ITERATIONS;

    /**
     * @return 当前租户
     */
    public long getCurrentTenantId() {
        return currentTenantId;
    }

    /**
     * @param currentTenantId 当前租户
     */
    public void setCurrentTenantId(long currentTenantId) {
        this.currentTenantId = currentTenantId;
    }

    /**
     * 生效墙钟：新键优先，旧键兜底；夹到 [1 秒, {@link #WALL_CAP_MS}]。
     *
     * @return 毫秒
     */
    public long effectiveWallMs() {
        long value = wallMs > 0 ? wallMs : trialWallMs;
        if (value <= 0) {
            value = DEFAULT_WALL_MS;
        }
        return Math.min(Math.max(value, 1_000L), WALL_CAP_MS);
    }

    /**
     * 旧方法名保留：刀2 的调用点与测试仍按「试跑墙钟」读值。
     *
     * @return 与 {@link #effectiveWallMs()} 同值
     */
    public long effectiveTrialWallMs() {
        return effectiveWallMs();
    }

    /**
     * 平台硬顶折算成整分钟，供配置页提示与「不得大于硬顶」的校验使用。
     *
     * <p>向下取整：硬顶 600000ms 时给出 10 分钟，界面写 10 分钟不会被自己拦住。
     *
     * @return 不小于 1 的分钟数
     */
    public int effectiveWallMinutes() {
        return (int) Math.max(1L, effectiveWallMs() / 60_000L);
    }

    /**
     * @return 生效的 FORK 分支上限，夹到 [1, {@link #MAX_PARALLEL_BRANCHES_CAP}]
     */
    public int effectiveMaxParallelBranches() {
        int value = maxParallelBranches <= 0 ? DEFAULT_MAX_PARALLEL_BRANCHES : maxParallelBranches;
        return Math.min(Math.max(value, 1), MAX_PARALLEL_BRANCHES_CAP);
    }

    /**
     * @return 生效的单图节点上限，夹到 [1, {@link #MAX_NODES_CAP}]
     */
    public int effectiveMaxNodesPerGraph() {
        int value = maxNodesPerGraph <= 0 ? DEFAULT_MAX_NODES_PER_GRAPH : maxNodesPerGraph;
        return Math.min(Math.max(value, 1), MAX_NODES_CAP);
    }

    /**
     * @return 生效的单 run node_run 上限，夹到 [1, {@link #MAX_NODE_RUNS_CAP}]
     */
    public int effectiveMaxNodeRunsPerRun() {
        int value = maxNodeRunsPerRun <= 0 ? DEFAULT_MAX_NODE_RUNS_PER_RUN : maxNodeRunsPerRun;
        return Math.min(Math.max(value, 1), MAX_NODE_RUNS_CAP);
    }

    /**
     * 循环次数的平台硬顶。图上的 maxIterations 不能超过它；运行时也取两者较小值。
     *
     * @return 夹到 [1, {@link #MAX_LOOP_ITERATIONS_CAP}] 的次数
     */
    public int effectiveMaxLoopIterations() {
        int value = maxLoopIterations <= 0 ? DEFAULT_MAX_LOOP_ITERATIONS : maxLoopIterations;
        return Math.min(Math.max(value, 1), MAX_LOOP_ITERATIONS_CAP);
    }

    public long getWallMs() {
        return wallMs;
    }

    public void setWallMs(long wallMs) {
        this.wallMs = wallMs;
    }

    public long getTrialWallMs() {
        return trialWallMs;
    }

    public void setTrialWallMs(long trialWallMs) {
        this.trialWallMs = trialWallMs;
    }

    public long getHumanDefaultTimeoutMs() {
        return humanDefaultTimeoutMs;
    }

    public void setHumanDefaultTimeoutMs(long humanDefaultTimeoutMs) {
        this.humanDefaultTimeoutMs = humanDefaultTimeoutMs;
    }

    public int getMaxParallelBranches() {
        return maxParallelBranches;
    }

    public void setMaxParallelBranches(int maxParallelBranches) {
        this.maxParallelBranches = maxParallelBranches;
    }

    public int getMaxNodesPerGraph() {
        return maxNodesPerGraph;
    }

    public void setMaxNodesPerGraph(int maxNodesPerGraph) {
        this.maxNodesPerGraph = maxNodesPerGraph;
    }

    public int getMaxNodeRunsPerRun() {
        return maxNodeRunsPerRun;
    }

    public void setMaxNodeRunsPerRun(int maxNodeRunsPerRun) {
        this.maxNodeRunsPerRun = maxNodeRunsPerRun;
    }

    /**
     * @return YAML 中的循环次数硬顶原值（未夹紧）
     */
    public int getMaxLoopIterations() {
        return maxLoopIterations;
    }

    /**
     * @param maxLoopIterations 循环次数硬顶；生效值见 {@link #effectiveMaxLoopIterations()}
     */
    public void setMaxLoopIterations(int maxLoopIterations) {
        this.maxLoopIterations = maxLoopIterations;
    }
}
