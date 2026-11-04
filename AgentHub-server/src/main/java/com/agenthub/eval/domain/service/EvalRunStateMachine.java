package com.agenthub.eval.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;
import com.agenthub.eval.domain.model.EvalVerdict;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 评测运行状态机与汇总计数器。所属域：eval 的 domain。**纯领域**：无 Spring、无仓储、无 IO。
 * <p>职责（本刀唯一允许改写 status / 计数器的入口）：
 * <ul>
 *   <li>{@link #moveTo}：校验并执行一次状态迁移，非法迁移抛 {@link BizException}；</li>
 *   <li>{@link #recount}：按当前 result 行重算 total/passed/failed/errored/skipped 与 gate_passed；</li>
 *   <li>{@link #applyCancelFinish}：取消收尾——未出判定的行置 SKIPPED，再按定稿口径落到 FAILED/PARTIAL。</li>
 * </ul>
 * <p>取消口径（写死，勿摇摆）：已有 PASS/FAIL ⇒ **PARTIAL**；一个 PASS/FAIL 都没有 ⇒ **FAILED**。
 * <p>为什么集中在这里：状态与计数器必须同源。若应用层各自 setStatus / setPassed，
 * 迟早出现「状态 SUCCEEDED 但 passed=0」这种自相矛盾的行。
 * 线程：无状态工具类，可并发调用。
 */
public final class EvalRunStateMachine {

    private EvalRunStateMachine() {
    }

    /**
     * 判断一次迁移是否被允许。迁移矩阵见 {@link EvalRunStatus} 类注释。
     *
     * @param from 当前状态，不可空
     * @param to   目标状态，不可空
     * @return true 表示合法
     */
    public static boolean canTransit(EvalRunStatus from, EvalRunStatus to) {
        if (from == null || to == null) {
            return false;
        }
        return switch (from) {
            // PENDING 可以开跑、可以就地取消，也可以在基础设施立刻失败时直接终结
            case PENDING -> to == EvalRunStatus.RUNNING || to == EvalRunStatus.CANCELING
                    || to == EvalRunStatus.FAILED;
            case RUNNING -> to == EvalRunStatus.SUCCEEDED || to == EvalRunStatus.FAILED
                    || to == EvalRunStatus.PARTIAL || to == EvalRunStatus.CANCELING;
            // 取消中只能收尾成失败或部分完成：取消永远不算成功
            case CANCELING -> to == EvalRunStatus.FAILED || to == EvalRunStatus.PARTIAL;
            // 终态一律禁止再迁移
            case SUCCEEDED, FAILED, PARTIAL -> false;
        };
    }

    /**
     * 校验一次迁移，非法即抛业务异常。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @throws BizException 非法迁移（含终态再迁移、空状态）
     */
    public static void requireTransition(EvalRunStatus from, EvalRunStatus to) {
        if (from == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测运行状态未知，无法迁移");
        }
        if (to == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "目标状态不能为空");
        }
        if (from.isTerminal()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评测运行已是终态 " + from.name() + "（" + from.getLabel() + "），不能再改为 " + to.name());
        }
        if (!canTransit(from, to)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "非法状态迁移：" + from.name() + " → " + to.name());
        }
    }

    /**
     * 执行一次状态迁移，并维护时间戳：
     * 进入 RUNNING 记 started_time；进入任一终态记 finished_time（已有值则不覆盖）。
     *
     * @param run    运行行
     * @param target 目标状态
     * @return 同一实例，字段已更新
     * @throws BizException 非法迁移
     */
    public static EvalRun moveTo(EvalRun run, EvalRunStatus target) {
        if (run == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测运行不能为空");
        }
        requireTransition(run.getStatus(), target);
        LocalDateTime now = LocalDateTime.now();
        if (target == EvalRunStatus.RUNNING && run.getStartedTime() == null) {
            run.setStartedTime(now);
        }
        if (target.isTerminal()) {
            run.setFinishedTime(now);
        }
        run.setStatus(target);
        return run;
    }

    /**
     * 把 run 推进到 RUNNING。
     * <p>仅供刀2b 的 Runner 与单测使用；**生产 REST 不暴露**（本刀没有 execute 接口）。
     *
     * @param run 运行行
     * @return 同一实例
     * @throws BizException 非法迁移
     */
    public static EvalRun markRunning(EvalRun run) {
        return moveTo(run, EvalRunStatus.RUNNING);
    }

    /**
     * 按当前 result 行重算计数器与门禁总结果。
     * <p>口径：
     * <ul>
     *   <li>total_cases = 结果行数（与建跑时的计划数一致；不一致说明数据被人工改过，也以此为准）；</li>
     *   <li>passed / failed / errored / skipped 分别数 PASS / FAIL / ERROR / SKIPPED，null 的不计入任何桶；</li>
     *   <li>gate_passed：有 SKIPPED 残留 ⇒ 0（跑不完整不能算过门禁）；
     *       有 FAIL 或 ERROR ⇒ 0；全部 PASS（且至少 1 条）⇒ 1；其余（如尚未跑）⇒ null。</li>
     * </ul>
     *
     * @param run     运行行
     * @param results 该 run 的全部结果行，可为空列表
     * @return 同一实例，计数器已更新
     */
    public static EvalRun recount(EvalRun run, List<EvalResult> results) {
        if (run == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测运行不能为空");
        }
        List<EvalResult> rows = results == null ? List.of() : results;
        Map<EvalVerdict, Long> byVerdict = rows.stream()
                .map(EvalResult::getVerdict)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.groupingBy(one -> one, java.util.stream.Collectors.counting()));

        int passed = count(byVerdict, EvalVerdict.PASS);
        int failed = count(byVerdict, EvalVerdict.FAIL);
        int errored = count(byVerdict, EvalVerdict.ERROR);
        int skipped = count(byVerdict, EvalVerdict.SKIPPED);

        run.setTotalCases(rows.size());
        run.setPassed(passed);
        run.setFailed(failed);
        run.setErrored(errored);
        run.setSkipped(skipped);
        run.setGatePassed(resolveGatePassed(rows.size(), passed, failed, errored, skipped));
        return run;
    }

    /**
     * 取消收尾的唯一口径：先把所有未出判定的结果行打成 SKIPPED，
     * 重算计数器，再把状态从 CANCELING 落到终态。
     * <p>落到哪个终态：**已有 PASS/FAIL ⇒ PARTIAL；没有 ⇒ FAILED**。
     * （定稿 §3.2 的 PARTIAL 是「门禁已出部分结果但没跑完」，恰好对应前者。）
     *
     * @param run     运行行，当前必须是 CANCELING（或 PENDING/RUNNING，会先迁到 CANCELING）
     * @param results 该 run 的全部结果行；其中的未完成行会被原地改成 SKIPPED
     * @return 同一实例，状态已是 FAILED 或 PARTIAL
     * @throws BizException 已是终态（不允许重复收尾）
     */
    public static EvalRun applyCancelFinish(EvalRun run, List<EvalResult> results) {
        if (run == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测运行不能为空");
        }
        if (run.isTerminal()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "评测运行已结束（" + run.getStatus().getLabel() + "），不能再取消");
        }
        List<EvalResult> rows = results == null ? List.of() : results;
        // PENDING / RUNNING 直接取消时先落 CANCELING，保持与 RUNNING 同一套收尾路径
        if (run.getStatus() != EvalRunStatus.CANCELING) {
            moveTo(run, EvalRunStatus.CANCELING);
        }
        // 未出判定的一律 SKIPPED：本刀没有 Trial，占位行本来就是 null，这里只是把口径落库
        for (EvalResult one : rows) {
            if (one.isUnfinished()) {
                one.setVerdict(EvalVerdict.SKIPPED);
            }
        }
        recount(run, rows);
        return moveTo(run, resolveCancelOutcome(rows));
    }

    /**
     * 取消后的终态：已有 PASS/FAIL 说明门禁出过部分结论 ⇒ PARTIAL；否则 FAILED。
     *
     * @param results 结果行
     * @return PARTIAL 或 FAILED
     */
    public static EvalRunStatus resolveCancelOutcome(List<EvalResult> results) {
        boolean anyJudged = results != null && results.stream()
                .map(EvalResult::getVerdict)
                .anyMatch(one -> one == EvalVerdict.PASS || one == EvalVerdict.FAIL);
        return anyJudged ? EvalRunStatus.PARTIAL : EvalRunStatus.FAILED;
    }

    /**
     * 执行收尾：所有该跑的 case 处理完后，从 RUNNING 落到终态，并写 {@code gate_passed}（刀3）。
     * <p>终态口径：
     * <ul>
     *   <li>有 ERROR 且有 PASS → {@link EvalRunStatus#PARTIAL}；</li>
     *   <li>有 ERROR 无 PASS → {@link EvalRunStatus#FAILED}；</li>
     *   <li>有 PASS 或 FAIL（已跑完评分）无 ERROR → {@link EvalRunStatus#SUCCEEDED}；</li>
     *   <li>全 SKIPPED → {@link EvalRunStatus#FAILED}。</li>
     * </ul>
     * <p>gate_passed（刀3 钉死）：任一 FAIL 或任一 ERROR → false；
     * 全部应评分 case 为 PASS（允许夹杂 SKIPPED）且无 FAIL/ERROR → true。
     *
     * @param run     运行行，当前应为 RUNNING
     * @param results 全部结果行
     * @return 同一实例，已是终态
     * @throws BizException 非法迁移
     */
    public static EvalRun applyExecuteFinish(EvalRun run, List<EvalResult> results) {
        if (run == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "评测运行不能为空");
        }
        List<EvalResult> rows = results == null ? List.of() : results;
        recount(run, rows);
        run.setGatePassed(resolveExecuteGatePassed(rows));
        return moveTo(run, resolveExecuteOutcome(rows));
    }

    /**
     * 执行收尾终态口径，见 {@link #applyExecuteFinish}。
     *
     * @param results 结果行
     * @return SUCCEEDED / FAILED / PARTIAL
     */
    public static EvalRunStatus resolveExecuteOutcome(List<EvalResult> results) {
        List<EvalResult> rows = results == null ? List.of() : results;
        boolean anyPass = rows.stream().map(EvalResult::getVerdict).anyMatch(one -> one == EvalVerdict.PASS);
        boolean anyFail = rows.stream().map(EvalResult::getVerdict).anyMatch(one -> one == EvalVerdict.FAIL);
        boolean anyError = rows.stream().map(EvalResult::getVerdict).anyMatch(one -> one == EvalVerdict.ERROR);
        if (anyError && anyPass) {
            return EvalRunStatus.PARTIAL;
        }
        if (anyError) {
            return EvalRunStatus.FAILED;
        }
        // 有 FAIL 仍算跑完 → SUCCEEDED（gate_passed 另算 false）
        if (anyPass || anyFail) {
            return EvalRunStatus.SUCCEEDED;
        }
        return EvalRunStatus.FAILED;
    }

    /**
     * 执行收尾的 gate_passed：FAIL 或 ERROR → 0；全 PASS（可夹 SKIPPED）→ 1；否则 0。
     *
     * @param results 结果行
     * @return 0 或 1（终态后非 null）
     */
    public static Integer resolveExecuteGatePassed(List<EvalResult> results) {
        List<EvalResult> rows = results == null ? List.of() : results;
        boolean anyFail = rows.stream().map(EvalResult::getVerdict).anyMatch(one -> one == EvalVerdict.FAIL);
        boolean anyError = rows.stream().map(EvalResult::getVerdict).anyMatch(one -> one == EvalVerdict.ERROR);
        if (anyFail || anyError) {
            return 0;
        }
        boolean anyPass = rows.stream().map(EvalResult::getVerdict).anyMatch(one -> one == EvalVerdict.PASS);
        // 全 PASS（允许 SKIPPED）才宣称门禁通过；全 SKIPPED 不算过关
        return anyPass ? 1 : 0;
    }

    /**
     * 门禁总结果口径，见 {@link #recount} 注释。
     */
    private static Integer resolveGatePassed(int total, int passed, int failed, int errored, int skipped) {
        if (skipped > 0 || failed > 0 || errored > 0) {
            return 0;
        }
        if (total > 0 && passed == total) {
            return 1;
        }
        return null;
    }

    private static int count(Map<EvalVerdict, Long> byVerdict, EvalVerdict key) {
        Long value = byVerdict.get(key);
        return value == null ? 0 : value.intValue();
    }
}
