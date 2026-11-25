package com.agenthub.workflow.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运行状态机单测。所属域：workflow 的 domain。
 * <p>硬钉：CREATED 不能直接 SUCCESS；终态不能再迁移；WAITING_HUMAN 只能收成成功 / 失败 / 取消。
 */
class WorkflowStateMachineTest {

    @Test
    @DisplayName("run 正常链路 CREATED→RUNNING→PAUSED_HUMAN→RUNNING→SUCCESS 全部合法")
    void runHappyPath() {
        assertThat(WorkflowStateMachine.canRun(WorkflowRunStatus.CREATED, WorkflowRunStatus.RUNNING)).isTrue();
        assertThat(WorkflowStateMachine.canRun(WorkflowRunStatus.RUNNING, WorkflowRunStatus.PAUSED_HUMAN)).isTrue();
        assertThat(WorkflowStateMachine.canRun(WorkflowRunStatus.PAUSED_HUMAN, WorkflowRunStatus.RUNNING)).isTrue();
        assertThat(WorkflowStateMachine.canRun(WorkflowRunStatus.RUNNING, WorkflowRunStatus.SUCCESS)).isTrue();
    }

    @Test
    @DisplayName("run 禁止跳态与终态再迁移")
    void runIllegalTransitions() {
        assertThat(WorkflowStateMachine.canRun(WorkflowRunStatus.CREATED, WorkflowRunStatus.SUCCESS)).isFalse();
        assertThat(WorkflowStateMachine.canRun(WorkflowRunStatus.SUCCESS, WorkflowRunStatus.RUNNING)).isFalse();
        assertThat(WorkflowStateMachine.canRun(WorkflowRunStatus.CANCELLED, WorkflowRunStatus.RUNNING)).isFalse();
        assertThatThrownBy(() -> WorkflowStateMachine.requireRun(
                WorkflowRunStatus.SUCCESS, WorkflowRunStatus.FAILED))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许");
    }

    @Test
    @DisplayName("node 正常链路与非法跳态")
    void nodeTransitions() {
        assertThat(WorkflowStateMachine.canNode(
                WorkflowNodeRunStatus.PENDING, WorkflowNodeRunStatus.RUNNING)).isTrue();
        assertThat(WorkflowStateMachine.canNode(
                WorkflowNodeRunStatus.RUNNING, WorkflowNodeRunStatus.WAITING_HUMAN)).isTrue();
        assertThat(WorkflowStateMachine.canNode(
                WorkflowNodeRunStatus.WAITING_HUMAN, WorkflowNodeRunStatus.SUCCESS)).isTrue();
        assertThat(WorkflowStateMachine.canNode(
                WorkflowNodeRunStatus.PENDING, WorkflowNodeRunStatus.SUCCESS)).isFalse();
        assertThat(WorkflowStateMachine.canNode(
                WorkflowNodeRunStatus.SUCCESS, WorkflowNodeRunStatus.RUNNING)).isFalse();
    }

    @Test
    @DisplayName("同值重复写按幂等放行，便于取消接口重复点击")
    void sameStatusAllowed() {
        assertThat(WorkflowStateMachine.canRun(WorkflowRunStatus.CANCELLED, WorkflowRunStatus.CANCELLED)).isTrue();
        assertThat(WorkflowStateMachine.canNode(WorkflowNodeRunStatus.FAILED, WorkflowNodeRunStatus.FAILED)).isTrue();
    }
}
