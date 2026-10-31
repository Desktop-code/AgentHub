package com.agenthub.runtime.infra.agentscope;

import com.agenthub.runtime.domain.service.TrialFailure;

/**
 * 计量备注。所属域：runtime 的 infra。
 * 只保留试跑自己的失败说明或异常类名，不把异常原文写进流水，避免带上地址或密钥。
 */
public final class MeterRemarks {

    private MeterRemarks() {
    }

    /**
     * @param error 模型调用失败，可空
     * @return 最长 500 的备注
     */
    public static String from(Throwable error) {
        if (error == null) {
            return null;
        }
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof TrialFailure failure && failure.getMessage() != null && !failure.getMessage().isBlank()) {
                return clip(failure.getMessage());
            }
            if (cursor.getCause() == null || cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        String name = cursor.getClass().getSimpleName();
        return name == null || name.isBlank() ? "模型调用失败" : clip(name);
    }

    private static String clip(String text) {
        String value = text.trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
