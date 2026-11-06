package com.agenthub.runtime.application.router;

import com.agenthub.agent.domain.model.AgentDef;
import com.agenthub.runtime.domain.model.TrialTurn;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个子 Agent 的执行结果。所属域：runtime。
 *
 * <p>三态：成功（有 turn）、失败（error 非空）、跳过（skipped=true，error 里是中文跳过说明）。
 * 「跳过不是失败」这条纪律由 {@link #hasAnswer()} 与各模板的终答汇总口径共同保证。
 *
 * @param intent  该子对应的执行意图
 * @param notices 子装配说明（源不可用 / 技能截断等）
 * @param turn    子终答与 token；失败 / 跳过时可空
 * @param error   失败或跳过说明；成功为 null
 * @param skipped true＝因超出并行上限未执行；false＝真跑过
 */
public record ChildOutcome(RoutedIntent intent, List<String> notices, TrialTurn turn, String error,
                           boolean skipped) {

    /**
     * 成功结果：notices 做防御性拷贝，避免调用方后续修改子列表时污染汇总。
     *
     * @param intent  执行意图
     * @param notices 装配说明；可空
     * @param turn    子终答
     * @return 成功结果
     */
    public static ChildOutcome ok(RoutedIntent intent, List<String> notices, TrialTurn turn) {
        return new ChildOutcome(intent, notices == null ? List.of() : new ArrayList<>(notices), turn, null, false);
    }

    /**
     * 失败结果：保留中文失败摘要，不抛异常（一子失败其它继续）。
     *
     * @param intent 执行意图
     * @param error  失败摘要
     * @return 失败结果
     */
    public static ChildOutcome failed(RoutedIntent intent, String error) {
        return new ChildOutcome(intent, List.of(), null, error, false);
    }

    /**
     * 跳过结果：用于超出并行/串行上限的候选，时间线上仍要留一行。
     *
     * @param intent 执行意图
     * @param note   中文跳过说明
     * @return 跳过结果
     */
    public static ChildOutcome skipped(RoutedIntent intent, String note) {
        return new ChildOutcome(intent, List.of(), null, note, true);
    }

    /**
     * 是否有可参与选优 / 投票 / 汇总的正文。
     *
     * @return true 表示非跳过、无错误且正文非空
     */
    public boolean hasAnswer() {
        return !skipped && error == null && turn != null
                && turn.getAssistantText() != null && !turn.getAssistantText().isBlank();
    }

    /**
     * 静态口径的同义方法：模板里大量「过滤有正文的子」写法用方法引用更顺。
     *
     * @param outcome 子结果；可空
     * @return 可参与选优时为 true
     */
    public static boolean hasAnswer(ChildOutcome outcome) {
        return outcome != null && outcome.hasAnswer();
    }

    /**
     * 候选 route_key；缺失时退回 Agent id，保证日志与时间线可读。
     *
     * @return 非空标识
     */
    public String key() {
        String key = intent.candidate().route().getRouteKey();
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        AgentDef agent = intent.candidate().agent();
        return agent == null || agent.getId() == null ? "unknown" : "#" + agent.getId();
    }

    /**
     * 静态口径的同义方法：日志、泳道与投票解析都按 route_key 对齐。
     *
     * @param outcome 子结果
     * @return 非空标识
     */
    public static String keyOf(ChildOutcome outcome) {
        return outcome.key();
    }
}
