package com.agenthub.rag.application;

import com.agenthub.common.error.BizException;
import com.agenthub.common.error.ErrorCode;

/**
 * 绑定启停取值。所属域：rag。
 * <p>
 * 不复用 agent 域的 {@code AgentChecks}：那是 agent 的 application 层，
 * rag 域跨层引用会让依赖方向变成 rag → agent.application，将来拆服务时很麻烦。
 * 规则本身只有「1 或 0」两行，重复一次比制造跨域耦合划算。
 */
final class RagBindChecks {

    /** 1 启用，与全站 status 语义一致 */
    private static final int ENABLED = 1;
    /** 0 停用 */
    private static final int DISABLED = 0;

    private RagBindChecks() {
    }

    /**
     * 新建时未传状态按启用。
     *
     * @param raw 请求里的状态，可空
     * @return 1 或 0
     * @throws BizException 非法值
     */
    static int initialStatus(Integer raw) {
        return raw == null ? ENABLED : exactStatus(raw);
    }

    /**
     * 状态只接受 1 / 0。
     *
     * @param raw 目标状态
     * @return 1 或 0
     * @throws BizException 非法值
     */
    static int exactStatus(Integer raw) {
        if (raw == null || (raw != ENABLED && raw != DISABLED)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态只能是 1 启用或 0 停用");
        }
        return raw;
    }

    /**
     * @param status 库里的状态
     * @return true 表示启用
     */
    static boolean isEnabled(Integer status) {
        return status != null && status == ENABLED;
    }
}
