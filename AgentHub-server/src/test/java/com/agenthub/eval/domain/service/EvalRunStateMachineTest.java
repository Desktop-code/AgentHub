package com.agenthub.eval.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.eval.domain.model.EvalResult;
import com.agenthub.eval.domain.model.EvalRun;
import com.agenthub.eval.domain.model.EvalRunStatus;
import com.agenthub.eval.domain.model.EvalVerdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机与取消收尾口径。所属域：eval。纯域单测：不连库、不起服务、不出站。
 * <p>覆盖：合法迁移矩阵全量、非法迁移抛 BizException、终态不可再迁、
 * 计数器与 gate_passed 口径、取消后 FAILED / PARTIAL 两条分支。
 */
class EvalRunStateMachineTest {

    /**
     * 合法迁移矩阵：逐个 from→to 组合，只有矩阵内的返回 true。
     */
    @Test
    void allowedTransitionsMatchSpec() {
        // PENDING 只能开跑 / 取消 / 基础设施立刻失败
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.PENDING, EvalRunStatus.RUNNING));
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.PENDING, EvalRunStatus.CANCELING));
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.PENDING, EvalRunStatus.FAILED));
        assertFalse(EvalRunStateMachine.canTransit(EvalRunStatus.PENDING, EvalRunStatus.SUCCEEDED));
        assertFalse(EvalRunStateMachine.canTransit(EvalRunStatus.PENDING, EvalRunStatus.PARTIAL));
        assertFalse(EvalRunStateMachine.canTransit(EvalRunStatus.PENDING, EvalRunStatus.PENDING));

        // RUNNING 四选，含取消
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.RUNNING, EvalRunStatus.SUCCEEDED));
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.RUNNING, EvalRunStatus.FAILED));
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.RUNNING, EvalRunStatus.PARTIAL));
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.RUNNING, EvalRunStatus.CANCELING));
        assertFalse(EvalRunStateMachine.canTransit(EvalRunStatus.RUNNING, EvalRunStatus.PENDING));

        // CANCELING 只能收尾成 FAILED / PARTIAL，取消永远不算成功
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.CANCELING, EvalRunStatus.FAILED));
        assertTrue(EvalRunStateMachine.canTransit(EvalRunStatus.CANCELING, EvalRunStatus.PARTIAL));
        assertFalse(EvalRunStateMachine.canTransit(EvalRunStatus.CANCELING, EvalRunStatus.SUCCEEDED));
        assertFalse(EvalRunStateMachine.canTransit(EvalRunStatus.CANCELING, EvalRunStatus.RUNNING));

        // 终态 → 任意：全部禁止
        for (EvalRunStatus terminal : List.of(EvalRunStatus.SUCCEEDED, EvalRunStatus.FAILED,
                EvalRunStatus.PARTIAL)) {
            for (EvalRunStatus target : EvalRunStatus.values()) {
                assertFalse(EvalRunStateMachine.canTransit(terminal, target),
                        "终态 " + terminal + " 不应能迁到 " + target);
            }
        }
    }

    /**
     * 非法迁移抛 BizException，文案点明方向；终态再迁移单独给「已结束」提示。
     */
    @Test
    void illegalTransitionThrowsBizException() {
        BizException pendingToSucceeded = assertThrows(BizException.class,
                () -> EvalRunStateMachine.requireTransition(EvalRunStatus.PENDING, EvalRunStatus.SUCCEEDED));
        assertEquals(ErrorCode.BAD_REQUEST, pendingToSucceeded.getErrorCode());
        assertTrue(pendingToSucceeded.getMessage().contains("非法状态迁移"));

        BizException terminalAgain = assertThrows(BizException.class,
                () -> EvalRunStateMachine.requireTransition(EvalRunStatus.SUCCEEDED, EvalRunStatus.RUNNING));
        assertTrue(terminalAgain.getMessage().contains("终态"));

        BizException cancelToSuccess = assertThrows(BizException.class,
                () -> EvalRunStateMachine.requireTransition(EvalRunStatus.CANCELING, EvalRunStatus.SUCCEEDED));
        assertTrue(cancelToSuccess.getMessage().contains("非法状态迁移"));

        // 空状态（例如仓储回读了脏数据）也要给业务异常，不能 NPE
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(BizException.class,
                () -> EvalRunStateMachine.requireTransition(null, EvalRunStatus.RUNNING)).getErrorCode());
    }

    /**
     * moveTo 维护时间戳：进 RUNNING 记 started_time，进终态记 finished_time。
     */
    @Test
    void moveToMaintainsTimestamps() {
        EvalRun run = newRun(EvalRunStatus.PENDING, 2);

        EvalRunStateMachine.moveTo(run, EvalRunStatus.RUNNING);
        assertEquals(EvalRunStatus.RUNNING, run.getStatus());
        assertNotNull(run.getStartedTime());
        assertNull(run.getFinishedTime());

        EvalRunStateMachine.moveTo(run, EvalRunStatus.SUCCEEDED);
        assertEquals(EvalRunStatus.SUCCEEDED, run.getStatus());
        assertTrue(run.isTerminal());
        assertNotNull(run.getFinishedTime());
    }

    /**
     * 计数器口径：PASS/FAIL/ERROR/SKIPPED 分别落桶，null 不计入任何桶。
     */
    @Test
    void recountBucketsByVerdict() {
        EvalRun run = newRun(EvalRunStatus.RUNNING, 4);
        List<EvalResult> rows = new ArrayList<>();
        rows.add(result("c0", EvalVerdict.PASS));
        rows.add(result("c1", EvalVerdict.PASS));
        rows.add(result("c2", EvalVerdict.SKIPPED));
        rows.add(result("c3", null));

        EvalRunStateMachine.recount(run, rows);
        assertEquals(4, run.getTotalCases());
        assertEquals(2, run.getPassed());
        assertEquals(0, run.getFailed());
        assertEquals(0, run.getErrored());
        assertEquals(1, run.getSkipped());
        // 有 SKIPPED 残留 → 门禁视为未通过
        assertEquals(0, run.getGatePassed());
    }

    /**
     * 全部 PASS 才是 gate_passed=1；一条都没判定时保持 null（不是 0）。
     */
    @Test
    void gatePassedOnlyWhenAllPassed() {
        EvalRun allPass = newRun(EvalRunStatus.RUNNING, 2);
        EvalRunStateMachine.recount(allPass, List.of(result("a", EvalVerdict.PASS), result("b", EvalVerdict.PASS)));
        assertEquals(1, allPass.getGatePassed());

        EvalRun untouched = newRun(EvalRunStatus.RUNNING, 2);
        EvalRunStateMachine.recount(untouched, List.of(result("a", null), result("b", null)));
        assertNull(untouched.getGatePassed(), "未出判定时 gate_passed 必须是 null");
        assertEquals(0, untouched.getPassed());
    }

    /**
     * 取消分支一：一个 PASS/FAIL 都没有 → 未完成行全 SKIPPED，终态 FAILED。
     */
    @Test
    void cancelWithoutAnyJudgedEndsFailed() {
        EvalRun run = newRun(EvalRunStatus.PENDING, 3);
        List<EvalResult> rows = List.of(result("c0", null), result("c1", null), result("c2", null));

        EvalRunStateMachine.applyCancelFinish(run, rows);

        assertEquals(EvalRunStatus.FAILED, run.getStatus());
        assertEquals(3, run.getSkipped());
        assertEquals(0, run.getPassed());
        assertEquals(0, run.getGatePassed());
        assertNotNull(run.getFinishedTime());
        rows.forEach(one -> assertEquals(EvalVerdict.SKIPPED, one.getVerdict()));
    }

    /**
     * 取消分支二：已有 PASS/FAIL 且其余 SKIPPED → 终态 PARTIAL（门禁出过部分结论）。
     */
    @Test
    void cancelWithSomeJudgedEndsPartial() {
        EvalRun run = newRun(EvalRunStatus.RUNNING, 3);
        List<EvalResult> rows = List.of(result("c0", EvalVerdict.PASS), result("c1", null), result("c2", null));

        EvalRunStateMachine.applyCancelFinish(run, rows);

        assertEquals(EvalRunStatus.PARTIAL, run.getStatus());
        assertEquals(1, run.getPassed());
        assertEquals(2, run.getSkipped());
        assertEquals(EvalVerdict.PASS, rows.get(0).getVerdict(), "已有判定不得被覆盖");
        assertEquals(0, run.getGatePassed());
    }

    /**
     * 终态再取消 → BizException，不静默成功。
     */
    @Test
    void cancelOnTerminalThrows() {
        EvalRun run = newRun(EvalRunStatus.PENDING, 1);
        EvalRunStateMachine.applyCancelFinish(run, List.of(result("c0", null)));
        assertEquals(EvalRunStatus.FAILED, run.getStatus());

        BizException ex = assertThrows(BizException.class,
                () -> EvalRunStateMachine.applyCancelFinish(run, List.of(result("c0", null))));
        assertTrue(ex.getMessage().contains("不能再取消"));
    }

    /**
     * 取消收尾结果函数单独校验口径（PASS 或 FAIL 任一即 PARTIAL）。
     */
    @Test
    void cancelOutcomeNeedsPassOrFail() {
        assertEquals(EvalRunStatus.PARTIAL,
                EvalRunStateMachine.resolveCancelOutcome(List.of(result("a", EvalVerdict.FAIL))));
        assertEquals(EvalRunStatus.PARTIAL,
                EvalRunStateMachine.resolveCancelOutcome(List.of(result("a", EvalVerdict.PASS))));
        assertEquals(EvalRunStatus.FAILED,
                EvalRunStateMachine.resolveCancelOutcome(List.of(result("a", EvalVerdict.ERROR))));
        assertEquals(EvalRunStatus.FAILED, EvalRunStateMachine.resolveCancelOutcome(List.of()));
        assertEquals(EvalRunStatus.FAILED, EvalRunStateMachine.resolveCancelOutcome(null));
    }

    /**
     * 执行收尾口径（刀3）：PASS+ERROR→PARTIAL；全 ERROR→FAILED；全 PASS / 有 FAIL 无 ERROR→SUCCEEDED；
     * gate_passed：全 PASS（可夹 SKIPPED）→1；有 FAIL/ERROR→0。
     */
    @Test
    void executeFinishOutcomeAndGatePassed() {
        assertEquals(EvalRunStatus.PARTIAL, EvalRunStateMachine.resolveExecuteOutcome(
                List.of(result("a", EvalVerdict.PASS), result("b", EvalVerdict.ERROR))));
        assertEquals(EvalRunStatus.FAILED, EvalRunStateMachine.resolveExecuteOutcome(
                List.of(result("a", EvalVerdict.ERROR), result("b", EvalVerdict.ERROR))));
        assertEquals(EvalRunStatus.SUCCEEDED, EvalRunStateMachine.resolveExecuteOutcome(
                List.of(result("a", EvalVerdict.PASS))));
        assertEquals(EvalRunStatus.SUCCEEDED, EvalRunStateMachine.resolveExecuteOutcome(
                List.of(result("a", EvalVerdict.FAIL))));
        assertEquals(EvalRunStatus.FAILED, EvalRunStateMachine.resolveExecuteOutcome(
                List.of(result("a", EvalVerdict.SKIPPED))));

        assertEquals(1, EvalRunStateMachine.resolveExecuteGatePassed(
                List.of(result("a", EvalVerdict.PASS), result("b", EvalVerdict.SKIPPED))));
        assertEquals(0, EvalRunStateMachine.resolveExecuteGatePassed(
                List.of(result("a", EvalVerdict.FAIL))));
        assertEquals(0, EvalRunStateMachine.resolveExecuteGatePassed(
                List.of(result("a", EvalVerdict.ERROR))));

        EvalRun passRun = newRun(EvalRunStatus.RUNNING, 1);
        EvalRunStateMachine.applyExecuteFinish(passRun, List.of(result("a", EvalVerdict.PASS)));
        assertEquals(EvalRunStatus.SUCCEEDED, passRun.getStatus());
        assertEquals(1, passRun.getGatePassed());
        assertEquals(1, passRun.getPassed());

        EvalRun failRun = newRun(EvalRunStatus.RUNNING, 1);
        EvalRunStateMachine.applyExecuteFinish(failRun, List.of(result("a", EvalVerdict.FAIL)));
        assertEquals(EvalRunStatus.SUCCEEDED, failRun.getStatus());
        assertEquals(0, failRun.getGatePassed());
        assertEquals(1, failRun.getFailed());
    }

    // ------------------------------------------------------------------
    // 构造工具：只造数据，不含断言
    // ------------------------------------------------------------------

    private EvalRun newRun(EvalRunStatus status, int total) {
        EvalRun run = new EvalRun();
        run.setId(1L);
        run.setTenantId(1L);
        run.setStatus(status);
        run.setTotalCases(total);
        return run;
    }

    private EvalResult result(String caseKey, EvalVerdict verdict) {
        EvalResult one = new EvalResult();
        one.setRunId(1L);
        one.setTenantId(1L);
        one.setCaseKey(caseKey);
        one.setVerdict(verdict);
        return one;
    }
}
