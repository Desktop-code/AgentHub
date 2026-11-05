package com.agenthub.eval.domain.service;

/**
 * 打分模型凭据不可用。所属域：eval 的 domain。
 * <p>由 {@link JudgeModelResolver} 实现抛出，{@code EvalRunnerAppService} 捕获后把该 case 的
 * {@code openjudge_status} 记 {@code ERROR}（软通道，<b>不</b>改 verdict / gate），
 * 并且<b>不得</b>退回 sidecar 的环境变量 Key 冒充所选实例。
 * <p>消息里只放实例 id 与原因，<b>禁止</b>拼入 Key 明文。
 */
public class JudgeModelUnavailableException extends RuntimeException {

    /**
     * @param message 中文原因（不含密钥）
     */
    public JudgeModelUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message 中文原因（不含密钥）
     * @param cause   底层异常；调用方不要再把它展开进日志细节
     */
    public JudgeModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
