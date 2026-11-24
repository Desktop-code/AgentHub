package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.model.MaPauseMember;
import com.agenthub.runtime.domain.model.MaPauseSnapshot;
import com.agenthub.runtime.domain.model.ParticipantView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 串行方案允许停等的边界。所属域：runtime。
 *
 * <p>为什么要单独一层 scope 而不是在 ChildRunner 里读会话：子执行只负责「跑成员」，
 * 是否允许停等、停等要带哪些上下文（方案名、用户原文、已跳过成员、已写键）由编排层决定。
 * 根层（depth=0）与一层嵌套（depth=1）的单链串行方案建 scope；并行模板、多成员串行
 * 与更深嵌套传 null，行为与现网逐字一致。
 *
 * <p>入口块与判定推理也在这里带上：停等发生时整轮还没落库，回放若要还原「入口 → 判定 →
 * 各成员」，就必须把已经推给直播的入口身份、判定推理与决策行一起存进等待态，
 * 否则刷新后用户已经看过的入口块会消失。
 *
 * @param maDefId       方案定义 id
 * @param schemeName    方案展示名
 * @param template      当前串行模板
 * @param depth         嵌套层级（0 = 入口本身，1 = 入口分到的方案）
 * @param parentKey     当前层方案路由键（根层为空）
 * @param userText      用户原文
 * @param modelUser     送给模型的用户侧文本（含记忆 / 近轮）
 * @param skipped       超出上限未执行的成员（保留给汇总与再次停等）
 * @param seedDone      续跑时已经跑完的成员（停等时用于拼前置摘要 / 补写事件）
 * @param writtenKeys   续跑时已经写进时间线的路由键
 * @param gateway       入口参与者身份；嵌套方案为空（首轮直播已经推过）
 * @param judgeReasoning 判路由 / 分流模型的推理；首轮停等要落库
 * @param decisionBlock 多意图决策块（机器 JSON）；与普通轮落库同口径
 */
public record MaPauseScope(Long maDefId, String schemeName, String template, int depth, String parentKey,
                           String userText, String modelUser, List<MaPauseMember> skipped,
                           List<MaPauseMember> seedDone, Set<String> writtenKeys,
                           ParticipantView gateway, String judgeReasoning, String decisionBlock) {

    /** 防御性归一：null → 空容器，调用方不必逐处判空。 */
    public MaPauseScope {
        skipped = skipped == null ? new ArrayList<>() : new ArrayList<>(skipped);
        seedDone = seedDone == null ? new ArrayList<>() : new ArrayList<>(seedDone);
        writtenKeys = writtenKeys == null ? new LinkedHashSet<>() : new LinkedHashSet<>(writtenKeys);
    }

    /**
     * 首次执行时的 scope：没有已完成 / 已写键。
     *
     * @param maDefId     方案 id
     * @param schemeName  方案名
     * @param template    串行模板
     * @param depth       嵌套层级：0 = 入口本身，1 = 入口分到的方案
     * @param parentKey   当前层方案路由键；根层为空
     * @param userText    用户原文
     * @param modelUser   模型侧用户文本
     * @param skipped     超限未执行的成员
     * @param gateway     入口身份；嵌套时为空（入口块由续跑时反查补写）
     * @param judgeReasoning 入口判定推理；可空
     * @param decisionBlock 决策块机器 JSON；可空
     * @return 首次停等边界
     */
    public static MaPauseScope root(Long maDefId, String schemeName, String template, int depth, String parentKey,
                                    String userText, String modelUser, List<MaPauseMember> skipped,
                                    ParticipantView gateway, String judgeReasoning, String decisionBlock) {
        return new MaPauseScope(maDefId, schemeName, template, depth, parentKey, userText, modelUser,
                skipped, List.of(), Set.of(), gateway, judgeReasoning, decisionBlock);
    }

    /**
     * 续跑时的 scope：沿用首次停等的方案 / 用户原文，带回已完成成员与已写键。
     *
     * @param state 从库里读出的停等快照
     * @return 续跑停等边界
     */
    public static MaPauseScope resume(MaPauseSnapshot state) {
        return new MaPauseScope(state.getMaDefId(), state.getSchemeName(), state.getTemplate(),
                state.getDepth(), state.getParentKey(), state.getUserText(), state.getModelUser(),
                state.getSkipped(), state.getDone(), state.getWrittenKeys(), state.getGateway(),
                state.getJudgeReasoning(), state.getDecisionBlock());
    }
}
