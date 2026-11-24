package com.agenthub.workflow.domain.service;

import com.agenthub.workflow.domain.model.WorkflowGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流自身的运行时限（整段 / 单节点）。所属域：workflow 的 domain，纯逻辑无状态。
 *
 * <p>口径：
 * <ul>
 *   <li>配置写在 graph_json 根上的 {@code limits.runMinutes / limits.nodeMinutes}，单位<b>分钟</b>；
 *       没配就是「跟平台走」，行为与原来一致；</li>
 *   <li>平台硬顶只有一个来源：{@code agenthub.workflow.wall-ms}（见
 *       {@link com.agenthub.workflow.infra.WorkflowProperties#effectiveWallMs()}）。
 *       两项配置都不得大于它；超出时保存 / 校验 / 发布会被明确拦下；</li>
 *   <li>单节点上限不得大于整段上限（整段没配时以平台硬顶为整段上限）。</li>
 * </ul>
 *
 * <p>文案边界：这里给用户看的提示只用「分钟」和「平台上限」，<b>不</b>出现 yml 键名或毫秒。
 *
 * <p>为什么放在 domain：运行时（引擎）与配置面（草稿 / 校验 / 发布）必须用同一套解析与校验，
 * 两处各写一份迟早漂移。
 */
public final class WorkflowRunLimits {

    /** 一分钟的毫秒数；与页面输入单位一一对应 */
    public static final long MINUTE_MS = 60_000L;

    /** 生效的整段上限（毫秒），恒大于 0 */
    private final long runLimitMs;

    /** 生效的单节点上限（毫秒）；0 表示未配置、不限单节点 */
    private final long nodeLimitMs;

    private WorkflowRunLimits(long runLimitMs, long nodeLimitMs) {
        this.runLimitMs = runLimitMs;
        this.nodeLimitMs = nodeLimitMs;
    }

    /**
     * 解析工作流配置并夹到平台硬顶内。
     *
     * <p>这里仍然做一次夹紧：保存时虽然已经拦过，但历史发布快照可能早于校验上线，
     * 运行时绝不能因为一个被手改大的值跑出平台硬顶。
     *
     * @param graph             图（含 limits）；可空
     * @param platformCeilingMs 平台硬顶毫秒；<=0 时按一分钟处理
     * @return 生效时限；整段上限恒大于 0，单节点上限 0 表示未配置
     */
    public static WorkflowRunLimits resolve(WorkflowGraph graph, long platformCeilingMs) {
        long platform = platformCeilingMs > 0 ? platformCeilingMs : MINUTE_MS;
        Integer runMinutes = graph == null ? null : graph.getRunMinutes();
        Integer nodeMinutes = graph == null ? null : graph.getNodeMinutes();
        long run = runMinutes == null || runMinutes <= 0
                ? platform
                : Math.min(runMinutes.longValue() * MINUTE_MS, platform);
        long node = nodeMinutes == null || nodeMinutes <= 0
                ? 0L
                : Math.min(nodeMinutes.longValue() * MINUTE_MS, run);
        return new WorkflowRunLimits(Math.max(run, 1_000L), node);
    }

    /**
     * 直接用已确定的两个上限构造（运行时用：run 上可能已落过值）。
     *
     * @param runLimitMs  整段上限（毫秒）；<=0 按 1 秒兜底
     * @param nodeLimitMs 单节点上限（毫秒）；<=0 表示未配置，且不会超过整段上限
     * @return 生效时限
     */
    public static WorkflowRunLimits of(long runLimitMs, long nodeLimitMs) {
        long run = Math.max(runLimitMs, 1_000L);
        long node = nodeLimitMs <= 0 ? 0L : Math.min(nodeLimitMs, run);
        return new WorkflowRunLimits(run, node);
    }

    /**
     * 配置面校验：返回所有不通过的说明（已按用户口径写成中文，不含内部键名 / 毫秒）。
     *
     * <p>调用方：草稿保存（任一问题即拒绝）、校验 / 发布（并入 errors）。
     *
     * @param graph             图（含 limits）；可空
     * @param platformCeilingMs 平台硬顶毫秒
     * @return 问题列表；空表示通过
     */
    public static List<String> issues(WorkflowGraph graph, long platformCeilingMs) {
        List<String> messages = new ArrayList<>();
        if (graph == null) {
            return messages;
        }
        long platformMinutes = Math.max(1L,
                (platformCeilingMs > 0 ? platformCeilingMs : MINUTE_MS) / MINUTE_MS);
        Integer runMinutes = graph.getRunMinutes();
        Integer nodeMinutes = graph.getNodeMinutes();
        long effectiveRunMinutes = platformMinutes;
        if (runMinutes != null) {
            if (runMinutes <= 0) {
                messages.add("整段运行上限必须是大于 0 的整数分钟");
            } else if (runMinutes > platformMinutes) {
                messages.add("整段运行上限 " + runMinutes + " 分钟超过平台上限 " + platformMinutes
                        + " 分钟，请填 " + platformMinutes + " 分钟以内");
            } else {
                effectiveRunMinutes = runMinutes;
            }
        }
        if (nodeMinutes != null) {
            if (nodeMinutes <= 0) {
                messages.add("单节点上限必须是大于 0 的整数分钟");
            } else if (nodeMinutes > effectiveRunMinutes) {
                messages.add("单节点上限 " + nodeMinutes + " 分钟超过整段运行上限 "
                        + effectiveRunMinutes + " 分钟，请填 " + effectiveRunMinutes + " 分钟以内");
            }
        }
        return messages;
    }

    /**
     * 毫秒 → 给用户看的分钟文案：整分钟写「X 分钟」，有零头写「X 分 Y 秒」。
     *
     * <p>只用于服务端日志与少量校验提示；卡片上的倒计时由前端按同一口径格式化。
     *
     * @param ms 毫秒；<=0 返回「0 秒」
     * @return 中文时长
     */
    public static String minutesText(long ms) {
        if (ms <= 0) {
            return "0 秒";
        }
        long totalSeconds = Math.max(0L, ms / 1_000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0) {
            return seconds + " 秒";
        }
        if (seconds == 0) {
            return minutes + " 分钟";
        }
        return minutes + " 分 " + seconds + " 秒";
    }

    /** @return 生效的整段上限（毫秒） */
    public long runLimitMs() {
        return runLimitMs;
    }

    /** @return 生效的单节点上限（毫秒）；0 表示未配置 */
    public long nodeLimitMs() {
        return nodeLimitMs;
    }
}
