package com.agenthub.workflow.domain.service;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;
import com.agenthub.workflow.domain.enums.WorkflowNodeRunStatus;
import com.agenthub.workflow.domain.enums.WorkflowRunStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 运行态状态机。所属域：workflow 的 domain。
 * <p>唯一职责：把「允许的迁移」写死在一处，所有落库前必须过这里，
 * 禁止在 Engine / AppService 里直接 setStatus。这样「只内存跑完不落状态」或「跳态」
 * 在代码层面就走不通。
 * <p>线程安全：迁移表构建后只读，无状态，可并发调用。
 */
public final class WorkflowStateMachine {

    /** run 允许的迁移；终态不在表里，等于「无出边」 */
    private static final Map<WorkflowRunStatus, Set<WorkflowRunStatus>> RUN_TRANSITIONS =
            buildRunTransitions();

    /** node_run 允许的迁移 */
    private static final Map<WorkflowNodeRunStatus, Set<WorkflowNodeRunStatus>> NODE_TRANSITIONS =
            buildNodeTransitions();

    private WorkflowStateMachine() {
    }

    /**
     * 校验一次 run 迁移。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true 表示合法
     */
    public static boolean canRun(WorkflowRunStatus from, WorkflowRunStatus to) {
        if (from == null || to == null || from == to) {
            // 同值重复写不算迁移：例如取消接口被重复点击，幂等返回即可，由调用方自行短路
            return from == to;
        }
        return RUN_TRANSITIONS.getOrDefault(from, Collections.emptySet()).contains(to);
    }

    /**
     * 校验并返回目标状态。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 目标状态，便于链式赋值
     * @throws BizException 非法迁移（BAD_REQUEST，中文说明）
     */
    public static WorkflowRunStatus requireRun(WorkflowRunStatus from, WorkflowRunStatus to) {
        if (!canRun(from, to)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "运行状态不允许从 " + label(from) + " 变更为 " + label(to));
        }
        return to;
    }

    /**
     * 校验一次节点迁移。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true 表示合法
     */
    public static boolean canNode(WorkflowNodeRunStatus from, WorkflowNodeRunStatus to) {
        if (from == null || to == null || from == to) {
            return from == to;
        }
        return NODE_TRANSITIONS.getOrDefault(from, Collections.emptySet()).contains(to);
    }

    /**
     * 校验并返回目标节点状态。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 目标状态
     * @throws BizException 非法迁移
     */
    public static WorkflowNodeRunStatus requireNode(WorkflowNodeRunStatus from, WorkflowNodeRunStatus to) {
        if (!canNode(from, to)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "节点状态不允许从 " + label(from) + " 变更为 " + label(to));
        }
        return to;
    }

    /**
     * 中文状态名，异常文案与展示共用。
     *
     * @param status 状态；null 时给「未知」
     * @return 中文名
     */
    public static String label(WorkflowRunStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case CREATED -> "已创建";
            case RUNNING -> "运行中";
            case PAUSED_HUMAN -> "等待人工";
            case SUCCESS -> "成功";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
    }

    /**
     * 中文节点状态名。
     *
     * @param status 状态；null 时给「未知」
     * @return 中文名
     */
    public static String label(WorkflowNodeRunStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING -> "待运行";
            case RUNNING -> "运行中";
            case SUCCESS -> "成功";
            case FAILED -> "失败";
            case SKIPPED -> "已跳过";
            case WAITING_HUMAN -> "等待人工";
            case CANCELLED -> "已取消";
        };
    }

    private static Map<WorkflowRunStatus, Set<WorkflowRunStatus>> buildRunTransitions() {
        Map<WorkflowRunStatus, Set<WorkflowRunStatus>> map = new EnumMap<>(WorkflowRunStatus.class);
        map.put(WorkflowRunStatus.CREATED, EnumSet.of(
                WorkflowRunStatus.RUNNING,
                WorkflowRunStatus.CANCELLED));
        map.put(WorkflowRunStatus.RUNNING, EnumSet.of(
                WorkflowRunStatus.PAUSED_HUMAN,
                WorkflowRunStatus.SUCCESS,
                WorkflowRunStatus.FAILED,
                WorkflowRunStatus.CANCELLED));
        // 人工处理完回到 RUNNING；超时自动拒绝直接 FAILED
        map.put(WorkflowRunStatus.PAUSED_HUMAN, EnumSet.of(
                WorkflowRunStatus.RUNNING,
                WorkflowRunStatus.FAILED,
                WorkflowRunStatus.CANCELLED));
        return Collections.unmodifiableMap(map);
    }

    private static Map<WorkflowNodeRunStatus, Set<WorkflowNodeRunStatus>> buildNodeTransitions() {
        Map<WorkflowNodeRunStatus, Set<WorkflowNodeRunStatus>> map =
                new EnumMap<>(WorkflowNodeRunStatus.class);
        map.put(WorkflowNodeRunStatus.PENDING, EnumSet.of(
                WorkflowNodeRunStatus.RUNNING,
                WorkflowNodeRunStatus.SKIPPED,
                WorkflowNodeRunStatus.CANCELLED));
        map.put(WorkflowNodeRunStatus.RUNNING, EnumSet.of(
                WorkflowNodeRunStatus.SUCCESS,
                WorkflowNodeRunStatus.FAILED,
                WorkflowNodeRunStatus.WAITING_HUMAN,
                WorkflowNodeRunStatus.CANCELLED));
        map.put(WorkflowNodeRunStatus.WAITING_HUMAN, EnumSet.of(
                WorkflowNodeRunStatus.SUCCESS,
                WorkflowNodeRunStatus.FAILED,
                WorkflowNodeRunStatus.CANCELLED));
        return Collections.unmodifiableMap(map);
    }
}
