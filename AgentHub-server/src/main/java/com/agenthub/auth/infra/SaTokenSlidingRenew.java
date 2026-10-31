package com.agenthub.auth.infra;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpUtil;

/**
 * Sa-Token 滑动续期。所属域：auth。
 * 框架默认 autoRenew 只刷新 active-timeout；绝对 timeout 需显式 renewTimeout，
 * 才能做到「20 分钟内有鉴权请求则不必重登」。不做 refreshToken 双票。
 */
public final class SaTokenSlidingRenew {

    private SaTokenSlidingRenew() {
    }

    /**
     * 鉴权通过后调用：按全局 timeout 重置绝对有效期。
     * timeout ≤ 0（永久）时跳过，避免误写成非法续期。
     */
    public static void renewAbsoluteTimeout() {
        long timeout = SaManager.getConfig().getTimeout();
        // 仅在配置了有限期时续绝对时钟；与 active-timeout 的 autoRenew 互补
        if (timeout > 0) {
            StpUtil.renewTimeout(timeout);
        }
    }
}
