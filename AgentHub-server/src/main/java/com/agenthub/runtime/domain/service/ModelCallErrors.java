package com.agenthub.runtime.domain.service;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * 把模型客户端异常收成可展示文案。所属域：runtime。
 * 只根据异常类型判断，不拼接异常原文，避免把 URL、Key 或堆栈带进 error_summary。
 */
public final class ModelCallErrors {

    private ModelCallErrors() {
    }

    /**
     * @param error 模型或引擎抛出的异常，可空
     * @return 已脱敏的失败说明；空入参时返回通用文案
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return "模型调用失败";
        }
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof TrialFailure failure
                    && failure.getMessage() != null
                    && !failure.getMessage().isBlank()) {
                return failure.getMessage().trim();
            }
            // 上游非 2xx 的脱敏原文（含状态码与 body 摘要）只进日志与追踪（见 MeterRemarks / CauseDigest），
            // 不在这里透出：本方法的结果会直接写进用户可见的 error_summary，必须保持固定话术。
            String message = match(cursor);
            if (message != null) {
                return message;
            }
            if (cursor.getCause() == null || cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return "模型调用失败";
    }

    /**
     * @return 命中时的文案；未识别返回 null
     */
    private static String match(Throwable error) {
        if (error instanceof SocketTimeoutException
                || error instanceof TimeoutException
                || nameContains(error, "TimeoutException")
                || nameContains(error, "ReadTimeout")
                || nameContains(error, "ConnectTimeout")) {
            return "模型调用超时，请稍后重试";
        }
        if (error instanceof SocketException
                || error instanceof UnknownHostException
                || nameContains(error, "ConnectException")
                || nameContains(error, "SSLException")
                || nameContains(error, "ClosedChannelException")
                || nameContains(error, "ConnectionReset")
                || nameContains(error, "UnresolvedAddress")) {
            return "模型服务连接失败，请检查网络后重试";
        }
        if (nameContains(error, "AuthenticationException")
                || nameContains(error, "Unauthorized")
                || nameEquals(error, "PermissionDeniedException")) {
            return "模型鉴权失败，请检查 API Key";
        }
        if (nameContains(error, "RateLimitException") || nameContains(error, "TooManyRequests")) {
            return "模型调用过于频繁，请稍后重试";
        }
        if (nameEquals(error, "NotFoundException")) {
            return "模型不存在或供应商地址不正确";
        }
        if (nameEquals(error, "BadRequestException") || nameEquals(error, "UnprocessableEntityException")) {
            return "模型请求被拒绝，请检查模型名与参数";
        }
        if (nameContains(error, "InternalServerException") || nameContains(error, "ServerException")) {
            return "模型服务暂时不可用，请稍后重试";
        }
        return null;
    }

    private static boolean nameEquals(Throwable error, String simpleName) {
        return error.getClass().getSimpleName().equals(simpleName);
    }

    private static boolean nameContains(Throwable error, String fragment) {
        String simple = error.getClass().getSimpleName();
        String full = error.getClass().getName();
        return simple.contains(fragment) || full.contains(fragment);
    }
}
