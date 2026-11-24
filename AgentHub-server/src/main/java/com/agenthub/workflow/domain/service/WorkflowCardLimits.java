package com.agenthub.workflow.domain.service;

/**
 * 执行卡片要展示的运行时限快照。所属域：workflow 的 domain（只读视图）。
 *
 * <p>为什么用「绝对时刻」而不是「剩余毫秒」：卡片要能刷新 / 离开再进 / 回放。
 * 只要截止时刻不变，前端每次重算出的剩余就一致，不会因为重新收到一帧就从头上再数一遍。
 *
 * <p>等待口径：{@link #waitingAt()} 非空表示当前停在等待（人工 / 用户回复），
 * 前端以这一刻代替「现在」计算剩余——等待时间因此不会被扣进任何一项时限。
 *
 * @param runLimitMs    本次运行整段上限（毫秒）；<=0 表示未知
 * @param runDeadlineAt 整段截止（epoch 毫秒）；null 表示还没有
 * @param waitingAt     进入等待的时刻（epoch 毫秒）；null 表示正在跑
 * @param nodeCode      当前运行中的节点编码；没有为 null
 * @param nodeName      当前运行中的节点展示名；没有为 null
 * @param nodeLimitMs   当前节点的单节点上限（毫秒）；null / <=0 表示该节点未配置
 * @param nodeDeadlineAt 当前节点截止（epoch 毫秒）；null 表示未配置或不是运行中
 */
public record WorkflowCardLimits(long runLimitMs, Long runDeadlineAt, Long waitingAt,
                                 String nodeCode, String nodeName, Long nodeLimitMs,
                                 Long nodeDeadlineAt) {

    /** 防御性归一：编码 / 名称 trim；空串按 null 处理，前端据此不画节点行。 */
    public WorkflowCardLimits {
        nodeCode = nodeCode == null || nodeCode.isBlank() ? null : nodeCode.trim();
        nodeName = nodeName == null || nodeName.isBlank() ? null : nodeName.trim();
        if (nodeLimitMs != null && nodeLimitMs <= 0) {
            nodeLimitMs = null;
        }
        if (runLimitMs <= 0) {
            runLimitMs = 0L;
        }
    }
}
