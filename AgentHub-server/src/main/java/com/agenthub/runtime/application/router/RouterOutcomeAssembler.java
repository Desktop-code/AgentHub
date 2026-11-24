package com.agenthub.runtime.application.router;

import com.agenthub.runtime.application.RouterTrialExecutor.RouteSegment;
import com.agenthub.runtime.domain.model.ParticipantView;
import com.agenthub.runtime.domain.model.RouteSticky;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮编排的结果组装。所属域：runtime。
 *
 * <p>从 {@code RouterTrialExecutor} 搬出：六套模板里近似的「子结果 → 分段 + SYSTEM 说明」循环、
 * 超上限跳过与未获允许（blocked）的留痕、汇总结论（★ 选中 / 结论行 / 推理暂存）以及粘性线路回写，
 * 统一收在这里。执行器只决定「谁跑、按什么顺序跑」，各模板不再各写一遍组装口径。
 *
 * <p>必须保持不变：
 * <ul>
 *   <li>分段顺序＝执行顺序；跳过的子只写路由行，不进终答、不计 token；</li>
 *   <li>未获允许的子只追加路由行 + notice，永不参与汇总；</li>
 *   <li>「★ 选中」与汇总结论行的中文文案、追加位置不变；</li>
 *   <li>粘性线路：单意图沿用、多意图清空（下一轮重判）。</li>
 * </ul>
 *
 * <p>已知的临时缝：{@link RouteSegment} 仍是 {@link RouterTrialExecutor} 的内部类型。
 * 本刀按约定不搬 `RoutedTurn` / `RouteSegment` DTO；等类型独立成文件后，这里只需改 import。
 */
public final class RouterOutcomeAssembler {

    /**
     * 真正跑过的子段：带上与 SSE begin 相同的身份，供时间线在路由行之前落 participant。
     *
     * <p>跳过的子没有 begin，身份留空，避免回放多出一张没开跑的卡。
     *
     * @param outcome 子结果
     * @param line    路由行
     * @return 分段
     */
    public static RouteSegment ranSegment(ChildOutcome outcome, String line) {
        // 身份只认执行时那一份：事后按 intent 重算在嵌套退出后会退化成 root
        ParticipantView who = outcome != null && !outcome.skipped() ? outcome.participant() : null;
        return new RouteSegment(line, outcome.turn(),
                outcome.skipped() ? null : outcome.error(), outcome.children(), outcome.reasoning(),
                outcome.workflow(), outcome.conclusionName(), outcome.conclusionReasoning())
                .withParticipant(who);
    }

    /**
     * 按执行顺序把子结果收成「分段 + 说明」。
     *
     * @param outcomes 已按执行顺序排好的子结果（含超上限跳过的占位）
     * @param notices  SYSTEM 说明收集器（原地追加）
     * @param segments 分段收集器（原地追加）
     */
    public void collect(List<ChildOutcome> outcomes, List<String> notices, List<RouteSegment> segments) {
        for (ChildOutcome outcome : outcomes) {
            notices.addAll(outcome.notices());
            // 跳过不是失败：只留「已跳过」路由行，不写成子 Agent 失败
            segments.add(ranSegment(outcome, outcome.intent().routeLine()));
        }
    }

    /**
     * 判路由模板的收集：路由行按判定顺序优先，子数多于判定行时按实际意图补一行。
     *
     * @param outcomes   子结果（执行顺序）
     * @param routeLines 判路由得到的路由行（顺序与 intents 一致）；可空
     * @param notices    SYSTEM 说明收集器
     * @param segments   分段收集器
     */
    public void collectRoutes(List<ChildOutcome> outcomes, List<String> routeLines,
                              List<String> notices, List<RouteSegment> segments) {
        for (int i = 0; i < outcomes.size(); i++) {
            ChildOutcome outcome = outcomes.get(i);
            notices.addAll(outcome.notices());
            // 判定行优先；多出的子（判路由未覆盖）按它自己的意图补一行，不丢段
            String line = i < routeLines.size() ? routeLines.get(i)
                    : RouterTexts.routeSummary(outcome.intent().candidate(), outcome.intent().reason());
            segments.add(ranSegment(outcome, line));
        }
    }

    /**
     * 竞争选优的收集：赢家行补「★ 选中」，其余原样。
     *
     * <p>不复用 {@link #markWinner(List, int)}：竞争的子段要保留自己的结论字段（目标方案汇总），
     * 这里必须原样走 {@link #ranSegment(ChildOutcome, String)}，只改标题一行。
     *
     * @param outcomes 参赛子结果（候选顺序）
     * @param winner   选优赢家
     * @param notices  SYSTEM 说明收集器
     * @param segments 分段收集器
     */
    public void collectPicked(List<ChildOutcome> outcomes, ChildOutcome winner,
                              List<String> notices, List<RouteSegment> segments) {
        for (ChildOutcome outcome : outcomes) {
            notices.addAll(outcome.notices());
            // 跳过不是失败：只留「已跳过」行，不写成子 Agent 失败
            String line = outcome == winner
                    ? outcome.intent().routeLine() + " · ★ 选中"
                    : outcome.intent().routeLine();
            segments.add(ranSegment(outcome, line));
        }
    }

    /**
     * 超上限未执行的候选补成「已跳过」占位，随后由 {@link #collect} 统一收段。
     *
     * @param skipped    超限意图
     * @param limitLabel 上限中文名（「并行上限」/「串行上限」），文案逐字不变
     * @param cap        配置上限
     * @param sink       子结果收集器（原地追加）
     */
    public void appendSkippedOutcomes(List<RoutedIntent> skipped, String limitLabel, int cap,
                                      List<ChildOutcome> sink) {
        for (RoutedIntent skip : skipped) {
            sink.add(ChildOutcome.skipped(skip,
                    "「" + RouterTexts.childLabel(skip.candidate().agent()) + "」超出" + limitLabel + " "
                            + cap + "，未执行"));
        }
    }

    /**
     * 竞争选优的跳过行：只追加路由行（比赛不把超限者算进参赛者分段与终答）。
     *
     * @param skipped  超限意图
     * @param segments 分段收集器
     */
    public void appendSkippedLines(List<RoutedIntent> skipped, List<RouteSegment> segments) {
        for (RoutedIntent skip : skipped) {
            segments.add(new RouteSegment(skip.routeLine(), null, null));
        }
    }

    /**
     * 未获编排允许的候选：只追加路由行 + notice，不进终答。
     *
     * <p>不伪造执行：turn 为空，因此不参与拼接、不计 token，也不会触发额外的 assistant 推送。
     *
     * @param blocked  被挡下的候选
     * @param userText 用户原问（仅作占位意图的 subQuery，不会真送给子）
     * @param notices  SYSTEM 说明收集器
     * @param segments 分段收集器
     */
    public void appendBlocked(List<BlockedCandidate> blocked, String userText,
                              List<String> notices, List<RouteSegment> segments) {
        for (ChildOutcome blockedOutcome : blockedOutcomes(blocked, userText)) {
            notices.addAll(blockedOutcome.notices());
            segments.add(new RouteSegment(blockedOutcome.intent().routeLine(), null, null));
        }
    }

    /**
     * 把终答汇总结论挂到分段列表：赢家子段打「★ 选中」，再追加投票结论行。
     *
     * @param segments  已有子分段（顺序与 outcomes 一致）
     * @param notices   SYSTEM 说明收集器
     * @param aggregate 汇总结果
     */
    public void applyAggregate(List<RouteSegment> segments, List<String> notices, AggregateOutcome aggregate) {
        notices.addAll(aggregate.notices());
        // 赢家子段补 ★：与 COMPETE 同一口径，时间线能看出终答取自哪一位
        markWinner(segments, aggregate.winnerIndex());
        if (aggregate.segmentLine() != null) {
            segments.add(new RouteSegment(aggregate.segmentLine(), null, null));
        }
        // 真汇总的标记与推理交给本轮暂存：写时间线时要先落「方案结论：X」，再落它的思考
        TurnNotes notes = TurnNotes.current();
        if (notes != null && aggregate.conclusionName() != null) {
            notes.conclusionName = aggregate.conclusionName();
            if (aggregate.reasoning() != null) {
                notes.conclusionReasoning.append(aggregate.reasoning());
            }
        }
    }

    /**
     * 粘性线路回写：单意图沿用本条线路，多意图清空（下一轮重判，避免把协作误降级成单人）。
     *
     * @param intents 本轮命中的意图（至少 1 项）
     * @return 线路回写指令
     */
    public RouteSticky stickyOf(List<RoutedIntent> intents) {
        return intents.size() >= 2
                ? RouteSticky.clear()
                : RouteSticky.keep(intents.get(0).candidate().route().getRouteKey(),
                        intents.get(0).candidate().agent().getId());
    }

    /**
     * 给赢家子段补「★ 选中」（不改其它字段）。
     *
     * @param segments    子分段
     * @param winnerIndex 赢家下标；-1 或越界表示无赢家，原样返回
     */
    public void markWinner(List<RouteSegment> segments, int winnerIndex) {
        if (winnerIndex < 0 || winnerIndex >= segments.size()) {
            return;
        }
        RouteSegment old = segments.get(winnerIndex);
        segments.set(winnerIndex, new RouteSegment(
                (old.routeLine() == null ? "" : old.routeLine()) + " · ★ 选中", old.turn(), old.error(),
                old.children(), old.reasoning()).withWorkflow(old.workflow())
                .withParticipant(old.participant()));
    }

    /**
     * 把被挡下的子转成「已跳过」占位。
     *
     * <p>不伪造执行：turn 为空、skipped=true，因此不进终答拼接、不计 token，只在路由时间线留中文原因。
     *
     * @param blocked  被挡下的候选
     * @param userText 用户原问（仅作占位意图的 subQuery，不会真送给子）
     * @return 跳过占位行，顺序与 blocked 一致
     */
    private List<ChildOutcome> blockedOutcomes(List<BlockedCandidate> blocked, String userText) {
        List<ChildOutcome> outcomes = new ArrayList<>(blocked.size());
        for (BlockedCandidate blockedItem : blocked) {
            Candidate candidate = blockedItem.candidate();
            String label = RouterTexts.childLabel(candidate.agent());
            String reason = blockedItem.reason();
            outcomes.add(ChildOutcome.skipped(
                    new RoutedIntent(candidate, userText, reason,
                            "已跳过 " + label + "：" + reason + "（"
                                    + RouterCandidateLoader.childModeText(blockedItem.runMode()) + "）"),
                    "「" + label + "」" + reason + "，已跳过"));
        }
        return outcomes;
    }
}
