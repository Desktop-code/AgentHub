package com.agenthub.common.text;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户面文本清洗的契约。所属域：common。
 * <p>
 * 验收口径：给定「超时 / 连不上」类异常，清洗结果不得出现主机、端口、本机脚本路径、
 * gRPC 状态码与异常类名；清洗后没实义内容时必须退回固定话术。
 */
class UserFacingTextTest {

    /**
     * 典型泄漏串：向量库连不上时的原始异常说明（主机 + 端口 + 启动脚本 + 状态码 + 类名）。
     */
    private static final String LEAKY = "无法连接向量库（http://127.0.0.1:19530）："
            + "io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED。"
            + "请确认 Milvus 已启动（本机可运行 D:\\Environment\\milvus\\start.py）";

    /**
     * 主机、端口、脚本、状态码、异常类名、中间件名一个都不能留。
     */
    @Test
    void scrubRemovesHostPortScriptStatusAndExceptionName() {
        String out = UserFacingText.scrub(LEAKY);

        assertFalse(out.contains("127.0.0.1"), out);
        assertFalse(out.contains("19530"), out);
        assertFalse(out.contains("start.py"), out);
        assertFalse(out.contains("D:\\"), out);
        assertFalse(out.contains("DEADLINE_EXCEEDED"), out);
        assertFalse(out.contains("StatusRuntimeException"), out);
        assertFalse(out.contains("Milvus"), out);
        assertFalse(out.contains("grpc"), out);
    }

    /**
     * 堆栈行整行丢弃，不把 "at com.foo.Bar(...)" 留在句子里。
     */
    @Test
    void scrubDropsStackTraceLines() {
        String raw = "同步超时\n\tat com.agenthub.rag.infra.MilvusClientHolder.client(MilvusClientHolder.java:70)\n"
                + "Caused by: java.net.SocketTimeoutException: Read timed out";

        String out = UserFacingText.scrub(raw);

        assertFalse(out.contains("at com.agenthub"), out);
        assertFalse(out.contains("Caused by"), out);
        assertFalse(out.contains("SocketTimeoutException"), out);
        assertTrue(out.contains("同步超时"), out);
    }

    /**
     * safeMessage：基础设施类异常一律换成固定话术，即使 message 里有可读中文。
     */
    @Test
    void safeMessageReplacesInfraFailureWithFallback() {
        assertEquals(UserFacingText.SERVICE_UNAVAILABLE,
                UserFacingText.safeMessage(new SocketTimeoutException("Read timed out"), UserFacingText.SERVICE_UNAVAILABLE));
        assertEquals(UserFacingText.SERVICE_UNAVAILABLE,
                UserFacingText.safeMessage(new ConnectException("Connection refused"), UserFacingText.SERVICE_UNAVAILABLE));
        assertEquals(UserFacingText.SERVICE_UNAVAILABLE,
                UserFacingText.safeMessage(new TimeoutException(), UserFacingText.SERVICE_UNAVAILABLE));
        // gRPC 的 StatusRuntimeException 在真实链路里以类名出现，不引入依赖也应按类名识别
        assertEquals(UserFacingText.SERVICE_UNAVAILABLE,
                UserFacingText.safeMessage(new IllegalStateException("x") {
                    @Override
                    public String getMessage() {
                        return "DEADLINE_EXCEEDED";
                    }
                }, UserFacingText.SERVICE_UNAVAILABLE));
    }

    /**
     * 业务类异常：清洗掉细节后仍保留可读原因，不能被一刀切成通用话术。
     */
    @Test
    void safeMessageKeepsBusinessReasonAfterScrub() {
        String out = UserFacingText.safeMessage(
                new IllegalArgumentException("知识库「售后库」未初始化，无法检索"), "该步执行失败");

        assertEquals("知识库「售后库」未初始化，无法检索", out);
    }

    /**
     * 清洗后只剩遮罩（原始 message 全是路径/端口）时，退回固定话术而不是展示一堆「（已隐藏）」。
     */
    @Test
    void safeMessageFallsBackWhenNothingReadableLeft() {
        String out = UserFacingText.safeMessage(
                new IllegalArgumentException("D:\\Environment\\milvus\\start.py 127.0.0.1:19530"), "该步执行失败");

        assertEquals("该步执行失败", out);
    }

    /**
     * 空异常也走 fallback，不返回 "null" 这类字面量。
     */
    @Test
    void safeMessageHandlesNull() {
        assertEquals(UserFacingText.SERVICE_UNAVAILABLE, UserFacingText.safeMessage(null, null));
    }

    /**
     * 时间写法（10:30）不能被当成端口抹掉：误伤会让业务文案变成乱码。
     */
    @Test
    void scrubKeepsPlainTimeValues() {
        String out = UserFacingText.scrub("预约时间为 10:30，请准时到场");

        assertTrue(out.contains("10:30"), out);
    }

    /**
     * 中文业务数字与文件名不应被误伤（清洗只针对运维特征，不做过度替换）。
     */
    @Test
    void scrubKeepsOrdinaryContent() {
        String out = UserFacingText.scrub("库存不足，仅剩 3 件，请调整下单数量");

        assertEquals("库存不足，仅剩 3 件，请调整下单数量", out);
    }
}
