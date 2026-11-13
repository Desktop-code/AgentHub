package com.agenthub.runtime.application.router;

import com.agenthub.runtime.domain.model.MaRunSpec;
import com.agenthub.runtime.domain.model.TrialTurn;
import com.agenthub.runtime.domain.service.TrialProgress;

import java.util.List;

/**
 * 「另一套编排方案」作为子候选时的执行委托。所属域：runtime。
 *
 * <p>为什么抽接口：子执行（{@link ChildRunner}）只认「跑一个子」这一件事，
 * 而跑目标方案需要回到 {@code RouterTrialExecutor} 自身（按目标方案的模板再跑一层编排）；
 * 用接口注入可以避免 ChildRunner 反向依赖模板执行器，也避免执行器再 new 自己。
 *
 * <p>实现约定（由 RouterTrialExecutor 提供）：
 * <ul>
 *   <li>在<b>同一条会话</b>里执行，嵌套超过 3 层熔断，不写任何线路粘性（外层线路由调用方回写）；</li>
 *   <li>不传线路提示：外层的手动换线 / 沿用不穿透到目标方案；</li>
 *   <li>抛出的业务异常按「子 Agent 失败」口径由调用方收成失败段，不打断其它子。</li>
 * </ul>
 */
public interface MaTargetRunner {

    /**
     * 执行目标方案一轮。
     *
     * @param intent       命中的子意图（route_key / 子问题用于日志与分块标题）
     * @param target       目标方案执行来源（候选已过滤、判路由模型已就位）
     * @param userText     用户原文
     * @param modelUser    已注入记忆的用户侧文本
     * @param userId       平台用户
     * @param sessionId    当前会话（工作台会话，不另建）
     * @param deadlineNanos 外层墙钟截止（用于进出时快速失败）
     * @param progress     可空；目标方案自己的子增量继续推给同一条时间线
     * @return 目标方案终答与要展示在时间线上的说明
     */
    MaTargetResult run(RoutedIntent intent, MaRunSpec target, String userText, String modelUser,
                       long userId, long sessionId, long deadlineNanos, TrialProgress progress);

    /**
     * 目标方案一轮的结果。
     *
     * @param turn     目标方案的终答（token 已含其内部子 Agent 的消耗）
     * @param notices  时间线说明（跳过的候选、降级等）；可空
     * @param children 目标方案内部每一跳（子专家「路由到」行 + 该子自己的思考）；可空
     */
    record MaTargetResult(TrialTurn turn, List<String> notices, List<NestedRoute> children) {

        /**
         * 兼容构造：没有可展开的内部跳（窄测与「目标方案内部没有出新路线」的场景）。
         *
         * @param turn    目标方案的终答
         * @param notices 时间线说明；可空
         */
        public MaTargetResult(TrialTurn turn, List<String> notices) {
            this(turn, notices, List.of());
        }

        /** 目标表防御性归一：null → 空表，调用方不必逐处判空。 */
        public MaTargetResult {
            children = children == null ? List.of() : List.copyOf(children);
        }
    }
}
