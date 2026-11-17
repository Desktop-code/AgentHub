package com.agenthub.runtime.domain.service;

import com.agenthub.runtime.domain.model.RunSession;
import com.agenthub.runtime.domain.model.RunStatus;

/**
 * 会话要不要对用户说「已取消」。所属域：runtime。
 * <p>
 * 库列 {@code status=CANCELLED} 有三条来源：用户点取消（摘要固定为「已取消」）、
 * 系统打断没写完的一轮、以及启动时把仍标着 RUNNING 的行一律收成取消。
 * 多轮正常结束后会话会停在 RUNNING，第三条会把已经跑完的历史会话改口成取消。
 * 本类只判定展示和能不能再发，不回写库。
 */
public final class SessionCancelFace {

    /** 用户点取消时写入的 error_summary，与 {@code TrialSessionCloser#cancel} 一致 */
    public static final String USER_CANCEL_SUMMARY = "已取消";

    private SessionCancelFace() {
    }

    /**
     * 是不是用户亲手取消。这种行不论最后一轮写没写完，都按已取消。
     *
     * @param session 会话
     * @return true 表示用户取消
     */
    public static boolean userCancelled(RunSession session) {
        if (session == null || !RunStatus.CANCELLED.name().equals(session.getStatus())) {
            return false;
        }
        String summary = session.getErrorSummary() == null ? "" : session.getErrorSummary().trim();
        return USER_CANCEL_SUMMARY.equals(summary);
    }

    /**
     * 左栏提示和发送口要不要挡成「会话已取消」。
     *
     * @param session       库里的会话
     * @param turnStillOpen 最后一轮是否还没写完；已写完的取消行是启动收口误标
     * @return true 表示按已取消展示并拒绝再发
     */
    public static boolean blocksAsCancelled(RunSession session, boolean turnStillOpen) {
        if (session == null || !RunStatus.CANCELLED.name().equals(session.getStatus())) {
            return false;
        }
        if (userCancelled(session)) {
            return true;
        }
        // 没写完就被收口：系统明确打断了这一轮
        return turnStillOpen;
    }

    /**
     * 启动、停机或异常收口能不能把这一行写成取消。
     * 已经写完的一轮保持原状态，交给空闲收口标成功，不能改成取消。
     *
     * @param session       仍可能是 RUNNING 的会话
     * @param turnStillOpen 最后一轮是否还没写完
     * @return true 表示允许写成 CANCELLED
     */
    public static boolean interruptible(RunSession session, boolean turnStillOpen) {
        if (session == null || !RunStatus.RUNNING.name().equals(session.getStatus())) {
            return false;
        }
        return turnStillOpen;
    }

    /**
     * 接口返回给左栏的状态。误标的取消在展示上回到 RUNNING，列值不动。
     *
     * @param session       库里的会话
     * @param turnStillOpen 最后一轮是否还没写完
     * @return 给前端的 status
     */
    public static String statusForClient(RunSession session, boolean turnStillOpen) {
        if (session == null) {
            return null;
        }
        if (RunStatus.CANCELLED.name().equals(session.getStatus()) && !blocksAsCancelled(session, turnStillOpen)) {
            return RunStatus.RUNNING.name();
        }
        return session.getStatus();
    }
}
